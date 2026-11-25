// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import com.google.android.material.R;
import com.google.android.material.color.MaterialColors;
import java.util.ArrayList;
import java.util.List;

/**
 * Floating frosted tab bar — the "liquid glass" navigation for the home screen.
 *
 * <p>Why a custom view instead of {@code BottomNavigationView}: the glass look needs a translucent
 * fill, a specular edge that is brighter at the top than the bottom, and a selection pill that
 * slides between tabs. Material's bar gives a flat surface with per-item ripples, which reads as a
 * toolbar pinned to the bottom rather than a pane of glass floating over the content.
 *
 * <p>There is no real backdrop blur: {@code RenderEffect} would need the content rendered into an
 * offscreen layer that is blurred and re-composited every frame, which costs more than it is worth
 * on a settings screen whose backdrop is a flat surface colour. The translucency plus the sheen
 * reads as glass over that backdrop; over text it reads as frosted, which is the same compromise
 * every non-native implementation of this style makes.
 */
public final class GlassTabBar extends FrameLayout {
    /** Fired only when the user taps a tab, never for a programmatic selection. */
    public interface OnTabSelected {
        void onTabSelected(int index);
    }

    private static final int HEIGHT_DP = 62;
    private static final int RADIUS_DP = 31;
    private static final int INSET_DP = 6;
    private static final long SLIDE_MS = 320;

    private final LinearLayout row;
    private final View capsule;
    private final List<TextView> labels = new ArrayList<>();
    private OnTabSelected listener;
    private int selected;
    private int tabWidth;
    private float capsuleOffset;

    public GlassTabBar(Context context, String... tabs) {
        super(context);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(RADIUS_DP));
            }
        });
        setClipToOutline(true);
        setElevation(dp(12));
        setBackground(new GlassPanel(getContext(), RADIUS_DP));

        capsule = new View(context);
        capsule.setBackground(capsuleShape(context));
        addView(capsule, new LayoutParams(0, 0));

        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int inset = dp(INSET_DP);
        row.setPadding(inset, inset, inset, inset);
        for (int index = 0; index < tabs.length; index++) {
            TextView label = new TextView(context);
            label.setText(tabs[index]);
            label.setTextSize(13);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setLetterSpacing(0.02f);
            final int picked = index;
            label.setOnClickListener(view -> select(picked, true));
            row.addView(label, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
            labels.add(label);
        }
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        paintLabels();
    }

    public void setOnTabSelected(OnTabSelected listener) {
        this.listener = listener;
    }

    public int selected() {
        return selected;
    }

    public void select(int index) {
        select(index, false);
    }

    /** {@code fromUser} decides whether the pager gets told about it. */
    public void select(int index, boolean fromUser) {
        if (index < 0 || index >= labels.size()) return;
        selected = index;
        paintLabels();
        slideTo(index, true);
        if (fromUser && listener != null) listener.onTabSelected(index);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec,
                MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP), MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int count = labels.size();
        if (count == 0 || getWidth() == 0) return;
        int inset = dp(INSET_DP);
        tabWidth = (getWidth() - inset * 2) / count;
        int width = tabWidth - dp(4);
        int height = getHeight() - inset * 2;
        int left0 = inset + dp(2);
        capsule.layout(left0, inset, left0 + width, inset + height);
        // A width change (rotation, split screen) must not leave the pill between two tabs.
        slideTo(selected, false);
    }

    private void slideTo(int index, boolean animate) {
        if (tabWidth <= 0) return;
        float target = (float) index * tabWidth;
        if (!animate) {
            capsuleOffset = target;
            capsule.setTranslationX(target);
            return;
        }
        ValueAnimator slide = ValueAnimator.ofFloat(capsuleOffset, target);
        slide.setDuration(SLIDE_MS);
        slide.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        slide.addUpdateListener(animation -> {
            capsuleOffset = (float) animation.getAnimatedValue();
            capsule.setTranslationX(capsuleOffset);
        });
        slide.start();
        // The pill stretches as it lands, which is what makes it read as liquid rather than a cursor.
        capsule.setScaleX(0.88f);
        capsule.animate().scaleX(1f).setDuration(260).setInterpolator(
                new OvershootInterpolator(1.2f)).start();
    }

    private void paintLabels() {
        for (int index = 0; index < labels.size(); index++) {
            TextView label = labels.get(index);
            boolean active = index == selected;
            label.setTextColor(MaterialColors.getColor(label,
                    active ? androidx.appcompat.R.attr.colorPrimary : R.attr.colorOnSurfaceVariant));
            label.setTypeface(Typeface.create("sans-serif-medium",
                    active ? Typeface.BOLD : Typeface.NORMAL));
            label.setAlpha(active ? 1f : 0.86f);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static Drawable capsuleShape(Context context) {
        boolean dark = ColorUtils.calculateLuminance(
                MaterialColors.getColor(context, R.attr.colorSurface, Color.GRAY)) < 0.5;
        int primary = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary,
                Color.GRAY);
        GradientDrawable pill = new GradientDrawable();
        pill.setShape(GradientDrawable.RECTANGLE);
        pill.setCornerRadius(context.getResources().getDisplayMetrics().density * 26);
        pill.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        pill.setColors(new int[]{
                withAlpha(primary, dark ? 0x3D : 0x2B),
                withAlpha(primary, dark ? 0x1F : 0x14)});
        pill.setStroke(Math.max(1, Math.round(
                context.getResources().getDisplayMetrics().density)), withAlpha(primary,
                dark ? 0x45 : 0x2E));
        return pill;
    }

    private static int withAlpha(int color, int alpha) {
        return ColorUtils.setAlphaComponent(color, alpha);
    }

    // The pane itself lives in GlassPanel: the same drawable dresses the top bar and the strips, and
    // keeping one implementation is what makes the surfaces look like the same material.
}
