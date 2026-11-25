// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.health;

import android.content.Context;
import android.os.PowerManager;
import io.github.miam1ku.mibandoplusbridge.data.SessionLog;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a sync on its own once a history round finishes, so the manual button is a repair tool
 * rather than the normal path.
 *
 * <p><b>Screen off only.</b> A sync stops Mi Health, replaces its database file and restarts it.
 * Doing that while the user is reading a sleep report would close the app under their hands, so the
 * automatic path stays out of the way and lets the next screen-off window carry it. The manual
 * button has no such guard — the user asked for it, in the foreground, on purpose.
 *
 * <p><b>Ten-minute floor.</b> The bridge pulls history on its own schedule, and a sync costs a
 * 13 MB copy each way plus a host restart. Ten minutes keeps a busy afternoon of band syncs from
 * turning into a chain of file replacements, while still landing well inside the window where
 * Mi Health would otherwise show a hole.
 *
 * <p>A dedicated single thread, not the service's coordinator: a sync blocks for tens of seconds
 * and would starve the band's own timers if it shared that pool.
 */
public final class MiHealthAutoSync {
    private static final long MIN_INTERVAL_MS = 10 * 60 * 1000L;
    private static final int DAYS_BACK = 7;
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MiHealthAutoSync");
        thread.setDaemon(true);
        return thread;
    });

    private MiHealthAutoSync() {}

    /**
     * Called from the history-sync completion callback. Never blocks the caller and never throws:
     * a failed automatic sync must not affect the band's own collection.
     */
    public static void onHistoryCompleted(Context context) {
        try {
            if (!MiHealthSync.autoEnabled(context)) return;
            if (!screenOff(context)) {
                // The normal case in the afternoon: the round lands while the phone is in use.
                SessionLog.line(context, "MIHEALTH_AUTO skip screen-on");
                return;
            }
            long since = System.currentTimeMillis() - MiHealthSync.lastAtMs(context);
            if (since < MIN_INTERVAL_MS) {
                SessionLog.line(context, "MIHEALTH_AUTO skip floor since=" + (since / 1000) + "s");
                return;
            }
            if (!RUNNING.compareAndSet(false, true)) {
                SessionLog.line(context, "MIHEALTH_AUTO skip busy");
                return;
            }
            Context app = context.getApplicationContext();
            WORKER.execute(() -> {
                try {
                    // Re-check on the worker: the user may have turned it off or started a manual
                    // run between the callback and the thread actually picking this up.
                    if (!MiHealthSync.autoEnabled(app)) return;
                    if (!screenOff(app)) {
                        SessionLog.line(app, "MIHEALTH_AUTO skip screen-on (worker)");
                        return;
                    }
                    if (System.currentTimeMillis() - MiHealthSync.lastAtMs(app) < MIN_INTERVAL_MS) {
                        return;
                    }
                    SessionLog.line(app, "MIHEALTH_AUTO start days=" + DAYS_BACK);
                    MiHealthSync.run(app, DAYS_BACK, MiHealthJournal.AUTO);
                } catch (RuntimeException failure) {
                    SessionLog.line(app, "MIHEALTH_AUTO_FAILED " + failure.getClass().getSimpleName());
                } finally {
                    RUNNING.set(false);
                }
            });
        } catch (RuntimeException scheduling) {
            RUNNING.set(false);
        }
    }

    /** True when the screen is off, which is the only window the automatic path may use. */
    private static boolean screenOff(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        // An unknown power state is treated as "in use" so the sync stays away.
        return power != null && !power.isInteractive();
    }
}
