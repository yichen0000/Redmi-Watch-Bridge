// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import io.github.miam1ku.mibandoplusbridge.data.LocalPrefs;
import io.github.miam1ku.mibandoplusbridge.data.SessionLog;

/**
 * Turns OPPO Health's own pedometer off and back on.
 *
 * <p>The step ring on Health's home card is not the bridge's daily row. Health runs its own
 * {@code com.heytap.sports.step.stepdaemon.sensor.StepCounterListener} against the phone's
 * {@code step_count} sensor and keeps adding those steps to the number on the card, so a band that
 * counted 1241 today shows next to a phone that counted 5327 — measured on 2026-11-24: the card sat
 * at 6568, the owner walked 111 steps with the phone, and the card moved to 6679 while the band
 * stayed at 1241.
 *
 * <p>Nothing the bridge writes can settle that difference. {@code OHealthStepWriter} writes the
 * band's day as {@code sport_mode=-3} and only ever raises a value ({@code publishSteps}), so the
 * larger phone-derived number wins every time. The one lever that stops the phone side without
 * touching Health's stored data is the sensor permission: {@code ACTIVITY_RECOGNITION} is what lets
 * Health read {@code step_count}, and revoking it from this package leaves the band's rows alone.
 *
 * <p>The cost is stated where the switch is: workout auto-detection and the sedentary reminder use
 * the same sensor and stop working too.
 */
public final class PhoneStepControl {
    static final String HEALTH = "com.heytap.health";
    static final String PERMISSION = Manifest.permission.ACTIVITY_RECOGNITION;
    private static final String PREFS = "phone-steps";
    private static final String SUSPEND = "suspend";

    private PhoneStepControl() { }

    public static boolean installed(Context context) {
        try {
            context.getPackageManager().getPackageInfo(HEALTH, 0);
            return true;
        } catch (PackageManager.NameNotFoundException absent) {
            return false;
        }
    }

    /** True when Health can no longer read the phone's step counter. */
    public static boolean suspended(Context context) {
        if (!installed(context)) return false;
        return context.getPackageManager().checkPermission(PERMISSION, HEALTH)
                != PackageManager.PERMISSION_GRANTED;
    }

    /** The user's stored choice, even while a grant Health asked for is still in place. */
    public static boolean wanted(Context context) {
        return LocalPrefs.open(context, PREFS).getBoolean(SUSPEND, false);
    }

    /**
     * Revokes or grants the sensor permission and confirms the platform really applied it.
     *
     * <p>{@code pm} exits 0 for a permission it refuses to change (a fixed or policy-managed grant),
     * so the exit code is not the answer — the permission state read back afterwards is.
     */
    public static void setSuspended(Context context, boolean suspend) throws OwnershipController.Failure {
        if (!installed(context)) throw new OwnershipController.Failure("HEALTH_APP_MISSING");
        if (suspended(context) == suspend) {
            remember(context, suspend);
            return;
        }
        OwnershipController.runFixedCommand(command(suspend), 30);
        if (suspended(context) != suspend) {
            throw new OwnershipController.Failure(
                    suspend ? "PHONE_STEPS_REVOKE_UNCONFIRMED" : "PHONE_STEPS_GRANT_UNCONFIRMED");
        }
        remember(context, suspend);
    }

    /**
     * Re-applies a stored "off" after Health asked the permission back.
     *
     * <p>Health shows its own dialog the next time it wants the sensor, and a tap there puts the
     * grant back without the bridge ever seeing it. Runs one root command only when the stored
     * choice and the live state disagree, so the common case costs a permission read.
     *
     * @return true when this call changed the state back
     */
    public static boolean reconcile(Context context) {
        if (!wanted(context) || !installed(context) || suspended(context)) return false;
        try {
            setSuspended(context, true);
            SessionLog.line(context, "phone steps re-suspended");
            return true;
        } catch (OwnershipController.Failure failed) {
            SessionLog.line(context, "phone steps reconcile failed " + failed.code);
            return false;
        }
    }

    /** {@code pm revoke|grant --user 0 com.heytap.health android.permission.ACTIVITY_RECOGNITION}. */
    static String command(boolean suspend) {
        return "pm " + (suspend ? "revoke" : "grant") + " --user 0 " + HEALTH + " " + PERMISSION;
    }

    private static void remember(Context context, boolean suspend) {
        LocalPrefs.open(context, PREFS).edit().putBoolean(SUSPEND, suspend).commit();
    }
}
