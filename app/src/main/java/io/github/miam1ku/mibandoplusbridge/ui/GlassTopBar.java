// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.R;
import com.google.android.material.color.MaterialColors;

/**
 * Floating glass top bar: the app's single title surface, shared by the paged home screen and every
 * secondary screen.
 *
 * <p>A capsule rather than a full-bleed toolbar because a bar that stops short of the screen edges
 * reads as a pane lying on top of the page, while a full-width bar reads as a strip cut out of it.
 * The content scrolls underneath either way; the difference is whether the page looks like it is
 * inside a frame or under a sheet of glass.
 *
 * <p>Height is fixed at 56dp so callers can reserve exactly the right amount of scroll padding
 * without measuring. {@link BridgeScreen#attach} and {@link BridgePager} both do that.
 */
public final class GlassTopBar extends FrameLayout {
    public static final int HEIGHT_DP = 56;
    /** Distance from the status bar to the top of the capsule. */
    public static final int TOP_GAP_DP = 10;

    private final LinearLayout row;
    private final TextView title;

    public GlassTopBar(Context context, String value, View.OnClickListener onBack) {
        super(context);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(GlassPanel.RADIUS_BAR));
            }
        });
        setClipToOutline(true);
        setElevation(dp(10));
        setBackground(new GlassPanel(context, GlassPanel.RADIUS_BAR));

        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(6);
        row.setPadding(pad, 0, dp(14), 0);
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        if (onBack != null) {
            ImageView back = new ImageView(context);
            back.setImageResource(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
            back.setImageTintList(ColorStateList.valueOf(
                    MaterialColors.getColor(back, R.attr.colorOnSurface)));
            back.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            back.setPadding(dp(10), dp(10), dp(10), dp(10));
            back.setBackground(clickable());
            back.setOnClickListener(onBack);
            back.setContentDescription("返回");
            row.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }

        title = new TextView(context);
        title.setText(value);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTextColor(MaterialColors.getColor(title, R.attr.colorOnSurface));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = onBack == null ? dp(10) : dp(2);
        row.addView(title, titleParams);
    }

    public void setTitle(String value) {
        title.setText(value);
    }

    /** Small text button at the end of the bar, e.g. "刷新". Returns it for enable/disable. */
    public TextView addAction(String label, Runnable action) {
        TextView button = new TextView(getContext());
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTextColor(MaterialColors.getColor(button,
                androidx.appcompat.R.attr.colorPrimary));
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setBackground(clickable());
        button.setMinWidth(dp(44));
        if (action != null) button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(40));
        params.leftMargin = dp(4);
        row.addView(button, params);
        return button;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP), MeasureSpec.EXACTLY));
    }

    private Drawable clickable() {
        TypedValue value = new TypedValue();
        getContext().getTheme().resolveAttribute(
                androidx.appcompat.R.attr.selectableItemBackgroundBorderless, value, true);
        return value.resourceId != 0
                ? getContext().getDrawable(value.resourceId) : null;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
