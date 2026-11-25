// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.health;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Root plumbing for Mi Health's own databases: locate the account file, copy it out, copy it back,
 * and restart the host afterwards.
 *
 * <p>Why a copy instead of a direct write: {@code /data/data/com.mi.health} is outside this app's
 * uid, and the device has no {@code sqlite3} binary. Copying the file into this app's private
 * directory lets Android's own SQLite do the write, and the copy goes back through the same root
 * channel. The measured cost on a 13 MB file is under a second.
 *
 * <p>Safety rules encoded here, each one paid for by an incident during development:
 * <ul>
 *   <li>The host must be provably dead before the copy is taken. {@code am force-stop} returns
 *       before the process is gone; writing under a live process truncated the main database to
 *       4096 bytes once.
 *   <li>The copy goes back by rename, never by in-place overwrite, so a failed push leaves the
 *       original intact.
 *   <li>The stale {@code -wal} and {@code -shm} are removed first, otherwise the host replays
 *       pre-write pages over the file that was just installed.
 *   <li>Owner, group, mode and SELinux label are re-applied; a root-owned file is unreadable to
 *       the host and it crashes with SQLiteDiskIOException on the next open.
 * </ul>
 */
final class MiHealthStore {
    static final String PACKAGE = "com.mi.health";
    static final String DATABASES = "/data/data/com.mi.health/databases";
    static final String DB = "fitness_data";
    /** The activity 3.59.1 actually opens on; {@code monkey -p} does not reach it. */
    private static final String SPLASH_ACTIVITY = "com.xiaomi.fitness.login.SplashActivity";
    private static final int SCRIPT_TIMEOUT_SECONDS = 60;

    /** Root step failed; the code is stable and safe to show in a log line or the UI. */
    static final class Failure extends Exception {
        final String code;

        Failure(String code) {
            super(code);
            this.code = code;
        }
    }

    private MiHealthStore() {}

    /** Private scratch directory. Its path must stay shell-safe: KernelSU passes it unquoted. */
    static File workDir(Context context) throws Failure {
        File dir = new File(context.getNoBackupFilesDir(), "mihealth-sync");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Failure("MIHEALTH_WORK_DIR_MISSING");
        if (!dir.getAbsolutePath().matches("[A-Za-z0-9_./-]+")) {
            throw new Failure("MIHEALTH_WORK_DIR_UNSAFE");
        }
        return dir;
    }

    /**
     * {@code /data/data/com.mi.health/databases/<userId>/cn}, the file that holds health detail.
     * The user id is the Mi account serial, so it changes when the account changes; it is resolved
     * on every run rather than cached.
     */
    static String accountDir(Context context) throws Failure {
        File dir = workDir(context);
        String body = "set -eu\n"
                + "for d in " + DATABASES + "/*; do\n"
                + "  case \"$(basename \"$d\")\" in *[!0-9]*) continue ;; esac\n"
                + "  if [ -f \"$d/cn/" + DB + "\" ]; then echo \"$d/cn\"; exit 0; fi\n"
                + "done\n"
                + "exit 4\n";
        String out = execute(context, dir, "account", body, "MIHEALTH_ACCOUNT_DIR_MISSING");
        for (String line : out.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(DATABASES + "/") && trimmed.endsWith("/cn")) return trimmed;
        }
        throw new Failure("MIHEALTH_ACCOUNT_DIR_MISSING");
    }

    /**
     * Copies the account database into {@code dir}.
     *
     * @param stopHost true when the copy will be written to and pushed back. A read-only peek
     *                 skips the stop, so the pre-check costs the user nothing.
     */
    static void copyOut(Context context, File dir, String accountDir, boolean stopHost) throws Failure {
        String body = "set -eu\n"
                + "SRC=" + accountDir + "\n"
                + "DEST=" + dir.getAbsolutePath() + "\n"
                + "test -f \"$SRC/" + DB + "\"\n"
                + (stopHost ? "am force-stop " + PACKAGE + " >/dev/null 2>&1 || true\n"
                        + "n=0\n"
                        + "while [ $n -lt 40 ]; do\n"
                        + "  pidof " + PACKAGE + " >/dev/null 2>&1 || break\n"
                        + "  sleep 0.25\n"
                        + "  n=$((n+1))\n"
                        + "done\n"
                        + "if pidof " + PACKAGE + " >/dev/null 2>&1; then echo HOST_ALIVE; exit 3; fi\n" : "")
                + "rm -f \"$DEST/" + DB + "\" \"$DEST/" + DB + "-wal\" \"$DEST/" + DB + "-shm\"\n"
                + "cp -a \"$SRC/" + DB + "\" \"$DEST/" + DB + "\"\n"
                + "if [ -f \"$SRC/" + DB + "-wal\" ]; then cp -a \"$SRC/" + DB + "-wal\" \"$DEST/" + DB + "-wal\"; fi\n"
                // The shared-memory index is deliberately not copied: it carries the live process's
                // lock, and opening a copy that points at it blocks until that process exits.
                + "rm -f \"$DEST/" + DB + "-shm\"\n"
                + "u=$(stat -c %u \"$DEST\")\n"
                + "g=$(stat -c %g \"$DEST\")\n"
                + "chown \"$u:$g\" \"$DEST\"/" + DB + "*\n"
                + "chmod 600 \"$DEST\"/" + DB + "*\n"
                + "echo COPIED\n";
        String code = stopHost ? "MIHEALTH_HOST_BUSY" : "MIHEALTH_COPY_FAILED";
        String out = execute(context, dir, stopHost ? "pull" : "peek", body, code);
        if (!out.contains("COPIED")) throw new Failure(code);
    }

    /** Installs the written copy over the live file, then brings the host back up. */
    static void copyBack(Context context, File dir, String accountDir) throws Failure {
        String body = "set -eu\n"
                + "SRC=" + dir.getAbsolutePath() + "\n"
                + "DEST=" + accountDir + "\n"
                + "test -f \"$SRC/" + DB + "\"\n"
                + "test -f \"$DEST/" + DB + "\"\n"
                + "u=$(stat -c %u \"$DEST/" + DB + "\")\n"
                + "g=$(stat -c %g \"$DEST/" + DB + "\")\n"
                + "cp -f \"$SRC/" + DB + "\" \"$DEST/.bridge-" + DB + ".new\"\n"
                + "chown \"$u:$g\" \"$DEST/.bridge-" + DB + ".new\"\n"
                + "chmod 660 \"$DEST/.bridge-" + DB + ".new\"\n"
                + "rm -f \"$DEST/" + DB + "-wal\" \"$DEST/" + DB + "-shm\"\n"
                + "mv -f \"$DEST/.bridge-" + DB + ".new\" \"$DEST/" + DB + "\"\n"
                + "restorecon \"$DEST/" + DB + "\" >/dev/null 2>&1 || true\n"
                + "echo INSTALLED\n";
        String out = execute(context, dir, "push", body, "MIHEALTH_INSTALL_FAILED");
        if (!out.contains("INSTALLED")) throw new Failure("MIHEALTH_INSTALL_FAILED");
    }

    /**
     * Brings Mi Health back to the foreground after the install.
     *
     * <p>{@code monkey -p} is the version-proof entry, but it silently does nothing on 3.59.1:
     * the launcher category resolves to a splash the monkey filter will not start, and the app
     * stays closed with the data already installed. The explicit activity is tried first and
     * monkey stays as the fallback for a release that renames it.
     */
    static void startHost(Context context) {
        try {
            execute(context, workDir(context), "start",
                    "am start -n " + PACKAGE + "/" + SPLASH_ACTIVITY + " >/dev/null 2>&1"
                            + " || monkey -p " + PACKAGE + " -c android.intent.category.LAUNCHER 1"
                            + " >/dev/null 2>&1 || true\n"
                            + "echo STARTED\n",
                    "MIHEALTH_START_FAILED");
        } catch (Failure ignored) {
            // Losing the relaunch is cosmetic: the user can open Mi Health, the data is installed.
        }
    }

    /**
     * Writes the script and runs it through root. KernelSU hands {@code su -c} to another shell, so
     * the command is a single unquoted path: a quoted string would be split and exit 1.
     */
    private static String execute(Context context, File dir, String name, String body, String failCode)
            throws Failure {
        File script = new File(dir, name + ".sh");
        try (FileOutputStream out = new FileOutputStream(script)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException unwritable) {
            throw new Failure("MIHEALTH_SCRIPT_UNWRITABLE");
        }
        String path = script.getAbsolutePath();
        if (!path.matches("[A-Za-z0-9_./-]+")) throw new Failure("MIHEALTH_SCRIPT_UNSAFE");
        Process process = null;
        try {
            // -M leaves the app mount namespace, where other apps' /data/data is hidden.
            process = new ProcessBuilder("su", "-M", "-c", "sh " + path)
                    .redirectErrorStream(true).start();
            if (!process.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new Failure("ROOT_ACTION_TIMEOUT");
            }
            String text = readLimited(process.getInputStream(), 8192);
            if (process.exitValue() != 0) {
                throw new Failure(text.contains("HOST_ALIVE") ? "MIHEALTH_HOST_BUSY" : failCode);
            }
            return text;
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new Failure("ROOT_ACTION_FAILED");
        } finally {
            if (process != null) process.destroy();
            script.delete();
        }
    }

    private static String readLimited(InputStream in, int maxOutput) throws IOException {
        byte[] buffer = new byte[1024];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int total = 0;
        while (total < maxOutput) {
            int read = in.read(buffer, 0, Math.min(buffer.length, maxOutput - total));
            if (read < 0) break;
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
