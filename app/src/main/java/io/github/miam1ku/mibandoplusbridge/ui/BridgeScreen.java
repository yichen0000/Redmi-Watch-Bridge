// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.ui;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Shared Material 3 layout for every bridge screen.
 * Pages read top to bottom: status, next step, features, help, management, advanced.
 */
public final class BridgeScreen {
    private static final int ATTR_COLOR_PRIMARY = androidx.appcompat.R.attr.colorPrimary;
    private static final int ATTR_COLOR_ERROR = androidx.appcompat.R.attr.colorError;
    private static final int ATTR_BORDERLESS_BUTTON_STYLE =
            androidx.appcompat.R.attr.borderlessButtonStyle;

    /** Colour role of a chip, notice card, or status line. */
    public enum Tone { POSITIVE, ACTIVE, NEUTRAL, ATTENTION }

    /** Position of a setup step relative to the current one. */
    public enum StepState { DONE, CURRENT, LATER }

    public final LinearLayout body;
    private final AppCompatActivity activity;
    private final GlassTopBar topBar;

    private BridgeScreen(AppCompatActivity activity, LinearLayout body, GlassTopBar topBar) {
        this.activity = activity;
        this.body = body;
        this.topBar = topBar;
    }

    /**
     * Builds the standard page shell: gradient backdrop, a floating glass top bar, and a scrolling
     * body underneath it.
     *
     * <p>Content scrolls under the bar rather than stopping below it — the scroll view carries a top
     * padding equal to the bar plus its gap, with {@code clipToPadding(false)} — so the page slides
     * beneath a pane of glass instead of meeting the edge of a strip. That is the whole point of the
     * floating capsule; a full-width toolbar could not do it without also covering the status bar.
     */
    public static BridgeScreen attach(AppCompatActivity activity, String title, boolean back) {
        FrameLayout root = new FrameLayout(activity);
        root.setBackground(new GlassBackdrop(activity));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        int gutter = dp(activity, 16);
        body.setPadding(gutter, dp(activity, 4), gutter, dp(activity, 16));
        scroll.addView(body, new LinearLayout.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        View.OnClickListener onBack = back ? view -> activity.finish() : null;
        GlassTopBar bar = new GlassTopBar(activity, title, onBack);
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(-1, -2);
        barParams.gravity = Gravity.TOP;
        barParams.leftMargin = gutter;
        barParams.rightMargin = gutter;
        barParams.topMargin = dp(activity, GlassTopBar.TOP_GAP_DP);
        root.addView(bar, barParams);

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            int reserve = dp(activity, GlassTopBar.TOP_GAP_DP)
                    + dp(activity, GlassTopBar.HEIGHT_DP) + dp(activity, 8);
            scroll.setPadding(0, reserve, 0, dp(activity, 24));
            return insets;
        });
        activity.setContentView(root);
        return new BridgeScreen(activity, body, bar);
    }

    /** Binds the widget helpers to a body that someone else built — one page of a {@link BridgePager}. */
    public static BridgeScreen bind(AppCompatActivity activity, LinearLayout body) {
        return new BridgeScreen(activity, body, null);
    }

    /** The floating bar, so a page can retitle it or hang an action off it. */
    public GlassTopBar topBar() {
        return topBar;
    }

    // ---- text ----

    public TextView heading(String value) {
        return heading(body, value);
    }

    /** Section label above a group of cards. */
    public TextView heading(LinearLayout parent, String value) {
        TextView text = label(parent, value, 14, true);
        text.setTextColor(color(activity, ATTR_COLOR_PRIMARY));
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) text.getLayoutParams();
        params.topMargin = dp(activity, 12);
        params.leftMargin = dp(activity, 4);
        return text;
    }

    public TextView overline(LinearLayout parent, String value) {
        TextView text = label(parent, value, 12, true);
        text.setTextColor(color(activity, R.attr.colorOnSurfaceVariant));
        text.setLetterSpacing(0.04f);
        return text;
    }

    public TextView title(LinearLayout parent, String value) {
        return label(parent, value, 22, true);
    }

    public TextView bodyText(String value) {
        return bodyText(body, value);
    }

    public TextView bodyText(LinearLayout parent, String value) {
        return label(parent, value, 16, false);
    }

    public TextView caption(String value) {
        return caption(body, value);
    }

    public TextView caption(LinearLayout parent, String value) {
        TextView text = label(parent, value, 14, false);
        muted(text);
        return text;
    }

    public void muted(TextView text) {
        text.setTextColor(color(activity, R.attr.colorOnSurfaceVariant));
    }

    /** Colours a status line with the tone's foreground role. */
    public void setTone(TextView text, Tone tone) {
        text.setTextColor(foreground(tone));
    }

    // ---- containers ----

    public LinearLayout card() {
        return card(body);
    }

    public LinearLayout card(LinearLayout parent) {
        return glassCard(parent, new GlassPanel(activity, GlassPanel.RADIUS_CARD, GlassPanel.Kind.CARD));
    }

    /** Card with a small title. Returns the inner layout. */
    public LinearLayout section(String title) {
        LinearLayout inner = card();
        overline(inner, title);
        return inner;
    }

    /** Tinted card for something the user can act on now. */
    public LinearLayout noticeCard(Tone tone) {
        LinearLayout inner = glassCard(body,
                GlassPanel.tinted(activity, GlassPanel.RADIUS_CARD, background(tone)));
        inner.setTag(tone);
        return inner;
    }

    /**
     * Translucent strip for a one-line summary that belongs above the cards — the same pane of glass
     * as the bars, at a smaller radius. Used where a full card would give a sentence more weight
     * than the list underneath it.
     */
    public LinearLayout glassStrip(LinearLayout parent) {
        LinearLayout strip = new LinearLayout(activity);
        strip.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        strip.setPadding(pad, dp(activity, 14), pad, dp(activity, 6));
        strip.setBackground(new GlassPanel(activity, 18f, GlassPanel.Kind.CARD));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(activity, 12);
        parent.addView(strip, params);
        return strip;
    }

    /**
     * Hairline between list rows. A list of two-line entries reads as one paragraph without it —
     * the eye cannot tell where a run ends and the next begins.
     */
    public View hairline(LinearLayout parent) {
        View line = new View(activity);
        int thickness = Math.max(1, dp(activity, 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, thickness);
        int gap = dp(activity, 12);
        params.topMargin = gap;
        params.bottomMargin = gap;
        line.setBackgroundColor(color(activity, R.attr.colorOutlineVariant));
        parent.addView(line, params);
        return line;
    }

    /** Collapsible block. The returned layout starts hidden. */
    public LinearLayout expander(String title) {
        return expander(body, title, null);
    }

    public LinearLayout expander(LinearLayout parent, String title, Runnable onOpen) {
        MaterialButton toggle = new MaterialButton(activity, null, ATTR_BORDERLESS_BUTTON_STYLE);
        toggle.setAllCaps(false);
        toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggle.setMinHeight(dp(activity, 48));
        toggle.setBackground(rowRipple(14));
        toggle.setText("▸  " + title);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setVisibility(View.GONE);
        toggle.setOnClickListener(view -> {
            boolean open = content.getVisibility() != View.VISIBLE;
            content.setVisibility(open ? View.VISIBLE : View.GONE);
            toggle.setText((open ? "▾  " : "▸  ") + title);
            if (open && onOpen != null) onOpen.run();
        });
        parent.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        parent.addView(content, new LinearLayout.LayoutParams(-1, -2));
        return content;
    }

    // ---- small widgets ----

    /** Rounded status label. */
    public TextView chip(LinearLayout parent, String text, Tone tone) {
        TextView chip = new TextView(activity);
        chip.setTextSize(13);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        int h = dp(activity, 12);
        int v = dp(activity, 4);
        chip.setPadding(h, v, h, v);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.bottomMargin = dp(activity, 12);
        parent.addView(chip, params);
        setChip(chip, text, tone);
        return chip;
    }

    public void setChip(TextView chip, String text, Tone tone) {
        chip.setText(text);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(activity, 999));
        shape.setColor(background(tone));
        chip.setBackground(shape);
        chip.setTextColor(onBackground(tone));
    }

    /** One line of a setup checklist: ✓ done, ● current, ○ later. */
    public TextView stepRow(LinearLayout parent, int index, String title, StepState state) {
        TextView row = label(parent, "", 15, false);
        ((LinearLayout.LayoutParams) row.getLayoutParams()).bottomMargin = dp(activity, 6);
        setStep(row, index, title, state);
        return row;
    }

    public void setStep(TextView row, int index, String title, StepState state) {
        String mark = switch (state) {
            case DONE -> "✓";
            case CURRENT -> "●";
            case LATER -> "○";
        };
        row.setText(mark + "  " + index + ". " + title);
        row.setTypeface(state == StepState.CURRENT
                ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
        row.setTextColor(state == StepState.CURRENT ? color(activity, ATTR_COLOR_PRIMARY)
                : color(activity, R.attr.colorOnSurfaceVariant));
    }

    /** Switch with a caption under it. Returns the switch. */
    public MaterialSwitch switchRow(LinearLayout parent, String title, String caption) {
        MaterialSwitch toggle = new MaterialSwitch(activity);
        toggle.setText(title);
        toggle.setTextSize(16);
        toggle.setTextColor(color(activity, R.attr.colorOnSurface));
        toggle.setMinHeight(dp(activity, 48));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(activity, 4);
        parent.addView(toggle, params);
        if (caption != null && !caption.isEmpty()) caption(parent, caption);
        return toggle;
    }

    // ---- buttons ----

    public MaterialButton filled(String title, Runnable action) {
        return filled(body, title, action);
    }

    public MaterialButton outlined(String title, Runnable action) {
        return outlined(body, title, action);
    }

    public MaterialButton textButton(String title, Runnable action) {
        return textButton(body, title, action);
    }

    public MaterialButton filled(LinearLayout parent, String title, Runnable action) {
        return material(parent, title, R.attr.materialButtonStyle, action);
    }

    public MaterialButton outlined(LinearLayout parent, String title, Runnable action) {
        return material(parent, title, R.attr.materialButtonOutlinedStyle, action);
    }

    public MaterialButton textButton(LinearLayout parent, String title, Runnable action) {
        return material(parent, title, ATTR_BORDERLESS_BUTTON_STYLE, action);
    }

    /** Outlined button in the error role for removal. */
    public MaterialButton dangerOutlined(LinearLayout parent, String title, Runnable action) {
        MaterialButton button = outlined(parent, title, action);
        int error = color(activity, ATTR_COLOR_ERROR);
        button.setTextColor(error);
        button.setStrokeColor(ColorStateList.valueOf(error));
        return button;
    }

    public MaterialButton navRow(String title, Runnable action) {
        return navRow(body, title, action);
    }

    /** Touch feedback for a row that has no material surface of its own. */
    private Drawable rowRipple(int radiusDp) {
        TypedValue value = new TypedValue();
        int color = 0x22000000;
        if (activity.getTheme().resolveAttribute(android.R.attr.colorControlHighlight, value, true)) {
            color = value.resourceId != 0
                    ? ContextCompat.getColor(activity, value.resourceId) : value.data;
        }
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(activity, radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(color), null, mask);
    }

    /** Full-width list row with a trailing chevron. */
    public MaterialButton navRow(LinearLayout parent, String title, Runnable action) {
        MaterialButton button = new MaterialButton(activity, null, ATTR_BORDERLESS_BUTTON_STYLE);
        button.setText(title + "   ›");
        button.setAllCaps(false);
        button.setTextColor(color(activity, R.attr.colorOnSurface));
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinHeight(dp(activity, 52));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(dp(activity, 12));
        // The borderless style still paints a surface-coloured background, and tinting it transparent
        // does not take on MaterialButton. On the old opaque card that was invisible; on a
        // translucent one it shows up as a white rectangle inside the frost, which reads as a
        // rendering bug. Dropping the background outright is the only thing that reliably clears it;
        // the ripple below puts the touch feedback back.
        button.setBackground(rowRipple(14));
        button.setOnClickListener(view -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    /** The two lines of a {@link #linkRow}, so a page can rewrite both on refresh. */
    public static final class TwoLines {
        public final TextView title;
        public final TextView caption;

        private TwoLines(TextView title, TextView caption) {
            this.title = title;
            this.caption = caption;
        }
    }

    /**
     * A row with two lines that leads somewhere: the event, then where the other half of it stands.
     *
     * <p>One line cannot carry both without reading as a fragment — "手环拉取 11:22" alone says
     * nothing about the hand-off that follows it — and the second line keeps its own subject
     * ("小米健康补写") because the first one is about the band, and a bare "11:11 · 6 条" under it
     * would be read as six steps from the band.
     */
    public TwoLines linkRow(LinearLayout parent, String title, Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int vertical = dp(activity, 10);
        row.setPadding(dp(activity, 4), vertical, dp(activity, 4), vertical);
        row.setBackground(rowRipple(14));
        row.setClickable(true);
        row.setOnClickListener(view -> action.run());

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView first = label(texts, title, 16, true);
        ((LinearLayout.LayoutParams) first.getLayoutParams()).bottomMargin = dp(activity, 2);
        TextView second = label(texts, "", 13, false);
        muted(second);

        TextView chevron = label(row, "›", 20, false);
        chevron.setTextColor(color(activity, R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(-2, -2);
        chevronParams.leftMargin = dp(activity, 8);
        chevron.setLayoutParams(chevronParams);

        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return new TwoLines(first, second);
    }

    public void setLastChildMargin(LinearLayout parent, int space) {
        int count = parent.getChildCount();
        if (count == 0) return;
        View last = parent.getChildAt(count - 1);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) last.getLayoutParams();
        params.bottomMargin = dp(activity, space);
        last.setLayoutParams(params);
    }

    /**
     * A frosted card: translucent fill, lit edge, and a 3dp elevation so the shadow follows the same
     * rounded rect.
     *
     * <p>The returned layout is wrapped in a holder, and the glass lives on the holder. That extra
     * level is load-bearing: callers hide a notice card by hiding its <em>parent</em> — that is how
     * the 14dp gap under it collapses too — so if the content layout were a direct child of the page
     * body, hiding one notice would hide the entire page.
     */
    private LinearLayout glassCard(LinearLayout parent, Drawable background) {
        FrameLayout holder = new FrameLayout(activity);
        holder.setBackground(background);
        // No elevation. A drop shadow is drawn behind the view, and a card this translucent shows
        // it *through* the frost: the shadow's silhouette lands inside the card as a hard-edged
        // grey band hugging the rounded corner, which looks like a rendering bug. Depth comes from
        // the lit edge and the translucency instead. The bars keep their elevation — at 92% fill
        // there is nothing to see through.
        holder.setElevation(0);

        LinearLayout inner = new LinearLayout(activity);
        inner.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        inner.setPadding(pad, dp(activity, 18), pad, dp(activity, 10));
        holder.addView(inner, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(activity, 14);
        parent.addView(holder, params);
        return inner;
    }

    private MaterialButton material(LinearLayout parent, String title, int styleAttr, Runnable action) {
        MaterialButton button = new MaterialButton(activity, null, styleAttr);
        if (styleAttr == ATTR_BORDERLESS_BUTTON_STYLE) button.setBackground(null);
        button.setText(title);
        button.setAllCaps(false);
        button.setMinHeight(dp(activity, 48));
        button.setInsetTop(dp(activity, 4));
        button.setInsetBottom(dp(activity, 4));
        button.setCornerRadius(dp(activity, 24));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(activity, 8);
        parent.addView(button, params);
        return button;
    }

    private TextView label(LinearLayout parent, String value, int sp, boolean heading) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(sp);
        text.setLineSpacing(0, heading ? 1.2f : 1.4f);
        text.setTextColor(color(activity, R.attr.colorOnSurface));
        if (heading) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(activity, heading ? 8 : 10);
        parent.addView(text, params);
        return text;
    }

    private int background(Tone tone) {
        return switch (tone) {
            case POSITIVE -> color(activity, R.attr.colorPrimaryContainer);
            case ACTIVE -> color(activity, R.attr.colorTertiaryContainer);
            case NEUTRAL -> color(activity, R.attr.colorSurfaceVariant);
            case ATTENTION -> color(activity, R.attr.colorErrorContainer);
        };
    }

    private int onBackground(Tone tone) {
        return switch (tone) {
            case POSITIVE -> color(activity, R.attr.colorOnPrimaryContainer);
            case ACTIVE -> color(activity, R.attr.colorOnTertiaryContainer);
            case NEUTRAL -> color(activity, R.attr.colorOnSurfaceVariant);
            case ATTENTION -> color(activity, R.attr.colorOnErrorContainer);
        };
    }

    private int foreground(Tone tone) {
        return switch (tone) {
            case POSITIVE -> color(activity, ATTR_COLOR_PRIMARY);
            case ACTIVE -> color(activity, R.attr.colorTertiary);
            case NEUTRAL -> color(activity, R.attr.colorOnSurfaceVariant);
            case ATTENTION -> color(activity, ATTR_COLOR_ERROR);
        };
    }

    private static int color(android.content.Context context, int attr) {
        return MaterialColors.getColor(context, attr, 0);
    }

    public static int dp(android.content.Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
