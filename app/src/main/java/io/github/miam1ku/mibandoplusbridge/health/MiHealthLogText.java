// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.health;

import android.content.Context;
import android.text.format.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Turns journal rows into the sentences the log page shows.
 *
 * <p>Kept out of the screen itself so the wording is testable and so the tab and any future
 * deep-link screen cannot drift apart — a log line that says "已补写" in one place and
 * "补写完成" in another makes the two views look like different features.
 */
public final class MiHealthLogText {
    private MiHealthLogText() {
    }

    /** One line above the list: how many runs, how many worked, whether the automatic path is on. */
    public static String summary(Context context, List<MiHealthJournal.Entry> entries) {
        if (entries.isEmpty()) {
            return "同步记录会出现在这里。在「健康」页点「立即同步到小米健康」跑一次就有第一条。";
        }
        int ok = 0;
        for (MiHealthJournal.Entry entry : entries) {
            if (entry.ok()) ok++;
        }
        return "共 " + entries.size() + " 条记录，其中成功 " + ok + " 条。自动同步"
                + (MiHealthSync.autoEnabled(context) ? "已开启" : "待开启") + "。";
    }

    /** Two lines per run: when and how, then what moved. */
    public static String describe(Context context, MiHealthJournal.Entry entry) {
        StringBuilder text = new StringBuilder();
        text.append(DateFormat.getTimeFormat(context).format(new Date(entry.at())))
                .append("  ")
                .append(MiHealthJournal.AUTO.equals(entry.trigger()) ? "自动" : "手动")
                .append("  ")
                .append(status(entry))
                .append("  ")
                .append(seconds(entry.ms()));
        if (!entry.detail().isEmpty()) {
            text.append('\n').append(entry.detail());
        }
        return text.toString();
    }

    public static String status(MiHealthJournal.Entry entry) {
        return switch (entry.code()) {
            case "OK" -> "已补写";
            case "NOTHING_TO_DO" -> "无需补写";
            case "NO_RECORDS" -> "桥接本地无记录";
            default -> "停在 " + entry.code();
        };
    }

    public static String seconds(long ms) {
        if (ms < 1_000) return ms + "ms";
        return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }
}
