// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.health;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import io.github.miam1ku.mibandoplusbridge.data.SessionLog;

/**
 * Hands bridge-captured activity files to Mi Health (com.mi.health) so that Mi Health parses,
 * stores and uploads them with its own pipeline.
 *
 * <p>Why file level and not measurement level: Mi Health treats a band's activity file as the unit
 * of truth. Its local rows are derived from those files, and its cloud upload is keyed by the file
 * id. Writing rows behind its back produces data that looks right locally and disappears on the next
 * sync. Feeding the original file instead means the parse, the local write, the cloud upload and the
 * dedupe are all Mi Health's own code paths.
 *
 * <p>The receiving side is {@code health.MiHealthIngestHook}, installed in the com.mi.health process
 * by the same libxposed entry point that already handles {@code MiFitnessImportHook}. The hook
 * publishes a provider; this class only knows the provider contract, never Mi Health internals.
 *
 * <p>CURRENT STATE: skeleton. The provider contract is fixed, the com.mi.health side is not
 * implemented yet, so {@link #submit} returns {@link Result#UNAVAILABLE} and the OPPO Health path is
 * unaffected. Anchor survey for Mi Health 3.59.1 is tracked in docs/ADAPT_PLAN.md.
 */
public final class MiHealthSink implements HealthSink {
    public static final String ID = "mihealth";
    static final String PACKAGE = "com.mi.health";
    /** Provider published by MiHealthIngestHook inside the com.mi.health process. */
    public static final String AUTHORITY = "io.github.miam1ku.mibandoplusbridge.mihealth";
    public static final Uri INGEST_URI = Uri.parse("content://" + AUTHORITY + "/ingest");
    public static final String METHOD_INGEST = "ingestActivityFile";
    public static final String METHOD_PROBE = "probe";
    static final String PREFS = "band-sinks";
    static final String KEY_ENABLED = "mihealth-enabled";

    @Override public String id() { return ID; }

    @Override public String label() { return "小米健康"; }

    @Override public boolean enabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, enabled).apply();
    }

    @Override public boolean available(Context context) {
        PackageInfo info = packageInfo(context);
        if (info == null) return false;
        return probe(context);
    }

    @Override public Result submit(Context context, HealthBatch batch) {
        if (!enabled(context)) return Result.DISABLED;
        if (batch.payload == null || batch.payload.length == 0) {
            log(context, "MIHEALTH_BATCH_EMPTY " + batch.describe());
            return Result.FAILED;
        }
        // The receiving end decides dedupe: it keys on fileHash, the same id the band allocated.
        Bundle args = new Bundle();
        args.putString("fileHash", batch.fileHash);
        args.putString("deviceId", batch.deviceId);
        args.putString("firmware", batch.firmware);
        args.putLong("capturedAtMs", batch.capturedAtMs);
        args.putInt("fromIndex", batch.fromIndex);
        args.putByteArray("payload", batch.payload);
        try {
            Bundle reply = context.getContentResolver().call(INGEST_URI, METHOD_INGEST, null, args);
            if (reply == null) {
                log(context, "MIHEALTH_INGEST_NO_REPLY " + batch.describe());
                return Result.UNAVAILABLE;
            }
            String status = reply.getString("status", "");
            switch (status) {
                case "delivered":
                    log(context, "MIHEALTH_INGEST_DELIVERED " + batch.describe());
                    return Result.DELIVERED;
                case "present":
                    return Result.ALREADY_PRESENT;
                case "rejected":
                    log(context, "MIHEALTH_INGEST_REJECTED " + batch.describe()
                            + " reason=" + reply.getString("reason", ""));
                    return Result.FAILED;
                default:
                    log(context, "MIHEALTH_INGEST_UNKNOWN status=" + status);
                    return Result.RETRY;
            }
        } catch (IllegalArgumentException noProvider) {
            // Provider absent: hook not installed for this Mi Health version, or scope not ticked.
            log(context, "MIHEALTH_PROVIDER_MISSING");
            return Result.UNAVAILABLE;
        } catch (RuntimeException failure) {
            log(context, "MIHEALTH_INGEST_FAILED " + failure.getClass().getSimpleName());
            return Result.RETRY;
        }
    }

    /** True when the com.mi.health side answers a probe with its supported contract version. */
    private boolean probe(Context context) {
        try {
            Bundle reply = context.getContentResolver().call(INGEST_URI, METHOD_PROBE, null, null);
            return reply != null && reply.getInt("contractVersion", 0) >= 1;
        } catch (IllegalArgumentException noProvider) {
            return false;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    static PackageInfo packageInfo(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(PACKAGE, 0);
        } catch (Exception missing) {
            return null;
        }
    }

    /** Version string of the installed Mi Health, for the UI and for anchor selection. */
    public static String versionName(Context context) {
        PackageInfo info = packageInfo(context);
        return info == null ? "" : String.valueOf(info.versionName);
    }

    private static void log(Context context, String line) {
        SessionLog.line(context, line);
    }
}
