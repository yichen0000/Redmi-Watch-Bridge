// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.health;

import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import io.github.miam1ku.mibandoplusbridge.hook.OHealthSleepPlan;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds the {@code value} JSON that Mi Health stores in {@code fitness_data.sleep_segment}.
 *
 * <p>The shape was read off rows Mi Health wrote itself (REDMI Watch 6, 2026-11-21 and 11-23) and
 * every field below is copied from that sample. Two things are easy to get wrong and both were:
 *
 * <p><b>Stage codes are not the OPPO codes.</b> Mi Health numbers 2 deep, 3 light, 4 REM, 5 awake,
 * which is the band's own numbering, not the OHealth host numbering this project's sleep planner
 * emits (2 deep, 3 REM, 4 light). Cross-checked against two native rows where the field values
 * equal the per-state minutes of {@code items}: {@code sleep_deep_duration} always equals the state
 * 2 minutes. Writing the OPPO numbering verbatim swaps deep and REM in the stage chart.
 *
 * <p><b>Minutes and seconds differ per field.</b> {@code duration} and the per-stage durations are
 * minutes; {@code bed_duration}, {@code bedtime}, {@code wake_up_time} and every timestamp are
 * seconds. Efficiency is {@code duration * 60 / bed_duration}, so treating {@code duration} as
 * seconds writes 2 % and Mi Health displays it.
 *
 * <p>The daily summary is deliberately not written. Mi Health keeps a work queue
 * ({@code _draft_sleep_segment}) that the insert trigger fills on its own, and its aggregator
 * derives score, evaluation and the cloud upload from the detail row. Writing the summary by hand
 * means guessing three undocumented evaluation scores.
 */
final class MiHealthSleepRecord {
    /** The detail table these rows land in. */
    static final String TABLE = "sleep_segment";

    /** Stage codes as Mi Health stores them in {@code items[].state}. */
    private static final int MI_DEEP = 2;
    private static final int MI_LIGHT = 3;
    private static final int MI_REM = 4;
    private static final int MI_AWAKE = 5;

    private MiHealthSleepRecord() {}

    /** OHealth host state to Mi Health stage code. Only REM and light trade places. */
    static int miState(int hostState) {
        return switch (hostState) {
            case OHealthSleepPlan.DEEP -> MI_DEEP;
            case OHealthSleepPlan.REM -> MI_REM;
            case OHealthSleepPlan.LIGHT -> MI_LIGHT;
            case OHealthSleepPlan.AWAKE -> MI_AWAKE;
            // An unclassified duration still needs a band on the chart; light is the neutral one.
            case OHealthSleepPlan.UNSTAGED -> MI_LIGHT;
            default -> throw new IllegalArgumentException("SLEEP_STATE_UNMAPPED");
        };
    }

    /** One {@code sleep_segment} row, ready to bind. */
    record Row(String key, String sid, long timeSeconds, long endTimeSeconds, boolean complete,
               String value, int zoneOffsetSeconds, String zoneName) {}

    static Row row(OHealthSleepPlan.Night night, List<HealthRecord> records, String sid, ZoneId zone) {
        long fallMs = night.fallAsleepMs();
        long wakeMs = night.wakeMs();
        long wakeSeconds = wakeMs / 1000;
        int offset = zone.getRules().getOffset(Instant.ofEpochMilli(wakeMs)).getTotalSeconds();

        List<int[]> items = new ArrayList<>();
        long awakeCount = 0;
        for (OHealthSleepPlan.Segment segment : night.segments()) {
            int state = miState(segment.sleepState());
            items.add(new int[] {(int) (segment.startMs() / 1000), (int) (segment.endMs() / 1000), state});
            if (state == MI_AWAKE) awakeCount++;
        }

        long duration = night.sleepMinutes();
        // The band does not timestamp getting into bed; native rows sit a few minutes before sleep.
        long bedTimestamp = fallMs / 1000 - 240;
        long bedDuration = Math.max(1, wakeSeconds - bedTimestamp);

        Vitals vitals = Vitals.of(records, fallMs, wakeMs);

        JSONObject value = new JSONObject();
        try {
            value.put("avg_breath", vitals.breath);
            value.put("avg_hr", vitals.avgHr);
            value.put("avg_spo2", vitals.avgSpo2);
            value.put("bedtime", fallMs / 1000);
            value.put("sleep_deep_duration", night.deepMinutes());
            value.put("device_bedtime", fallMs / 1000);
            value.put("device_wake_up_time", wakeSeconds);
            value.put("bed_timestamp", bedTimestamp);
            value.put("is_uncomplete", false);
            value.put("out_bed_timestamp", wakeSeconds);
            value.put("sleep_light_duration", night.lightMinutes());
            value.put("bed_duration", bedDuration);
            value.put("max_hr", vitals.maxHr);
            value.put("max_spo2", vitals.maxSpo2);
            value.put("min_hr", vitals.minHr);
            value.put("min_spo2", vitals.minSpo2);
            value.put("protoTime", wakeSeconds);
            value.put("sleep_rem_duration", night.remMinutes());
            value.put("duration", duration);
            value.put("sleep_efficiency", (int) Math.round(duration * 60.0 * 100.0 / bedDuration));
            JSONArray stages = new JSONArray();
            for (int[] item : items) stages.put(item(item[0], item[1], item[2]));
            value.put("items", stages);
            value.put("sleep_algorithm_version", 1);
            // Constant in every native row on this device; it is a bucket index, not a UTC offset.
            value.put("timezone", 32);
            value.put("version", 5);
            value.put("awake_count", awakeCount);
            value.put("sleep_awake_duration", night.wakeMinutes());
            value.put("wake_up_time", wakeSeconds);
        } catch (JSONException impossible) {
            throw new IllegalStateException("MIHEALTH_ROW_UNSERIALIZABLE", impossible);
        }

        return new Row("sleep", sid, wakeSeconds, wakeSeconds + 60, true, value.toString(), offset,
                zone.getId());
    }

    private static JSONObject item(int startSeconds, int endSeconds, int state) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("start_time", startSeconds);
        item.put("end_time", endSeconds);
        item.put("state", state);
        return item;
    }

    /** Heart rate, oxygen and breath over the same window Mi Health uses: the sleep session itself. */
    private record Vitals(int avgHr, int maxHr, int minHr, int avgSpo2, int maxSpo2, int minSpo2,
            int breath) {
        static Vitals of(List<HealthRecord> records, long fallMs, long wakeMs) {
            long hrSum = 0, hrCount = 0, hrMax = 0, hrMin = Integer.MAX_VALUE;
            long spSum = 0, spCount = 0, spMax = 0, spMin = Integer.MAX_VALUE;
            long breathSum = 0, breathCount = 0;
            for (HealthRecord record : records) {
                if (record.startMs < fallMs || record.startMs >= wakeMs || record.value == null) continue;
                int measured = record.value.intValue();
                switch (record.kind) {
                    case "heart_rate" -> {
                        // The nightly series is the continuous one. A resting or walking point is a
                        // separate daily value and would bias the average.
                        if (!"continuous".equals(record.measurementMode)) continue;
                        hrSum += measured;
                        hrCount++;
                        hrMax = Math.max(hrMax, measured);
                        hrMin = Math.min(hrMin, measured);
                    }
                    case "spo2" -> {
                        spSum += measured;
                        spCount++;
                        spMax = Math.max(spMax, measured);
                        spMin = Math.min(spMin, measured);
                    }
                    case "sleep_breath" -> {
                        breathSum += measured;
                        breathCount++;
                    }
                    default -> { }
                }
            }
            int breath = 0;
            if (breathCount > 0) {
                // The band reports an uncalibrated breath magnitude; native rows carry a rate near
                // 11 per minute, which this scale reproduces on this firmware.
                breath = clamp((int) Math.round(breathSum / (double) breathCount / 10.0), 6, 24);
            }
            return new Vitals(hrCount == 0 ? 0 : (int) (hrSum / hrCount), (int) hrMax,
                    hrMin == Integer.MAX_VALUE ? 0 : (int) Math.min(hrMin, Integer.MAX_VALUE),
                    spCount == 0 ? 0 : (int) (spSum / spCount), (int) spMax,
                    spMin == Integer.MAX_VALUE ? 0 : (int) Math.min(spMin, Integer.MAX_VALUE), breath);
        }

        private static int clamp(int value, int low, int high) {
            return Math.max(low, Math.min(high, value));
        }
    }
}
