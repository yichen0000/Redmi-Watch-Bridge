// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import androidx.core.graphics.ColorUtils;
import com.google.android.material.R;
import com.google.android.material.color.MaterialColors;

/**
 * Window background: a soft wash of the brand hues over the surface colour.
 *
 * <p>Why this exists at all — glass only reads as glass when there is something behind it to bend.
 * Over a flat single colour the translucent pane has nothing to show through, so the frosted effect
 * collapses into a slightly lighter rectangle. Three wide, very low-alpha colour fields (mint at the
 * upper right, indigo at the lower left, mint again at the bottom) give every card something
 * variegated to pick up, which is what makes the translucency visible at all.
 *
 * <p>The fields are real radial gradients rather than a photograph, so there is nothing to blur:
 * blurring a gradient is a no-op. That is why the frosted look is built from translucency plus the
 * panel's own sheen — see {@link GlassPanel}. It is the same reason a frosted window looks different
 * over a garden than over a wall.
 *
 * <p>Geometry note: every blob's radius is kept smaller than its distance to y = 0, and the vertical
 * wash starts at zero alpha on the very top edge. The status bar is painted with the plain surface
 * colour by the theme, so a wash that is already at full strength at y = 0 would draw a visible seam
 * right under it. Fading in from zero keeps the top edge identical to the status bar while still
 * putting colour behind the floating top bar.
 *
 * <p>Shaders are built in {@link #onBoundsChange} instead of per frame: this drawable is the root
 * background, so it repaints on every scroll frame, and allocating a shader there would be one
 * allocation per frame for a picture that never changes.
 */
public final class GlassBackdrop extends Drawable {
    private final Paint base = new Paint();
    private final Paint wash = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final int surface;
    private final int mint;
    private final int indigo;
    private final boolean dark;

    private LinearGradient topWash;
    private RadialGradient blobTopRight;
    private RadialGradient blobLeft;
    private RadialGradient blobBottom;
    private final Rect built = new Rect();

    public GlassBackdrop(Context context) {
        surface = MaterialColors.getColor(context, R.attr.colorSurface, Color.WHITE);
        mint = MaterialColors.getColor(context, R.attr.colorPrimaryContainer, Color.WHITE);
        indigo = MaterialColors.getColor(context, R.attr.colorTertiaryContainer, Color.WHITE);
        dark = ColorUtils.calculateLuminance(surface) < 0.5;
        base.setColor(surface);
    }

    @Override protected void onBoundsChange(Rect bounds) {
        built.set(bounds);
        int w = bounds.width();
        int h = bounds.height();
        if (w <= 0 || h <= 0) return;

        int washPeak = dark ? 0x1A : 0x14;
        topWash = new LinearGradient(0, bounds.top, 0, bounds.top + h * 0.52f,
                new int[]{
                        withAlpha(mint, 0),
                        withAlpha(mint, washPeak),
                        withAlpha(mint, 0)},
                new float[]{0f, 0.30f, 1f}, Shader.TileMode.CLAMP);

        // Radii stay below the centre's distance to the top edge, so no blob bleeds into the status
        // bar — that is the whole reason the top seam does not come back.
        blobTopRight = blob(mint, dark ? 0x2E : 0x7A,
                (int) (bounds.left + w * 0.92f), (int) (bounds.top + h * 0.36f),
                (int) (h * 0.34f));
        blobLeft = blob(indigo, dark ? 0x2A : 0x6B,
                (int) (bounds.left + w * 0.06f), (int) (bounds.top + h * 0.66f),
                (int) (h * 0.30f));
        blobBottom = blob(mint, dark ? 0x24 : 0x5C,
                (int) (bounds.left + w * 0.78f), (int) (bounds.top + h * 0.98f),
                (int) (h * 0.36f));
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (!built.equals(bounds)) onBoundsChange(bounds);
        canvas.drawRect(bounds, base);
        if (topWash == null) return;
        wash.setShader(topWash);
        canvas.drawRect(bounds, wash);
        wash.setShader(blobTopRight);
        canvas.drawRect(bounds, wash);
        wash.setShader(blobLeft);
        canvas.drawRect(bounds, wash);
        wash.setShader(blobBottom);
        canvas.drawRect(bounds, wash);
        wash.setShader(null);
    }

    /**
     * A colour field that fades to nothing on all sides. Four stops instead of two because a linear
     * alpha ramp reads as a visible circle: the outer third has to fall off much faster than the
     * inner two thirds for the edge to disappear.
     */
    private static RadialGradient blob(int color, int peak, int cx, int cy, int radius) {
        return new RadialGradient(cx, cy, Math.max(1, radius),
                new int[]{
                        withAlpha(color, peak),
                        withAlpha(color, (int) (peak * 0.62f)),
                        withAlpha(color, (int) (peak * 0.16f)),
                        withAlpha(color, 0)},
                new float[]{0f, 0.42f, 0.78f, 1f}, Shader.TileMode.CLAMP);
    }

    private static int withAlpha(int color, int alpha) {
        return ColorUtils.setAlphaComponent(color, alpha);
    }

    @Override public void setAlpha(int alpha) {
        // This is the window background; fading it would show black through.
    }

    @Override public void setColorFilter(android.graphics.ColorFilter filter) {
        base.setColorFilter(filter);
    }

    @Override public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
