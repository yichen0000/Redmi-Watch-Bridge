// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.health;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Append-only record of every sync run, so the log screen can answer "did it run, when, and what
 * did it move" without asking the user to read logcat.
 *
 * <p>One JSON object per line, newest last. The file is capped at {@link #MAX_LINES} entries: a sync
 * every ten minutes for a month is about 4300 runs, and an unbounded file in the app's private
 * storage would be the kind of thing that only shows up as a slow-starting app a year later.
 *
 * <p>Writes are synchronised on the class because the manual button and the automatic trigger can
 * overlap; the journal is the only place that would lose entries if they did.
 */
public final class MiHealthJournal {
    /** Enough history to cover a long weekend of troubleshooting without becoming a disk liability. */
    private static final int MAX_LINES = 500;
    private static final String FILE = "mihealth-sync.jsonl";

    /** Manual means the settings button; auto means the post-sync trigger. */
    public static final String MANUAL = "manual";
    public static final String AUTO = "auto";

    private MiHealthJournal() {}

    /** One finished run. {@code at} is wall-clock millis; {@code ms} is how long it took. */
    public record Entry(long at, String trigger, String code, int inserted, int present, long ms,
            String detail) {
        public boolean ok() {
            return "OK".equals(code) || "NOTHING_TO_DO".equals(code);
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("at", at);
                json.put("trigger", trigger);
                json.put("code", code);
                json.put("inserted", inserted);
                json.put("present", present);
                json.put("ms", ms);
                json.put("detail", detail);
            } catch (JSONException impossible) {
                throw new IllegalStateException("MIHEALTH_JOURNAL_UNSERIALIZABLE", impossible);
            }
            return json;
        }
    }

    static void append(Context context, Entry entry) {
        synchronized (MiHealthJournal.class) {
            File file = file(context);
            // The file is stored oldest-first, so appending and then keeping the tail is what
            // preserves the newest runs. Reading newest-first here would keep the oldest ones.
            List<Entry> existing = readChronological(context);
            existing.add(entry);
            int from = Math.max(0, existing.size() - MAX_LINES);
            // Rewrite rather than append once the cap is reached: the file then never grows past
            // the cap and a single truncate keeps the newest entries.
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file, false),
                    StandardCharsets.UTF_8)) {
                for (int index = from; index < existing.size(); index++) {
                    writer.write(existing.get(index).toJson().toString());
                    writer.write('\n');
                }
            } catch (Exception unwritable) {
                // The journal is diagnostics; failing to record a run must never fail the run.
            }
        }
    }

    /** Newest first, which is the order the log screen shows. */
    public static List<Entry> read(Context context, int limit) {
        synchronized (MiHealthJournal.class) {
            List<Entry> chronological = readChronological(context);
            List<Entry> newestFirst = new ArrayList<>(chronological.size());
            for (int index = chronological.size() - 1; index >= 0; index--) {
                newestFirst.add(chronological.get(index));
                if (limit > 0 && newestFirst.size() >= limit) break;
            }
            return newestFirst;
        }
    }

    /** Oldest first, which is the order the file is written in. Capped at {@link #MAX_LINES}. */
    private static List<Entry> readChronological(Context context) {
        List<Entry> entries = new ArrayList<>();
        File file = file(context);
        if (!file.isFile()) return entries;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Entry entry = parse(line);
                if (entry != null) entries.add(entry);
            }
        } catch (Exception unreadable) {
            return entries;
        }
        if (entries.size() > MAX_LINES) {
            return new ArrayList<>(entries.subList(entries.size() - MAX_LINES, entries.size()));
        }
        return entries;
    }

    public static void clear(Context context) {
        synchronized (MiHealthJournal.class) {
            File file = file(context);
            if (file.isFile()) file.delete();
        }
    }

    private static Entry parse(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            JSONObject json = new JSONObject(line);
            return new Entry(json.getLong("at"), json.optString("trigger", AUTO),
                    json.optString("code", "UNKNOWN"), json.optInt("inserted", 0),
                    json.optInt("present", 0), json.optLong("ms", 0), json.optString("detail", ""));
        } catch (JSONException malformed) {
            return null;
        }
    }

    private static File file(Context context) {
        return new File(context.getNoBackupFilesDir(), FILE);
    }
}
