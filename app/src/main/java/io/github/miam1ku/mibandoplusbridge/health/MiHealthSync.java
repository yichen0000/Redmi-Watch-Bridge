// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.health;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.data.BindingStore;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecordStore;
import io.github.miam1ku.mibandoplusbridge.data.MiFitnessDatabase;
import io.github.miam1ku.mibandoplusbridge.data.MiFitnessDeviceRows;
import io.github.miam1ku.mibandoplusbridge.data.SessionLog;
import io.github.miam1ku.mibandoplusbridge.hook.OHealthSleepPlan;
import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/**
 * Hands the data the bridge already captured to Mi Health, for the days Mi Health is missing.
 *
 * <p>The bridge is the only BLE client while it owns the band, so Mi Health never sees the band's
 * activity files for those days. It does, however, accept rows in its own health tables, and every
 * detail table has an insert trigger that queues the day for its normal aggregation, scoring and
 * upload. That is the whole mechanism: recompute the row Mi Health would have written, put it where
 * Mi Health looks, and let Mi Health do everything after that. The daily summaries are never
 * written by hand — {@code _draft_*} queues already carry the work.
 *
 * <p><b>One host restart per run, not per type.</b> Every write needs Mi Health to be gone while its
 * database is copied out and replaced. Writing each type with its own copy cycle would restart the
 * host six times per sync, so all tables are filled inside a single copy-out/write/copy-back cycle.
 *
 * <p><b>Idempotent by primary key.</b> Detail tables key on {@code (key, sid, time)}; the bridge's
 * minute-aligned rows therefore line up with Mi Health's own minute rows. A row that exists is
 * skipped, and a row the user deleted ({@code isDeleted=1}) is revived rather than counted as
 * present — deleting data is exactly the case this sync exists to repair.
 */
public final class MiHealthSync {
    private static final String PREFS = "mihealth-sync";
    private static final String KEY_LAST_TEXT = "last-text";
    private static final String KEY_LAST_AT = "last-at";
    private static final String KEY_AUTO = "auto-enabled";
    /** One-shot: the {@code timeIn0Tz} sign repair below runs until it has completed once. */
    private static final String KEY_TZ_REPAIRED = "tz-offset-repaired";
    /** Heart rate, oxygen and breath are fetched per night; a week of them at once exceeds the store page limit. */
    private static final long NIGHT_PAD_MS = 60_000L;
    /**
     * How recently the band has to have written the night's last minute for the night to count as
     * still open. The bridge syncs every half hour or so, so an open night's last row is minutes
     * old while a closed one is hours old; 90 minutes separates the two without ever waiting a
     * whole night for a late-morning wake-up.
     */
    private static final long OPEN_NIGHT_MS = 90 * 60_000L;
    /** One bucket per table, in report order. */
    private static final String SLEEP_LABEL = "睡眠";
    /** Report bucket for rows whose {@code timeIn0Tz} had the wrong sign. */
    private static final String TZ_LABEL = "时区修正";
    /** Every table whose {@code timeIn0Tz} the bridge writes. */
    private static final List<String> OFFSET_TABLES = List.of(MiHealthVitalsRecord.STEP_TABLE,
            MiHealthVitalsRecord.CALORIE_TABLE, MiHealthVitalsRecord.HR_TABLE,
            MiHealthVitalsRecord.SPO2_TABLE, MiHealthVitalsRecord.STRESS_TABLE,
            MiHealthSleepRecord.TABLE);

    private MiHealthSync() {}

    /** Outcome of one run. Never carries an exception: the UI prints this as-is. */
    public static final class Report {
        /** OK, NOTHING_TO_DO, NO_RECORDS, ACCOUNT_REQUIRED, or a {@link MiHealthStore.Failure} code. */
        public final String code;
        public final int inserted;
        public final int present;
        public final String detail;
        /** Table (or {@link #SLEEP_LABEL}) to rows written in this run. Empty when nothing moved. */
        public final Map<String, Integer> byTable;

        Report(String code, int inserted, int present, String detail,
                Map<String, Integer> byTable) {
            this.code = code;
            this.inserted = inserted;
            this.present = present;
            this.detail = detail;
            // Not Map.copyOf: that drops iteration order, and the report prints tables in the
            // order they were written (sleep first, then steps, calories, heart rate, ...).
            this.byTable = byTable == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byTable));
        }

        Report(String code) {
            this(code, 0, 0, "", Map.of());
        }

        public boolean ok() {
            return "OK".equals(code) || "NOTHING_TO_DO".equals(code);
        }

        /** One line for the settings card. */
        public String summary() {
            return switch (code) {
                case "OK" -> "已补写 " + moved() + "。";
                case "NOTHING_TO_DO" -> "小米健康已是最新，这次无需补写。";
                case "NO_RECORDS" -> "桥接本地暂无健康记录，先同步一次手环。";
                case "ACCOUNT_REQUIRED" -> "先导入绑定，桥接确认账号后才能读取本地记录。";
                case "MIHEALTH_ACCOUNT_DIR_MISSING" -> "打开一次小米健康，让桥接读到它的账号数据库。";
                case "MIHEALTH_HOST_BUSY" -> "小米健康还在收尾，稍后再试。";
                case "ROOT_ACTION_TIMEOUT" -> "Root 操作超时，稍后再试。";
                case "DEVICE_MISMATCH" -> "先在小米健康里连接过一次这只手环，两边就能对上。";
                default -> "这次同步停在这里（" + code + "）。";
            };
        }

        /** What actually moved, phrased for a settings card. */
        public String moved() {
            if (byTable.isEmpty()) return "0 条";
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : byTable.entrySet()) {
                if (entry.getValue() <= 0) continue;
                if (SLEEP_LABEL.equals(entry.getKey())) {
                    parts.add("睡眠 " + entry.getValue() + " 晚");
                } else {
                    parts.add(label(entry.getKey()) + " " + entry.getValue() + " 条");
                }
            }
            if (parts.isEmpty()) return "0 条";
            return String.join("、", parts) + "（共 " + inserted + " 行）";
        }
    }

    /** Table name to what the user calls it. */
    public static String label(String table) {
        return switch (table) {
            case SLEEP_LABEL -> SLEEP_LABEL;
            case MiHealthVitalsRecord.STEP_TABLE -> "步数";
            case MiHealthVitalsRecord.CALORIE_TABLE -> "卡路里";
            case MiHealthVitalsRecord.HR_TABLE -> "心率";
            case MiHealthVitalsRecord.SPO2_TABLE -> "血氧";
            case MiHealthVitalsRecord.STRESS_TABLE -> "压力";
            default -> table;
        };
    }

    /**
     * Blocking. Call from a worker thread.
     *
     * @param daysBack how many days of bridge history to consider, counting back from today.
     */
    public static Report run(Context context, int daysBack) {
        return run(context, daysBack, MiHealthJournal.MANUAL);
    }

    public static Report run(Context context, int daysBack, String trigger) {
        long started = System.currentTimeMillis();
        Report report = attempt(context, daysBack);
        long elapsed = System.currentTimeMillis() - started;
        remember(context, report);
        MiHealthJournal.append(context, new MiHealthJournal.Entry(System.currentTimeMillis(),
                trigger, report.code, report.inserted, report.present, elapsed,
                report.byTable.isEmpty() ? "" : report.moved()));
        SessionLog.line(context, "MIHEALTH_SYNC trigger=" + trigger + " code=" + report.code
                + " inserted=" + report.inserted + " present=" + report.present
                + " ms=" + elapsed + " tables=" + report.byTable);
        return report;
    }

    /** Last finished run, for the settings card. Empty text means never run. */
    public static String lastText(Context context) {
        return prefs(context).getString(KEY_LAST_TEXT, "");
    }

    public static long lastAtMs(Context context) {
        return prefs(context).getLong(KEY_LAST_AT, 0);
    }

    /** Automatic sync after the bridge finishes a history round. On unless the user turns it off. */
    public static boolean autoEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTO, true);
    }

    public static void setAutoEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO, enabled).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void remember(Context context, Report report) {
        prefs(context).edit()
                .putString(KEY_LAST_TEXT, report.summary())
                .putLong(KEY_LAST_AT, System.currentTimeMillis())
                .apply();
    }

    private static Report attempt(Context context, int daysBack) {
        File dir;
        String accountDir;
        String sid;
        String deviceId;
        try {
            dir = MiHealthStore.workDir(context);
            accountDir = MiHealthStore.accountDir(context);
            deviceId = bridgeDeviceId(context);
            sid = miHealthSid(context);
        } catch (MiHealthStore.Failure failure) {
            return new Report(failure.code);
        } catch (RuntimeException failure) {
            return new Report("BRIDGE_STATE_UNAVAILABLE");
        }

        ZoneId zone = ZoneId.systemDefault();
        long now = System.currentTimeMillis();
        long from = now - (daysBack + 1L) * 86_400_000L;

        HealthRecordStore store = new HealthRecordStore(context);
        try {
            return sync(context, store, dir, accountDir, zone, sid, deviceId, from, now);
        } finally {
            store.close();
        }
    }

    private static Report sync(Context context, HealthRecordStore store, File dir,
            String accountDir, ZoneId zone, String sid, String deviceId, long from, long to) {
        List<OHealthSleepPlan.Night> nights;
        List<MiHealthVitalsRecord.Point> points;
        try {
            nights = nights(store, deviceId, from, to + 3_600_000L);
            points = MiHealthVitalsRecord.points(vitals(store, deviceId, from, to));
        } catch (IllegalStateException accountMissing) {
            return new Report("ACCOUNT_REQUIRED");
        } catch (RuntimeException unreadable) {
            return new Report("LOCAL_HISTORY_UNAVAILABLE");
        }
        if (nights.isEmpty() && points.isEmpty()) return new Report("NO_RECORDS");

        // Peek first: data that is already there costs one file copy and nothing else.
        Map<Integer, Long> sleepDays;
        Map<String, Set<Long>> minutes;
        try {
            MiHealthStore.copyOut(context, dir, accountDir, false);
            sleepDays = existingSleepDays(dir, sid, zone);
            minutes = existingMinutes(dir, sid, from / 1000, to / 1000 + 60);
        } catch (MiHealthStore.Failure failure) {
            return new Report(failure.code);
        }

        List<OHealthSleepPlan.Night> missingNights = new ArrayList<>();
        for (OHealthSleepPlan.Night night : nights) {
            // A night the band has not closed yet. Its last minute row is the sync that just ran, so
            // the night is still growing, and writing it now would put a half-night in Mi Health.
            if (to - night.wakeMs() < OPEN_NIGHT_MS) continue;
            // Dedupe by sleep-day, not by wake time: the band rewrites the open sleep file on every
            // sync with a longer tail, so the same night arrives with a later wake time each time
            // and a wake-time key would add the night again on every run. A stored row that ends
            // earlier than this night is that same night caught mid-growth, so it is rewritten.
            Long earliest = sleepDays.get(OHealthSleepPlan.sleepDate(night.wakeMs(), zone));
            if (earliest == null || earliest < night.wakeMs() / 1000) {
                missingNights.add(night);
            }
        }
        Map<String, List<MiHealthVitalsRecord.Point>> missingPoints = new LinkedHashMap<>();
        for (MiHealthVitalsRecord.Point point : points) {
            Set<Long> present = minutes.get(point.table());
            if (present != null && present.contains(point.timeSeconds())) continue;
            missingPoints.computeIfAbsent(point.table(), ignored -> new ArrayList<>()).add(point);
        }
        if (missingNights.isEmpty() && missingPoints.isEmpty() && repaired(context)) {
            return new Report("NOTHING_TO_DO", 0, nights.size() + points.size(), "", Map.of());
        }

        Map<String, Integer> byTable = new LinkedHashMap<>();
        try {
            // A write needs a consistent copy: the host has to be gone before the file is taken.
            MiHealthStore.copyOut(context, dir, accountDir, true);
            File database = new File(dir, MiHealthStore.DB);
            boolean repair = !repaired(context);
            int written = write(store, database, zone, sid, deviceId, missingNights, missingPoints,
                    byTable, repair, from / 1000, to / 1000 + 60);
            if (repair) prefs(context).edit().putBoolean(KEY_TZ_REPAIRED, true).apply();
            if (written == 0) {
                MiHealthStore.startHost(context);
                return new Report("NOTHING_TO_DO", 0, nights.size() + points.size(), "", Map.of());
            }
            // Install before the host is allowed back up: it opens the file on start.
            MiHealthStore.copyBack(context, dir, accountDir);
            MiHealthStore.startHost(context);
            return new Report("OK", written, nights.size() + points.size() - written, "", byTable);
        } catch (MiHealthStore.Failure failure) {
            MiHealthStore.startHost(context);
            return new Report(failure.code);
        } catch (RuntimeException failure) {
            MiHealthStore.startHost(context);
            return new Report("MIHEALTH_WRITE_FAILED");
        } finally {
            deleteFiles(dir);
        }
    }

    private static List<OHealthSleepPlan.Night> nights(HealthRecordStore store, String deviceId,
            long from, long to) {
        List<HealthRecord> records = new ArrayList<>();
        for (String kind : new String[] {"sleep_interval", "sleep_stage"}) {
            records.addAll(records(store, deviceId, kind, from, to));
        }
        if (records.isEmpty()) return List.of();
        return OHealthSleepPlan.nights(records, ZoneId.systemDefault());
    }

    /**
     * Per-minute data for every table this sync fills, read one day at a time.
     *
     * <p>{@code storedRecords} pages internally but stops at 4000 rows per call. A week of step
     * intervals alone is past that, and the truncation would be silent — the missing days would
     * just never appear. Reading a day at a time keeps every call far below the cap.
     */
    private static List<HealthRecord> vitals(HealthRecordStore store, String deviceId, long from,
            long to) {
        List<HealthRecord> all = new ArrayList<>();
        long day = 86_400_000L;
        for (long start = from; start < to; start += day) {
            long end = Math.min(start + day, to);
            for (String kind : new String[] {"steps_interval", "heart_rate", "spo2", "stress"}) {
                all.addAll(records(store, deviceId, kind, start, end));
            }
        }
        return all;
    }

    /** {@code storedRecords} reports its payload damage as a checked exception; the UI needs a code. */
    private static List<HealthRecord> records(HealthRecordStore store, String deviceId, String kind,
            long from, long to) {
        try {
            return store.storedRecords(deviceId, kind, from, to);
        } catch (org.json.JSONException damaged) {
            throw new IllegalStateException("LOCAL_HISTORY_DAMAGED", damaged);
        }
    }

    /**
     * Writes the missing rows into the private copy and bumps each touched table version.
     * Returns how many rows were actually written; a row whose key appears between the peek and
     * the write is skipped rather than overwritten.
     */
    private static int write(HealthRecordStore store, File database, ZoneId zone, String sid,
            String deviceId, List<OHealthSleepPlan.Night> nights,
            Map<String, List<MiHealthVitalsRecord.Point>> points, Map<String, Integer> byTable,
            boolean repairOffsets, long fromSeconds, long toSeconds) {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(database.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
            db.rawQuery("PRAGMA busy_timeout=2000", null).close();
            int written = 0;
            int offset = zone.getRules().getOffset(Instant.ofEpochMilli(System.currentTimeMillis()))
                    .getTotalSeconds();
            db.beginTransaction();
            try {
                if (repairOffsets) {
                    // Repair before inserting so the rows written below are the only fresh ones.
                    int fixed = repairUtcOffsets(db, sid, fromSeconds, toSeconds);
                    if (fixed > 0) {
                        byTable.put(TZ_LABEL, fixed);
                        written += fixed;
                    }
                }
                int sleepRows = 0;
                for (OHealthSleepPlan.Night night : nights) {
                    List<HealthRecord> nightVitals = vitals(store, deviceId, night);
                    MiHealthSleepRecord.Row row = MiHealthSleepRecord.row(night, nightVitals, sid, zone);
                    long wakeSeconds = row.timeSeconds();
                    // The primary key carries the wake time, so a night that grew is a different
                    // row. Drop the shorter rows this bridge wrote for the same sleep-day first:
                    // a night the band was still recording when it got written leaves one behind,
                    // and it has to go (2026-11-26: Mi Health held 84 of 529 minutes because the
                    // first write after sleep started was treated as final).
                    int stale = db.delete("sleep_segment",
                            "key=? AND sid=? AND time>=? AND time<? AND time<>?",
                            new String[] {row.key(), sid, Long.toString(night.dayStartMs() / 1000),
                                    Long.toString(night.dayEndMs() / 1000),
                                    Long.toString(wakeSeconds)});
                    if (stale > 0) {
                        // Counted so a run that only replaced a night still installs its copy.
                        written++;
                        sleepRows++;
                    }
                    if (exists(db, MiHealthSleepRecord.TABLE, row.key(), sid, wakeSeconds)) {
                        continue;
                    }
                    db.execSQL("INSERT OR REPLACE INTO sleep_segment"
                                    + " (key, sid, time, isComplete, value, endTime, zoneOffsetInSec,"
                                    + " zoneName, timeIn0Tz, endTimeIn0Tz, isUpload, isDeleted)"
                                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                            new Object[] {row.key(), row.sid(), row.timeSeconds(),
                                    row.complete() ? 1 : 0, row.value(), row.endTimeSeconds(),
                                    row.zoneOffsetSeconds(), row.zoneName(),
                                    row.timeSeconds() + row.zoneOffsetSeconds(),
                                    row.endTimeSeconds() + row.zoneOffsetSeconds(), 0, 0});
                    written++;
                    sleepRows++;
                }
                if (sleepRows > 0) {
                    byTable.put(SLEEP_LABEL, sleepRows);
                    bumpVersion(db, MiHealthSleepRecord.TABLE);
                }
                // Iterate the declared table order, not the map: the map is filled in whatever order
                // the records were read, so the card's "步数 / 卡路里 / 心率..." order would shuffle
                // between runs and look like the numbers were swapped.
                for (String table : MiHealthVitalsRecord.TABLES) {
                    List<MiHealthVitalsRecord.Point> rowsInTable = points.get(table);
                    if (rowsInTable == null) continue;
                    int rows = 0;
                    for (MiHealthVitalsRecord.Point point : rowsInTable) {
                        if (exists(db, table, point.key(), sid, point.timeSeconds())) continue;
                        long end = point.timeSeconds() + 60;
                        // timeIn0Tz is time + offset, not time - offset: every row Mi Health writes
                        // for itself adds the zone offset (see repairUtcOffsets for the measurement
                        // that pinned this, and for what the opposite sign did to its day buckets).
                        db.execSQL("INSERT OR REPLACE INTO " + table
                                        + " (key, sid, time, value, endTime, zoneOffsetInSec,"
                                        + " zoneName, timeIn0Tz, endTimeIn0Tz, isUpload, isDeleted)"
                                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                                new Object[] {point.key(), sid, point.timeSeconds(), point.value(),
                                        end, offset, zone.getId(),
                                        point.timeSeconds() + offset, end + offset, 0, 0});
                        written++;
                        rows++;
                    }
                    if (rows > 0) {
                        byTable.put(table, rows);
                        bumpVersion(db, table);
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).close();
            return written;
        } finally {
            if (db != null) db.close();
        }
    }

    private static List<HealthRecord> vitals(HealthRecordStore store, String deviceId,
            OHealthSleepPlan.Night night) {
        List<HealthRecord> found = new ArrayList<>();
        for (String kind : new String[] {"heart_rate", "spo2", "sleep_breath"}) {
            found.addAll(records(store, deviceId, kind, night.fallAsleepMs() - NIGHT_PAD_MS,
                    night.wakeMs() + NIGHT_PAD_MS));
        }
        return found;
    }

    /**
     * A row Mi Health (or the user) soft-deleted still occupies the primary key
     * {@code (key, sid, time)} but must not count as present: the user asked for that data to be
     * gone, and deleting it is exactly the case this sync exists to repair. The insert is
     * {@code OR REPLACE}, so writing over the tombstone revives it with {@code isDeleted=0}.
     */
    private static boolean exists(SQLiteDatabase db, String table, String key, String sid,
            long timeSeconds) {
        String sql = "SELECT 1 FROM " + table + " WHERE key=? AND sid=? AND time=? AND isDeleted=0";
        try (Cursor cursor = db.rawQuery(sql,
                new String[] {key, sid, Long.toString(timeSeconds)})) {
            return cursor.moveToFirst();
        }
    }

    /** Mi Health's Room database carries a per-table version; readers compare it before trusting a table. */
    private static void bumpVersion(SQLiteDatabase db, String table) {
        db.execSQL("UPDATE _version_table_ SET version=version+1 WHERE table_name=?",
                new Object[] {table});
    }

    private static Map<Integer, Long> existingSleepDays(File dir, String sid, ZoneId zone) {
        Map<Integer, Long> days = new HashMap<>();
        SQLiteDatabase db = null;
        try {
            // Read-write on a private copy: a WAL database cannot be opened read-only without its
            // shared-memory index, and the live one belongs to Mi Health. Opening read-write lets
            // SQLite rebuild that index and apply the copied WAL, which is the state we want to read.
            db = SQLiteDatabase.openDatabase(new File(dir, MiHealthStore.DB).getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
            db.rawQuery("PRAGMA busy_timeout=2000", null).close();
            try (Cursor cursor = db.rawQuery(
                    "SELECT time FROM sleep_segment WHERE key='sleep' AND sid=? AND isDeleted=0",
                    new String[] {sid})) {
                while (cursor.moveToNext()) {
                    long wakeMs = cursor.getLong(0) * 1000;
                    // The earliest row for each sleep-day: anything earlier than the night we are
                    // about to write is a partial night that has to be replaced.
                    days.merge(OHealthSleepPlan.sleepDate(wakeMs, zone), cursor.getLong(0), Math::min);
                }
            }
        } catch (RuntimeException unreadable) {
            // A failed peek only costs a write attempt; the per-row existence check covers it.
        } finally {
            if (db != null) db.close();
        }
        return days;
    }

    /**
     * Minutes already present in each detail table, over the window being considered.
     *
     * <p>Reading the whole window once beats one {@code SELECT} per candidate row: a busy day holds
     * 1440 step minutes, and the per-row form would issue thousands of queries inside the write
     * transaction while Mi Health is down.
     */
    private static Map<String, Set<Long>> existingMinutes(File dir, String sid, long fromSeconds,
            long toSeconds) {
        Map<String, Set<Long>> minutes = new HashMap<>();
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(new File(dir, MiHealthStore.DB).getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
            db.rawQuery("PRAGMA busy_timeout=2000", null).close();
            for (String table : MiHealthVitalsRecord.TABLES) {
                Set<Long> times = new HashSet<>();
                try (Cursor cursor = db.rawQuery(
                        "SELECT time FROM " + table + " WHERE key=? AND sid=? AND isDeleted=0"
                                + " AND time>=? AND time<=?",
                        new String[] {MiHealthVitalsRecord.key(table), sid,
                                Long.toString(fromSeconds), Long.toString(toSeconds)})) {
                    while (cursor.moveToNext()) times.add(cursor.getLong(0));
                }
                minutes.put(table, times);
            }
        } catch (RuntimeException unreadable) {
            // An unreadable peek only costs a write attempt; the per-row check covers it.
        } finally {
            if (db != null) db.close();
        }
        return minutes;
    }

    private static String bridgeDeviceId(Context context) {
        try {
            JSONObject binding = new BindingStore(context).read();
            if (binding == null) throw new IllegalStateException("BINDING_MISSING");
            return BandStateRepository.deviceId(binding);
        } catch (IllegalStateException missing) {
            throw missing;
        } catch (Exception failure) {
            throw new IllegalStateException("BINDING_UNREADABLE");
        }
    }

    /** The band's {@code did} inside Mi Health, matched by the MAC the bridge is bound to. */
    private static String miHealthSid(Context context) throws MiHealthStore.Failure {
        String mac = "";
        String model = "";
        try {
            BindingStore.Identity identity = new BindingStore(context).readIdentity();
            mac = identity.address() == null ? "" : identity.address();
            model = identity.model() == null ? "" : identity.model();
        } catch (Exception ignored) {
            // Without a binding there is nothing to sync; the caller reports the account error.
        }
        MiFitnessDatabase.Snapshot snapshot;
        try {
            snapshot = MiFitnessDatabase.read(context);
        } catch (MiFitnessDatabase.Failure failure) {
            throw new MiHealthStore.Failure(failure.code);
        }
        List<MiFitnessDeviceRows.Row> rows = MiFitnessDeviceRows.select(snapshot.details, mac);
        for (MiFitnessDeviceRows.Row row : rows) {
            if (row.did != null && row.did.matches("[0-9]{5,}")) return row.did;
        }
        for (MiFitnessDeviceRows.Detail detail : snapshot.details) {
            if (detail.did == null || !detail.did.matches("[0-9]{5,}")) continue;
            if (!model.isEmpty() && model.equals(detail.model)) return detail.did;
        }
        throw new MiHealthStore.Failure("DEVICE_MISMATCH");
    }

    /**
     * Rewrites the UTC-0 columns to the convention Mi Health uses for the rows it writes itself.
     *
     * <p>Native rows store {@code timeIn0Tz = time + zoneOffsetInSec} — verified on a
     * {@code single_heart_rate} row Mi Health wrote on 2026-11-21 and on every native
     * {@code sleep_segment} row. This bridge wrote {@code time - zoneOffsetInSec} until
     * 2026-11-26. Mi Health decides which day a detail row belongs to from that column, so the
     * wrong sign slid its day boundary eight hours late: everything before 16:00 local counted
     * toward the previous day, and the home page only ever showed the afternoon. Measured
     * 2026-11-26 19:55: the page read 2388 steps while {@code step_record} held 6552, and the 2388
     * was exactly the rows from 16:00 onward.
     *
     * <p>Rows Mi Health wrote carry {@code +offset} and never match the WHERE clause, which is
     * also what makes the repair idempotent: after one pass there is nothing left to fix.
     */
    private static int repairUtcOffsets(SQLiteDatabase db, String sid, long fromSeconds,
            long toSeconds) {
        String where = "sid=? AND zoneOffsetInSec>0 AND timeIn0Tz = time - zoneOffsetInSec";
        String[] args = {sid};
        String update = "UPDATE %s SET timeIn0Tz = time + zoneOffsetInSec,"
                + " endTimeIn0Tz = endTime + zoneOffsetInSec WHERE " + where;
        int touched = 0;
        for (String table : OFFSET_TABLES) {
            long found = 0;
            try (Cursor cursor = db.rawQuery("SELECT count(*) FROM " + table + " WHERE " + where,
                    args)) {
                if (cursor.moveToFirst()) found = cursor.getLong(0);
            }
            if (found > 0) {
                db.execSQL(String.format(java.util.Locale.ROOT, update, table), args);
                touched += (int) found;
            }
            // Mi Health's update trigger only fires when isDeleted changes, so a row whose day moved
            // tells it nothing. Push the days this sync covers through its own work queue instead —
            // that queue is exactly what the insert trigger fills, so this is its normal input.
            long queued = 0;
            try (Cursor cursor = db.rawQuery("SELECT count(*) FROM " + table
                    + " WHERE sid=? AND time>=? AND time<?",
                    new String[] {sid, Long.toString(fromSeconds), Long.toString(toSeconds)})) {
                if (cursor.moveToFirst()) queued = cursor.getLong(0);
            }
            if (queued > 0) {
                db.execSQL("INSERT INTO _draft_" + table
                                + " (key,sid,time,timeIn0Tz,isDeleted)"
                                + " SELECT key,sid,time,timeIn0Tz,isDeleted FROM " + table
                                + " WHERE sid=? AND time>=? AND time<?",
                        new Object[] {sid, fromSeconds, toSeconds});
                touched += (int) queued;
            }
        }
        return touched;
    }

    /** True once the one-shot {@code timeIn0Tz} repair has completed. */
    private static boolean repaired(Context context) {
        return prefs(context).getBoolean(KEY_TZ_REPAIRED, false);
    }

    private static void deleteFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile()) file.delete();
        }
    }
}
