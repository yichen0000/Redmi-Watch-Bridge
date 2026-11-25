// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import androidx.core.graphics.ColorUtils;
import com.google.android.material.R;
import com.google.android.material.color.MaterialColors;

/**
 * The pane of frosted glass used by every surface in the app: cards, the tab bar, the top bar and
 * the summary strips.
 *
 * <p>Four layers stacked on one rounded rect — a translucent fill that is lighter at the top than at
 * the bottom, a specular sheen strongest at the top-left and gone by the middle, a wide 3dp halo,
 * and a crisp 1dp edge that is bright at the top and falls off at the bottom. The halo is what reads
 * as the "微光边框": a single 1dp line looks like a hairline rule, while a line sitting inside a
 * faintly lit ring looks like an edge catching light. Those four are what make a flat rectangle read
 * as glass; a plain translucent fill on its own reads as a tinted box.
 *
 * <p>There is deliberately no backdrop blur. {@code RenderEffect} needs the content rendered into an
 * offscreen layer, blurred and re-composited every frame, and the backdrop here is the app's own
 * gradient — blurring a gradient changes nothing. What sells the frost is the translucency over a
 * variegated backdrop (see {@link GlassBackdrop}) plus the sheen, at zero per-frame cost.
 */
public final class GlassPanel extends Drawable {
    /** Radius that makes a rounded rect into a pill for a 62dp bar. */
    public static final float RADIUS_PILL = 31f;
    /** Radius for the 56dp top bar. */
    public static final float RADIUS_BAR = 28f;
    /** Radius for a content card. */
    public static final float RADIUS_CARD = 24f;

    /** Where the pane sits in the depth order: bars float over content, cards sit on it. */
    public enum Kind {
        /** Bars are read through, so they stay the most opaque of the two. */
        BAR,
        /** Cards carry text, so they are lighter and let the gradient tint them. */
        CARD
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final float radius;
    private final float stroke;
    private final float haloWidth;
    private final int fillTop;
    private final int fillBottom;
    private final int edgeTop;
    private final int edgeBottom;
    private final int sheenTop;
    private final int haloColor;

    public GlassPanel(Context context, float radiusDp) {
        this(context, radiusDp, Kind.BAR, 0);
    }

    public GlassPanel(Context context, float radiusDp, Kind kind) {
        this(context, radiusDp, kind, 0);
    }

    /**
     * A card tinted with a status colour: the notice cards. The tint is applied at fill alpha rather
     * than as an opaque container colour, so the gradient still shows through the card.
     */
    public static GlassPanel tinted(Context context, float radiusDp, int color) {
        return new GlassPanel(context, radiusDp, Kind.CARD, color);
    }

    private GlassPanel(Context context, float radiusDp, Kind kind, int tint) {
        float density = context.getResources().getDisplayMetrics().density;
        radius = radiusDp * density;
        stroke = Math.max(1f, density);
        haloWidth = 3f * density;
        boolean dark = ColorUtils.calculateLuminance(
                MaterialColors.getColor(context, R.attr.colorSurface, Color.GRAY)) < 0.5;

        if (tint != 0) {
            fillTop = withAlpha(tint, dark ? 0x4D : 0x66);
            fillBottom = withAlpha(tint, dark ? 0x33 : 0x4D);
            edgeTop = withAlpha(tint, dark ? 0x8C : 0xB3);
            edgeBottom = withAlpha(tint, 0x33);
            sheenTop = 0x33FFFFFF;
            haloColor = withAlpha(tint, dark ? 0x1F : 0x2E);
        } else if (kind == Kind.CARD) {
            if (dark) {
                fillTop = 0x24FFFFFF;
                fillBottom = 0x14FFFFFF;
                edgeTop = 0x47FFFFFF;
                edgeBottom = 0x14FFFFFF;
                sheenTop = 0x1AFFFFFF;
                haloColor = 0x14FFFFFF;
            } else {
                // 60%/46%: enough body for 16sp body text over a pastel gradient, still clearly
                // translucent — the card takes its colour from whatever blob is behind it. Higher
                // than this turns the card into a plain white sheet and the frost is gone.
                fillTop = 0x99FFFFFF;
                fillBottom = 0x75FFFFFF;
                edgeTop = 0xF0FFFFFF;
                edgeBottom = 0x3D0F766E;
                sheenTop = 0x59FFFFFF;
                haloColor = 0x1FFFFFFF;
            }
        } else {
            if (dark) {
                fillTop = 0x33FFFFFF;
                fillBottom = 0x14FFFFFF;
                edgeTop = 0x59FFFFFF;
                edgeBottom = 0x14FFFFFF;
                sheenTop = 0x1FFFFFFF;
                haloColor = 0x14FFFFFF;
            } else {
                // Deliberately translucent: scrolling content stays readable through the pane, which
                // is the point of the material. Raising the fill towards opaque (tried at 97%/92%)
                // hides the content underneath and turns the pane into a solid white bar — the
                // product decision is to keep the glass, so do not "fix" this by making it opaque.
                fillTop = 0xEBFFFFFF;
                fillBottom = 0xC4FFFFFF;
                edgeTop = 0xFFFFFFFF;
                edgeBottom = 0x1A0F766E;
                sheenTop = 0x66FFFFFF;
                haloColor = 0x1FFFFFFF;
            }
        }
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(stroke);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(haloWidth);
        halo.setColor(haloColor);
    }

    @Override public void draw(Canvas canvas) {
        bounds.set(getBounds());
        float inset = stroke / 2f;
        RectF shape = new RectF(bounds.left + inset, bounds.top + inset,
                bounds.right - inset, bounds.bottom - inset);

        fill.setShader(new LinearGradient(0, shape.top, 0, shape.bottom,
                fillTop, fillBottom, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(shape, radius, radius, fill);
        fill.setShader(null);

        sheen.setShader(new LinearGradient(shape.left, shape.top, shape.right,
                shape.top + shape.height() * 0.55f, sheenTop, 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(shape, radius, radius, sheen);
        sheen.setShader(null);

        // Drawn inside the bounds so it stays a lit edge rather than a shadow that the view's own
        // canvas would clip away.
        canvas.drawRoundRect(shape, radius, radius, halo);

        edge.setShader(new LinearGradient(0, shape.top, 0, shape.bottom,
                edgeTop, edgeBottom, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(shape, radius, radius, edge);
        edge.setShader(null);
    }

    private static int withAlpha(int color, int alpha) {
        return ColorUtils.setAlphaComponent(color, alpha);
    }

    @Override public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        sheen.setAlpha(alpha);
        halo.setAlpha(alpha);
        edge.setAlpha(alpha);
        invalidateSelf();
    }

    @Override public void setColorFilter(android.graphics.ColorFilter filter) {
        fill.setColorFilter(filter);
        sheen.setColorFilter(filter);
        halo.setColorFilter(filter);
        edge.setColorFilter(filter);
        invalidateSelf();
    }

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
