package com.cuiyi.passwordnotebook.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Glass as a background drawable, for hosts that cannot paint their own.
 *
 * <p>Buttons are text views, so they have no onDraw of their own to hook. A
 * drawable gets the same job done and is the only thing they can accept, which
 * is why the glass look arrives here through a second door.
 *
 * <p>One instance per button: a drawable carries the bounds it was last drawn
 * at, so sharing one between several hosts would make them fight.
 */
public class GlassDrawable extends Drawable {

    /** Mirrors {@link GlassCard} so the backdrop timer can reach both kinds. */
    private static final List<GlassDrawable> LIVE = new ArrayList<>();

    private final float radiusDp;
    private final float thicknessDp;
    private final int tintColor;
    private final float tintAmount;
    private final float density;
    private final Rect bounds = new Rect();
    private boolean listening;

    public GlassDrawable(float density, float radiusDp, float thicknessDp,
                         int tintColor, float tintAmount) {
        this.density = density;
        this.radiusDp = radiusDp;
        this.thicknessDp = thicknessDp;
        this.tintColor = tintColor;
        this.tintAmount = tintAmount;
    }

    /** Repaints every glass button. Called alongside GlassCard.invalidateAll. */
    public static void invalidateAll() {
        for (int i = 0; i < LIVE.size(); i++) {
            LIVE.get(i).invalidateSelf();
        }
    }

    @Override
    protected void onBoundsChange(Rect newBounds) {
        super.onBoundsChange(newBounds);
        if (!listening) {
            listening = true;
            if (!LIVE.contains(this)) {
                LIVE.add(this);
            }
        }
    }

    @Override
    public void draw(Canvas canvas) {
        bounds.set(getBounds());
        View host = getCallback() instanceof View ? (View) getCallback() : null;
        boolean painted = GlassPainter.paint(canvas, bounds, host,
                dp(radiusDp), dp(thicknessDp), tintColor, tintAmount);
        if (!painted) {
            Paint fallback = new Paint(Paint.ANTI_ALIAS_FLAG);
            fallback.setColor(Theme.BUTTON_BG);
            canvas.drawRoundRect(new RectF(bounds), dp(radiusDp), dp(radiusDp), fallback);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        // The backdrop supplies its own opacity; nothing to scale here.
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private float dp(float value) {
        return value * density + 0.5f;
    }
}
