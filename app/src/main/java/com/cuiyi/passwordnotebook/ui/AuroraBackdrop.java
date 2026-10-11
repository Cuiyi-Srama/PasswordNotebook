package com.cuiyi.passwordnotebook.ui;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * A soft field of colour for the glass style to refract.
 *
 * <p>Glass needs something behind it. A lens over a black screen is just a grey
 * smudge, which is exactly how the first attempt looked: the rain is sparse
 * green glyphs on near black, so blurring it yields almost nothing and every
 * pane turned into grey fog.
 *
 * <p>This is a renderer, not a view. It is painted straight into the backdrop
 * bitmap, so there is no second copy on screen to drift out of step with the
 * one the panes sample, and no dependency on the view pipeline having laid the
 * widget out first. Drawing a view that has never been painted returns an empty
 * rectangle, which is a failure mode worth designing out rather than guarding
 * against.
 */
public final class AuroraBackdrop {

    /** Each bloom: centre as a fraction of size, radius as a fraction, colour. */
    private static final float[][] BLOOMS = {
            {0.18f, 0.16f, 0.62f, 0xFF1E5F6E},
            {0.86f, 0.24f, 0.58f, 0xFF2A3A7A},
            {0.28f, 0.72f, 0.66f, 0xFF1B6B5A},
            {0.78f, 0.88f, 0.60f, 0xFF3A2A6E},
            {0.50f, 0.46f, 0.44f, 0xFF17505C},
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float phase;

    /** Advances the drift and paints the field into {@code canvas}. */
    public void draw(Canvas canvas, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        phase += 0.09f;
        if (phase > 6.2831855f) {
            phase -= 6.2831855f;
        }
        canvas.drawColor(0xFF070A12);

        // A broad diagonal wash, so the corners do not sink to flat black and
        // undo the point of having a backdrop at all.
        paint.setShader(new LinearGradient(0, 0, width, height,
                new int[]{0xFF0A1020, 0xFF120A1E, 0xFF08161A},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);

        float longest = Math.max(width, height);
        for (int i = 0; i < BLOOMS.length; i++) {
            float[] bloom = BLOOMS[i];
            float cx = bloom[0] * width;
            float cy = bloom[1] * height;
            // Slow breathing, so what the glass transmits is not a still image.
            float radius = bloom[2] * longest * (1f + 0.10f * (float) Math.sin(phase + i));
            int colour = (int) bloom[3];
            int alpha = (colour >>> 24) & 0xFF;
            int faded = (colour & 0x00FFFFFF) | (Math.round(alpha * 0.55f) << 24);
            paint.setShader(new RadialGradient(cx, cy, Math.max(1f, radius),
                    new int[]{colour, faded, 0x00000000},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
        }
    }
}
