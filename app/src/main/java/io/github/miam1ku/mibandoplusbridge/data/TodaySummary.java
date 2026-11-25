// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.Context;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * One read of everything the overview page puts on screen as a number: today's steps and heart rate
 * from the band snapshot, last night's sleep and the latest vitals from the local store.
 *
 * <p>Kept as a single immutable snapshot rather than a set of getters so the page can render once
 * and stay consistent — mixing a prefs read with two queries would let a background sync land
 * between them and show a heart rate from a different minute than the steps beside it.
 *
 * <p>Everything here is failure-tolerant: a missing store, a locked device or an unconfirmed
 * account produces zeroes and {@link #vitalsReadable} = false, never an exception. The page treats
 * a zero as "no value", which is what the band itself means by an absent sample.
 */
public final class TodaySummary {
    /** How far back last night's sleep may have started, so an 8-hour night is still found. */
    private static final long SLEEP_LOOKBACK_MS = 36L * 60 * 60 * 1000;
    /** Vitals older than this are not "current" and are left out rather than shown with a stale time. */
    private static final long VITALS_LOOKBACK_MS = 6L * 60 * 60 * 1000;
    /** Band stage code for deep sleep, from {@code Copy.stageName}. */
    private static final int STAGE_DEEP = 2;

    public final boolean registered;
    public final long steps;
    public final long stepsAtMs;
    public final int heartRate;
    public final long heartRateAtMs;
    public final int spo2;
    public final long spo2AtMs;
    public final int stress;
    public final long stressAtMs;
    public final int sleepMinutes;
    public final int deepSleepMinutes;
    public final long sleepStartMs;
    public final long sleepEndMs;
    /** False when the OPPO Health account is not confirmed, so vitals cannot be read at all. */
    public final boolean vitalsReadable;

    private TodaySummary(boolean registered, long steps, long stepsAtMs, int heartRate,
            long heartRateAtMs, int spo2, long spo2AtMs, int stress, long stressAtMs,
            int sleepMinutes, int deepSleepMinutes, long sleepStartMs, long sleepEndMs,
            boolean vitalsReadable) {
        this.registered = registered;
        this.steps = steps;
        this.stepsAtMs = stepsAtMs;
        this.heartRate = heartRate;
        this.heartRateAtMs = heartRateAtMs;
        this.spo2 = spo2;
        this.spo2AtMs = spo2AtMs;
        this.stress = stress;
        this.stressAtMs = stressAtMs;
        this.sleepMinutes = sleepMinutes;
        this.deepSleepMinutes = deepSleepMinutes;
        this.sleepStartMs = sleepStartMs;
        this.sleepEndMs = sleepEndMs;
        this.vitalsReadable = vitalsReadable;
    }

    public static TodaySummary read(Context context) {
        BandStateRepository repository = new BandStateRepository(context);
        boolean registered = repository.isRegistered();
        LocalPrefs state = LocalPrefs.open(context, "band-state");
        long steps = registered ? repository.stepsToday() : -1;
        long stepsAtMs = registered ? repository.stepsAtMs() : 0;
        int heartRate = registered ? state.getInt("heartRate", 0) : 0;
        long heartRateAtMs = registered ? state.getLong("heartRateAtMs", 0) : 0;

        int spo2 = 0;
        long spo2AtMs = 0;
        int stress = 0;
        long stressAtMs = 0;
        int sleepMinutes = 0;
        int deepSleepMinutes = 0;
        long sleepStartMs = 0;
        long sleepEndMs = 0;
        boolean vitalsReadable = false;

        String deviceId = repository.deviceId();
        long now = System.currentTimeMillis();
        if (!deviceId.isBlank()) {
            try (HealthRecordStore store = new HealthRecordStore(context)) {
                // Sleep is device-local: it stays readable before the account is confirmed, so it is
                // read first and kept even when the vitals queries below are refused.
                List<HealthRecord> nights =
                        store.storedRecords(deviceId, "sleep_interval", now - SLEEP_LOOKBACK_MS, now);
                for (HealthRecord night : nights) {
                    if (night.endMs > sleepEndMs) {
                        sleepStartMs = night.startMs;
                        sleepEndMs = night.endMs;
                    }
                }
                if (sleepEndMs > sleepStartMs) {
                    sleepMinutes = (int) Math.min(24 * 60, (sleepEndMs - sleepStartMs) / 60_000L);
                    for (HealthRecord stage : store.storedRecords(deviceId, "sleep_stage",
                            sleepStartMs, sleepEndMs)) {
                        if (stage.stage != null && stage.stage == STAGE_DEEP) {
                            deepSleepMinutes += (int) ((stage.endMs - stage.startMs) / 60_000L);
                        }
                    }
                }
                long since = now - VITALS_LOOKBACK_MS;
                for (HealthRecord oxygen : store.storedRecords(deviceId, "spo2", since, now)) {
                    if (number(oxygen) > 0 && oxygen.startMs >= spo2AtMs) {
                        spo2 = number(oxygen);
                        spo2AtMs = oxygen.startMs;
                    }
                }
                for (HealthRecord worry : store.storedRecords(deviceId, "stress", since, now)) {
                    if (number(worry) > 0 && worry.startMs >= stressAtMs) {
                        stress = number(worry);
                        stressAtMs = worry.startMs;
                    }
                }
                vitalsReadable = true;
            } catch (SecurityException accountNotConfirmed) {
                // Steps and sleep above stay; the two vitals tiles show nothing.
            } catch (Exception unavailable) {
                // A closed or still-initialising store: the page falls back to "no value".
            }
        }
        return new TodaySummary(registered, steps, stepsAtMs, heartRate, heartRateAtMs, spo2,
                spo2AtMs, stress, stressAtMs, sleepMinutes, deepSleepMinutes, sleepStartMs,
                sleepEndMs, vitalsReadable);
    }

    private static int number(HealthRecord record) {
        return record.value == null ? 0 : record.value.intValue();
    }

    /** Start of the current local day, for "is this sample from today" checks. */
    public static long dayStartMs() {
        ZoneId zone = ZoneId.systemDefault();
        return LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();
    }
}
