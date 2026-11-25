// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.health;

import android.content.Context;

/**
 * A health application that accepts bridged band data.
 *
 * <p>Implementations must be idempotent: the caller replays the same batch after a crash, a reboot
 * or a manual sync, and a sink that counts one file twice corrupts the host app's daily totals.
 * Implementations must also never throw: failures are reported through {@link Result} so one broken
 * target cannot stop the others.
 */
public interface HealthSink {
    enum Result {
        /** Accepted and durably queued by the target. */
        DELIVERED,
        /** Already present in the target; nothing to do. */
        ALREADY_PRESENT,
        /** The user turned this target off. */
        DISABLED,
        /** Target app missing, too old, or its side channel has not been installed yet. */
        UNAVAILABLE,
        /** Retryable failure: keep the batch and try again later. */
        RETRY,
        /** Permanent failure for this batch: log it and stop retrying. */
        FAILED
    }

    /** Stable key, used in settings and logs. Never localised. */
    String id();

    /** Human label for the UI. */
    String label();

    /** Whether the user has this target switched on. Cheap; safe to call from the UI thread. */
    boolean enabled(Context context);

    /**
     * Whether the target can actually receive data right now (package installed, expected version
     * family, side channel reachable). Must not start the target app.
     */
    boolean available(Context context);

    /** Delivers one batch. Never throws. */
    Result submit(Context context, HealthBatch batch);
}
