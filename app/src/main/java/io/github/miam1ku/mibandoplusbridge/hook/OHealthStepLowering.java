// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.app.Application;
import android.content.Context;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.HookRuntime;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XC_MethodHook;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedBridge;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Lets one band day row in table 1002 go <em>down</em>.
 *
 * <p>Nothing else can. {@code SportDataStatProcess.processOldStat} merges a stored day row with the
 * incoming one field by field and keeps the larger value, so an insert carrying the true, smaller
 * total is silently discarded while the store still reports success. Table 1002's delete is a no-op
 * as well, so "delete then insert" is not a way out either. The only seam that can move the number
 * down is that merge: rewrite the stored entity before the merge reads it, and the host then
 * persists the corrected row through its own ordinary update path.
 *
 * <p><b>The merge does not run in the process that writes.</b> Measured on the device: only
 * {@code :SportDaemonService} holds {@code databases/database.db} open, the main Health process
 * holds no handle at all, and an insert from {@code insertSportHealthData} is a request to that
 * daemon. A request kept in memory therefore never reached the merge - the hook that was installed
 * next to the writer could not fire however often the row was written. The request is published as
 * a small file in the host's own no-backup directory instead: both Health processes run as the same
 * uid inside the same data directory, so the writer arms it and whichever process performs the
 * merge consumes it.
 *
 * <p><b>Matching is deliberately narrow.</b> A request names the account, the device id, the day,
 * the mode and the exact total currently stored. The merge only counts as ours when every one of
 * them agrees, the request is younger than a few seconds, and it asks for a <em>smaller</em> total.
 * A mismatch does not throw: it means this merge is somebody else's, and their row must be left
 * alone.
 */
final class OHealthStepLowering {
    private static final String ENTITY =
            "com.heytap.databaseengineservice.db.table.DBSportDataStat";
    private static final String PROCESS =
            "com.heytap.databaseengineservice.store.stat.SportDataStatProcess";
    private static final String NAME = "step-lower-request.json";
    /** A request older than this is not applied; the caller reports it as unconfirmed. */
    private static final long REQUEST_TTL_MS = 10_000L;

    private static volatile Access access;
    /** The request this thread is currently applying, so the same call can report it as changed. */
    private static final ThreadLocal<Request> claimed = new ThreadLocal<>();

    private OHealthStepLowering() {}

    /** The file both Health processes use to pass one correction to whichever one merges. */
    static File requestFile(Context context) {
        return new File(context.getNoBackupFilesDir(), NAME);
    }

    /** Installs the merge hook once. Returns false when the host classes are not reachable here. */
    static synchronized boolean install(ClassLoader loader, File channel) {
        if (access != null) return true;
        try {
            Access resolved = new Access(loader);
            XposedBridge.hookMethod(resolved.merge, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Request request = read(channel);
                    if (request == null || request.expired()) return;
                    Object old = param.args[2];
                    // A forced merge already takes every value from the incoming row; it needs no
                    // help, and "old" is not the side that survives it.
                    if (old == null || Boolean.TRUE.equals(param.args[1])) return;
                    if (!request.matches(resolved, old)) return;
                    // The two fields a max merge would otherwise take from the larger side. The
                    // rest of the row is left exactly as the host's own merge reports it.
                    resolved.setSteps.invoke(old, request.steps);
                    resolved.setSync.invoke(old, 0);
                    claimed.set(request);
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Request request = claimed.get();
                    if (request == null) return;
                    claimed.remove();
                    if (param.hasThrowable()) return;
                    // processOldStat returns whether anything changed, and the caller only persists
                    // the row when it did. The rewrite above is already in the entity, so claim the
                    // change on its behalf; otherwise the correction would be dropped here.
                    param.setResult(true);
                    String line = "OHEALTH_STEP_STAT_LOWER_APPLIED date=" + request.date
                            + " was=" + request.previous + " now=" + request.steps
                            + " process=" + Application.getProcessName();
                    HookRuntime.log(line);
                    XposedBridge.log("OplusBandBridge " + line);
                }
            });
            access = resolved;
            return true;
        } catch (ReflectiveOperationException | LinkageError unsupported) {
            HookRuntime.log("OHEALTH_STEP_LOWER_UNAVAILABLE " + unsupported.getClass().getSimpleName()
                    + " process=" + Application.getProcessName());
            return false;
        }
    }

    /**
     * Publishes one correction for the next merge of that exact row. The caller must check the
     * stored total afterwards, because the merge happens in another process.
     */
    static boolean arm(File channel, String account, String device, int date, int mode, int previous,
            int steps) {
        if (channel == null || account == null || account.isBlank() || device == null || device.isBlank()) {
            return false;
        }
        // steps <= 0 is refused on purpose: an empty day is "no evidence", and writing it over a
        // stored total would erase a real number rather than correct one.
        if (date < 19_700_101 || steps <= 0 || previous <= steps) return false;
        Request request = new Request(account, device, date, mode, previous, steps,
                System.currentTimeMillis());
        try {
            Files.write(channel.toPath(), request.toJson().toString().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException | JSONException failed) {
            HookRuntime.log("OHEALTH_STEP_STAT_LOWER_ARM_FAILED " + failed.getClass().getSimpleName());
            return false;
        }
    }

    /** Drops the request whether it was applied or not; a stale file must never outlive its write. */
    static void clear(File channel) {
        if (channel == null) return;
        //noinspection ResultOfMethodCallIgnored
        channel.delete();
    }

    private static Request read(File channel) {
        if (channel == null || !channel.isFile()) return null;
        try {
            String json = new String(Files.readAllBytes(channel.toPath()), StandardCharsets.UTF_8);
            return Request.fromJson(new JSONObject(json));
        } catch (IOException | JSONException unreadable) {
            // A half-written or foreign file is not a request; the next pass writes a fresh one.
            return null;
        }
    }

    /** One armed correction. Package-private so the merge matching can be unit tested. */
    static final class Request {
        final String account, device;
        final int date, mode, previous, steps;
        final long armedAtMs;

        Request(String account, String device, int date, int mode, int previous, int steps,
                long armedAtMs) {
            this.account = account;
            this.device = device;
            this.date = date;
            this.mode = mode;
            this.previous = previous;
            this.steps = steps;
            this.armedAtMs = armedAtMs;
        }

        boolean expired() { return System.currentTimeMillis() - armedAtMs > REQUEST_TTL_MS; }

        boolean matches(Access access, Object old) throws IllegalAccessException,
                java.lang.reflect.InvocationTargetException {
            return matchesValues((String) access.account.invoke(old), (String) access.device.invoke(old),
                    (Integer) access.date.invoke(old), (Integer) access.mode.invoke(old),
                    (Integer) access.steps.invoke(old));
        }

        boolean matchesValues(String account, String device, int date, int mode, int steps) {
            return this.account.equals(account) && this.device.equals(device)
                    && this.date == date && this.mode == mode && this.previous == steps
                    && steps > this.steps;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("account", account).put("device", device).put("date", date)
                    .put("mode", mode).put("previous", previous).put("steps", steps)
                    .put("armedAtMs", armedAtMs);
        }

        static Request fromJson(JSONObject json) throws JSONException {
            return new Request(json.getString("account"), json.getString("device"),
                    json.getInt("date"), json.getInt("mode"), json.getInt("previous"),
                    json.getInt("steps"), json.getLong("armedAtMs"));
        }
    }

    private static final class Access {
        final Method merge, account, device, date, mode, steps, setSteps, setSync;

        Access(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> entity = Class.forName(ENTITY, false, loader);
            Class<?> process = Class.forName(PROCESS, false, loader);
            merge = process.getDeclaredMethod("processOldStat", entity, boolean.class, entity);
            merge.setAccessible(true);
            account = entity.getMethod("getSsoid");
            device = entity.getMethod("getDeviceUniqueId");
            date = entity.getMethod("getDate");
            mode = entity.getMethod("getSportMode");
            steps = entity.getMethod("getTotalSteps");
            setSteps = entity.getMethod("setTotalSteps", int.class);
            setSync = entity.getMethod("setSyncStatus", int.class);
        }
    }
}
