// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.ui;

import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.R;
import com.google.android.material.color.MaterialColors;

/**
 * A grid of bare numbers inside one card: a small label, the value with its unit, and a quiet line
 * underneath for when that value was measured.
 *
 * <p>No per-cell surface. Four sheets of glass in a 2×2 would repeat the 24dp radius, the lit edge
 * and the glow four times inside one card and read as four buttons; the cells are separated by a
 * hairline instead, so the card stays one object and the numbers stay the subject. This project has
 * no elevation anywhere (a shadow shows through a translucent fill), so every extra line costs.
 *
 * <p>The value and its unit share one {@link TextView} with a size span. Two views side by side
 * would need baseline alignment done by hand, and would break the line in the wrong place when the
 * value grows; a span keeps them on one line and lets the cell wrap as a paragraph.
 */
public final class MetricGrid {
    /** One size for every value. Autosizing would leave the four cells at four different sizes. */
    private static final int VALUE_SP = 28;
    private static final int UNIT_SP = 14;
    private static final int LABEL_SP = 12;
    private static final int NOTE_SP = 11;
    private static final int PAD_DP = 12;
    private static final int CELL_MIN_DP = 96;
    /** A value must fit the cell minus its padding; measured, not guessed, because it depends on the font. */
    private static final int VALUE_LIMIT_DP = 139;
    /**
     * The same limit for a one-column row. This grid stacks the label above the value in both modes,
     * so a one-column cell has the full 296dp of content width; 288 leaves the same 8dp of headroom.
     * (A side-by-side one-column layout would have to reserve the label column and end up narrower
     * than the two-column cell it replaced, which is the one thing a "more readable" mode must not do.)
     */
    private static final int ONE_COLUMN_LIMIT_DP = 288;
    private static final int RULE_ALPHA = 0x24;
    /** Reserved even when empty: a cell that grows when it has something to say breaks the grid. */
    private static final int NOTE_MIN_DP = 15;
    /**
     * Whether the narrowest form of the longest value still fits a two-column cell.
     *
     * <p>Measured, never read off {@code fontScale}: the question is "does this string fit this
     * width", and a threshold on the scale only guesses at the answer. ROM scale steps are discrete
     * (1.0 / 1.15 / 1.3 / 1.5 / 1.8), and the people who install this app set it with
     * {@code settings put system font_scale 1.7} — so a guessed breakpoint is a breakpoint that
     * leaves a window where the string no longer fits but the layout has not changed yet.
     *
     * <p>Callers pass the widest string that can actually appear, so the answer does not depend on
     * today's data.
     */
    public static boolean fitsTwoColumns(AppCompatActivity activity, String[] narrowest, String unit) {
        Paint ruler = new Paint(Paint.ANTI_ALIAS_FLAG);
        ruler.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ruler.setTextSize(activity.getResources().getDisplayMetrics().scaledDensity * VALUE_SP);
        float limit = BridgeScreen.dp(activity, VALUE_LIMIT_DP);
        float unitWidth = unit == null || unit.isBlank() ? 0 : ruler.measureText(" " + unit);
        // The widest of the four is what decides: one cell that cannot hold its own shortest form
        // is enough to turn the whole grid into a list.
        float widest = 0;
        for (String candidate : narrowest) {
            if (candidate == null) continue;
            widest = Math.max(widest, ruler.measureText(candidate) + unitWidth);
        }
        return widest <= limit;
    }

    private final AppCompatActivity activity;
    private final TextView[] labels;
    private final TextView[] values;
    private final TextView[] notes;
    private final Paint ruler;
    private final int columns;
    private final String[] spoken;

    private MetricGrid(AppCompatActivity activity, TextView[] labels, TextView[] values,
            TextView[] notes, int columns) {
        this.activity = activity;
        this.labels = labels;
        this.values = values;
        this.notes = notes;
        this.columns = columns;
        this.spoken = new String[labels.length];
        this.ruler = new Paint(Paint.ANTI_ALIAS_FLAG);
        ruler.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ruler.setTextSize(sp(VALUE_SP));
    }

    /**
     * The full name of a cell for TalkBack, when the visible label has been shortened to fit a
     * column. One column collapses "今日步数" to "步数" so the label stops winning over the value;
     * the spoken name stays complete, because a listener has no column width to run out of.
     */
    public void speak(int index, String full) {
        spoken[index] = full;
    }

    public static MetricGrid attach(AppCompatActivity activity, LinearLayout card, int columns, int cells) {
        int perRow = Math.max(1, columns);
        TextView[] labels = new TextView[cells];
        TextView[] values = new TextView[cells];
        TextView[] notes = new TextView[cells];
        LinearLayout grid = new LinearLayout(activity);
        grid.setOrientation(LinearLayout.VERTICAL);
        card.addView(grid, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = null;
        for (int index = 0; index < cells; index++) {
            if (index % perRow == 0) {
                if (index > 0) grid.addView(rule(activity), ruleParams(activity, grid));
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
            } else {
                row.addView(rule(activity), ruleParams(activity, null));
            }
            LinearLayout cell = new LinearLayout(activity);
            cell.setOrientation(LinearLayout.VERTICAL);
            int pad = BridgeScreen.dp(activity, PAD_DP);
            cell.setPadding(0, pad, 0, pad);
            cell.setMinimumHeight(BridgeScreen.dp(activity, CELL_MIN_DP));
            labels[index] = label(activity, cell);
            values[index] = value(activity, cell);
            notes[index] = note(activity, cell);
            // One node for TalkBack: otherwise it reads the label, the number, and the unit as three
            // separate stops and "昨夜睡眠 / 9 小时 25 分 / 小时 / 分" stops being a sentence.
            cell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
            // One column is a vertical list, so the numbers align on the right edge and can be
            // scanned down a single line. In two columns each cell is its own unit of meaning and
            // left alignment is the only sensible one.
            if (perRow == 1) values[index].setGravity(Gravity.END);
        }
        return new MetricGrid(activity, labels, values, notes, perRow);
    }

    /**
     * One cell. An empty {@code value} is this page's "no data" state: it prints an em dash in the
     * same size and colour as a real number, because a zero step count and an unread step count look
     * identical otherwise, and a greyed-out cell would read as "loading" rather than "absent".
     *
     * <p>{@code candidates} are tried in order and the first one that fits the cell is used. The
     * sleep duration needs this: "9 小时 25 分" fits at this font scale and "9h25m" is there for the
     * one where it does not. Shrinking the font instead would break the four-cell grid.
     */
    public void set(int index, String label, String[] candidates, String unit, String note) {
        String chosen = fit(candidates, unit);
        String clean = chosen == null || chosen.isBlank() ? "—" : chosen;
        boolean withUnit = unit != null && !unit.isBlank();
        String text = withUnit ? clean + " " + unit : clean;
        SpannableString span = new SpannableString(text);
        if (withUnit) {
            // The separating space belongs to the unit run, not the value run. Left at 28sp it is
            // 7.3dp of air and the unit reads as detached ("721    步"); at 14sp it is the 4dp the
            // layout asks for and the two read as one token.
            int start = clean.length();
            span.setSpan(new AbsoluteSizeSpan(UNIT_SP, true), start, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new ForegroundColorSpan(color(activity, R.attr.colorOnSurfaceVariant)),
                    start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        labels[index].setText(label == null ? "" : label);
        values[index].setText(span);
        notes[index].setText(note == null ? "" : note);
        String spokenLabel = spoken[index] == null ? label : spoken[index];
        ((View) values[index].getParent()).setContentDescription(
                (spokenLabel == null ? "" : spokenLabel) + " " + text
                        + (note == null || note.isEmpty() ? "" : "，" + note));
    }

    /**
     * Colours the note as a warning. Only "this number is no longer current" earns it — a note that
     * is merely a timestamp stays muted, or four tiles would end up shouting at once.
     */
    public void warnNote(int index, boolean warning) {
        boolean night = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        notes[index].setTextColor(warning
                ? (night ? 0xFFFCD34D : 0xFFB45309)
                : color(activity, R.attr.colorOnSurfaceVariant));
    }

    private String fit(String[] candidates, String unit) {
        // A wider cell gets the longer form back: the string is chosen against the width the cell
        // actually has in the current mode, so the one-column layout shows "9时25分" again, and only
        // the two-column one at a large font scale has to fall back.
        float limit = BridgeScreen.dp(activity, columns == 1 ? ONE_COLUMN_LIMIT_DP : VALUE_LIMIT_DP);
        float unitWidth = unit == null || unit.isBlank() ? 0 : ruler.measureText(" " + unit) * 0.62f;
        String last = null;
        for (String candidate : candidates) {
            if (candidate == null) continue;
            last = candidate;
            if (ruler.measureText(candidate) + unitWidth <= limit) return candidate;
        }
        return last;
    }

    /**
     * The label sits above the value, not below it. Under the number it would be pushed down by
     * whatever the value does — a longer number would move the label, and the grid would twitch on
     * every refresh. Above, the label row is fixed and only the digit line changes.
     */
    private static TextView label(AppCompatActivity activity, LinearLayout parent) {
        TextView text = new TextView(activity);
        text.setTextSize(LABEL_SP);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setLetterSpacing(0.04f);
        text.setTextColor(color(activity, R.attr.colorOnSurfaceVariant));
        text.setIncludeFontPadding(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = BridgeScreen.dp(activity, 4);
        parent.addView(text, params);
        return text;
    }

    private static TextView value(AppCompatActivity activity, LinearLayout parent) {
        TextView text = new TextView(activity);
        text.setTextSize(VALUE_SP);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextColor(color(activity, R.attr.colorOnSurface));
        text.setIncludeFontPadding(false);
        // Not single-line: at a very large font scale "1.2万 步" is a single token that cannot be
        // broken, and clipping it is worse than letting the unit drop to a second line.
        text.setMaxLines(2);
        parent.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return text;
    }

    private static TextView note(AppCompatActivity activity, LinearLayout parent) {
        TextView text = new TextView(activity);
        text.setTextSize(NOTE_SP);
        text.setTextColor(color(activity, R.attr.colorOnSurfaceVariant));
        text.setIncludeFontPadding(false);
        text.setSingleLine(true);
        text.setMinHeight(BridgeScreen.dp(activity, NOTE_MIN_DP));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = BridgeScreen.dp(activity, 2);
        parent.addView(text, params);
        return text;
    }

    private static View rule(AppCompatActivity activity) {
        View line = new View(activity);
        line.setBackgroundColor(ruleColor(activity));
        return line;
    }

    /**
     * Horizontal rules span the cell area without meeting the card edge; the vertical one sits
     * inside its row with 12dp above and below, so the two never touch. A full-height vertical rule
     * would read as a table; this reads as a seam.
     */
    private static LinearLayout.LayoutParams ruleParams(AppCompatActivity activity, ViewGroup row) {
        int thickness = Math.max(1, BridgeScreen.dp(activity, 1));
        if (row != null) return new LinearLayout.LayoutParams(-1, thickness);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(thickness, -1);
        int gap = BridgeScreen.dp(activity, 16);
        params.leftMargin = gap;
        params.rightMargin = gap;
        params.topMargin = BridgeScreen.dp(activity, 12);
        params.bottomMargin = BridgeScreen.dp(activity, 12);
        return params;
    }

    /**
     * A 14% rule: brand green in light, surface in dark. {@code colorOutlineVariant} would be the
     * same weight as the rule between log rows, which makes four stats look like four list items.
     */
    private static int ruleColor(AppCompatActivity activity) {
        boolean night = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int base = night ? color(activity, R.attr.colorOnSurface)
                : color(activity, androidx.appcompat.R.attr.colorPrimary);
        return (base & 0x00FFFFFF) | (RULE_ALPHA << 24);
    }

    private static int color(AppCompatActivity activity, int attr) {
        return MaterialColors.getColor(activity, attr, 0);
    }

    private float sp(int value) {
        return activity.getResources().getDisplayMetrics().scaledDensity * value;
    }
}
