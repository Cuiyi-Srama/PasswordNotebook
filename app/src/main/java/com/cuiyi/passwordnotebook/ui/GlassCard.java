package com.cuiyi.passwordnotebook.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * A container that draws the blurred backdrop behind itself, the way a real
 * pane of glass would.
 *
 * <p>The painting itself lives in {@link GlassPainter}; this class only knows
 * how to place a pane and how to make sure it gets repainted when the backdrop
 * moves on.
 */
public class GlassCard extends LinearLayout {

    /**
     * Every pane currently attached, so the backdrop timer can repaint them.
     *
     * <p>Invalidating a parent is not enough: a view whose display list has not
     * changed keeps its cached drawing, and onDraw is never called again. The
     * rain would then sit frozen inside the glass, which is exactly the bug
     * this list exists to fix.
     */
    private static final List<GlassCard> LIVE = new ArrayList<>();

    private final float radiusDp;
    private final float thicknessDp;
    private final int tintColor;
    private final float tintAmount;
    private final Rect bounds = new Rect();

    public GlassCard(Context context, float radiusDp, float thicknessDp,
                     int tintColor, float tintAmount) {
        super(context);
        this.radiusDp = radiusDp;
        this.thicknessDp = thicknessDp;
        this.tintColor = tintColor;
        this.tintAmount = tintAmount;
        setWillNotDraw(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!LIVE.contains(this)) {
            LIVE.add(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        LIVE.remove(this);
    }

    /** Repaints every pane. Called each time a new backdrop is captured. */
    public static void invalidateAll() {
        for (int i = 0; i < LIVE.size(); i++) {
            LIVE.get(i).invalidate();
        }
        GlassDrawable.invalidateAll();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        bounds.set(0, 0, getWidth(), getHeight());
        boolean painted = GlassPainter.paint(canvas, bounds, this,
                dp(radiusDp), dp(thicknessDp), tintColor, tintAmount);
        if (!painted) {
            // No backdrop yet, or the first layout pass. A flat fill keeps the
            // card readable rather than leaving a hole.
            Paint fallback = new Paint(Paint.ANTI_ALIAS_FLAG);
            fallback.setColor(Theme.SURFACE);
            canvas.drawRoundRect(new RectF(bounds), dp(radiusDp), dp(radiusDp), fallback);
        }
        super.onDraw(canvas);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density + 0.5f;
    }
}
