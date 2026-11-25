// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.health;

import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds the {@code value} JSON Mi Health stores for the per-minute detail tables: steps,
 * calories, heart rate, oxygen and stress.
 *
 * <p>Every shape below was read off rows Mi Health wrote itself for this band, so the field names
 * are not guesses:
 *
 * <pre>
 * step_record     {"calories":1.0,"distance":9,"steps":14,"time":1795406100}
 * calorie_record  {"calories":1.0,"time":1795406160}
 * hr_record       {"bpm":86,"time":1795406400}
 * spo2_record     {"spo2":98,"time":1795406400}
 * stress_record   {"stress":32,"time":1795405800}
 * </pre>
 *
 * <p><b>One minute, one row.</b> The table primary key is {@code (key, sid, time)} and native rows
 * sit on exact minutes with {@code endTime = time + 60}. The bridge stores a continuous heart-rate
 * point as a 60-second window but may hold several of them with the same {@code startMs}, so points
 * are folded into one row per minute. Heart rate, oxygen and stress average; steps, calories and
 * distance add up.
 *
 * <p><b>Only active minutes are written, per table.</b> The bridge records a walk-free minute as a
 * zero-step point carrying a base-metabolism calorie value. Mi Health's own tables show the split:
 * 146 step rows against 456 calorie rows over the same three days. So a step row needs steps
 * ({@code steps > 0}), while a calorie row only needs energy ({@code calories > 0}) — writing every
 * bridge interval into both would triple the step table with rows that carry no distance.
 *
 * <p>The daily totals ({@code steps_day}) are deliberately not written. Mi Health derives them
 * from these rows through its own {@code _draft_*} work queue, exactly like the sleep summary.
 */
final class MiHealthVitalsRecord {
    static final String STEP_TABLE = "step_record";
    static final String CALORIE_TABLE = "calorie_record";
    static final String HR_TABLE = "hr_record";
    static final String SPO2_TABLE = "spo2_record";
    static final String STRESS_TABLE = "stress_record";

    /** Every table this class can fill, in the order the report lists them. */
    static final List<String> TABLES = List.of(STEP_TABLE, CALORIE_TABLE, HR_TABLE, SPO2_TABLE,
            STRESS_TABLE);

    private MiHealthVitalsRecord() {}

    /** One ready-to-bind row. {@code timeSeconds} is the primary-key half; the rest is filled by the caller. */
    record Point(String table, String key, long timeSeconds, String value) {}

    /** Folds bridge records into at most one row per table per minute. */
    static List<Point> points(List<HealthRecord> records) {
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        for (HealthRecord record : records) {
            long minute = Math.floorDiv(record.startMs, 60_000L) * 60L;
            switch (record.kind) {
                case "steps_interval" -> {
                    bucket(buckets, STEP_TABLE, minute, false).add(record);
                    // Calories ride on the step interval; Mi Health keeps them in their own table.
                    bucket(buckets, CALORIE_TABLE, minute, false).add(record);
                }
                case "heart_rate" -> {
                    if (!"continuous".equals(record.measurementMode)) continue;
                    bucket(buckets, HR_TABLE, minute, true).add(record);
                }
                case "spo2" -> bucket(buckets, SPO2_TABLE, minute, true).add(record);
                case "stress" -> bucket(buckets, STRESS_TABLE, minute, true).add(record);
                default -> { }
            }
        }
        List<Point> points = new ArrayList<>(buckets.size());
        for (Bucket bucket : buckets.values()) {
            Point point = bucket.point();
            if (point != null) points.add(point);
        }
        return points;
    }

    private static Bucket bucket(Map<String, Bucket> buckets, String table, long minute,
            boolean average) {
        return buckets.computeIfAbsent(table + ':' + minute,
                ignored -> new Bucket(table, minute, average));
    }

    /** The {@code key} column Mi Health's insert trigger whitelists for each table. */
    static String key(String table) {
        return switch (table) {
            case STEP_TABLE -> "steps";
            case CALORIE_TABLE -> "calories";
            case HR_TABLE -> "heart_rate";
            case SPO2_TABLE -> "spo2";
            case STRESS_TABLE -> "stress";
            default -> throw new IllegalArgumentException("MIHEALTH_TABLE_UNMAPPED");
        };
    }

    private static final class Bucket {
        private final String table;
        private final long minute;
        private final boolean average;
        private int sum;
        private int count;
        private int calories;
        private int distance;

        Bucket(String table, long minute, boolean average) {
            this.table = table;
            this.minute = minute;
            this.average = average;
        }

        void add(HealthRecord record) {
            if (record.value != null) {
                sum += record.value.intValue();
                count++;
            }
            if (record.calories != null) calories += record.calories;
            if (record.distance != null) distance += record.distance;
        }

        Point point() {
            try {
                if (CALORIE_TABLE.equals(table)) {
                    if (calories <= 0) return null;
                    JSONObject value = new JSONObject();
                    // Native rows carry a float here; an int would still parse but differs byte-wise.
                    value.put("calories", (double) calories);
                    value.put("time", minute);
                    return new Point(table, key(table), minute, value.toString());
                }
                if (count == 0) return null;
                int measured = average ? (int) Math.round(sum / (double) count) : sum;
                JSONObject value = new JSONObject();
                switch (table) {
                    case STEP_TABLE -> {
                        // Native behaviour: step_record only keeps minutes that took steps. Mi Health
                        // holds 146 step rows over three days against 456 calorie rows, so the
                        // calorie table is the one that records every active minute. Writing a
                        // zero-step row here would inflate the table threefold and hand the
                        // aggregator minutes that carry no distance.
                        if (measured <= 0) return null;
                        value.put("calories", (double) calories);
                        value.put("distance", distance);
                        value.put("steps", measured);
                    }
                    case HR_TABLE -> value.put("bpm", measured);
                    case SPO2_TABLE -> value.put("spo2", measured);
                    case STRESS_TABLE -> value.put("stress", measured);
                    default -> throw new IllegalArgumentException("MIHEALTH_TABLE_UNMAPPED");
                }
                value.put("time", minute);
                return new Point(table, key(table), minute, value.toString());
            } catch (JSONException impossible) {
                throw new IllegalStateException("MIHEALTH_ROW_UNSERIALIZABLE", impossible);
            }
        }
    }
}
