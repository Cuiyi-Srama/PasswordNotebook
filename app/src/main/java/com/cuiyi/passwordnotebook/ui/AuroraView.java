package com.cuiyi.passwordnotebook.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * A soft field of colour for the glass style to refract.
 *
 * <p>Glass needs something behind it. A lens over a black screen is just a grey
 * smudge, which is exactly how the first attempt looked: the rain is sparse
 * green glyphs on near black, so blurring it yields almost nothing and every
 * pane turned into grey fog.
 *
 * <p>This draws a few large, low saturation blooms instead. Each one is cheap
 * (a radial gradient), they overlap into a broad wash of colour, and once the
 * backdrop is blurred they read as depth rather than as shapes. The panes then
 * have something worth transmitting.
 */
public class AuroraView extends View {

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
    private Shader[] shaders;
    private int width;
    private int height;

    public AuroraView(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        width = w;
        height = h;
        shaders = null;
    }

    /** Drifts the blooms. Called from the backdrop timer, not per frame. */
    public void advance() {
        phase += 0.035f;
        if (phase > 6.2831855f) {
            phase -= 6.2831855f;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (width <= 0 || height <= 0) {
            return;
        }
        canvas.drawColor(0xFF070A12);

        if (shaders == null) {
            shaders = buildShaders();
        }
        for (int i = 0; i < shaders.length; i++) {
            paint.setShader(shaders[i]);
            canvas.drawRect(0, 0, width, height, paint);
        }
    }

    /**
     * One radial gradient per bloom, laid over a broad diagonal wash.
     *
     * The wash keeps the corners from going flat black, which would undo the
     * whole point of having a backdrop at all.
     */
    private Shader[] buildShaders() {
        Shader[] built = new Shader[BLOOMS.length + 1];
        built[0] = new LinearGradient(0, 0, width, height,
                new int[]{0xFF0A1020, 0xFF120A1E, 0xFF08161A},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);

        for (int i = 0; i < BLOOMS.length; i++) {
            float[] bloom = BLOOMS[i];
            float cx = bloom[0] * width;
            float cy = bloom[1] * height;
            // Slow breathing, so the glass transmits something that moves.
            float radius = bloom[2] * Math.max(width, height)
                    * (1f + 0.10f * (float) Math.sin(phase + i));
            int colour = (int) bloom[3];
            int alpha = (colour >>> 24) & 0xFF;
            int faded = (colour & 0x00FFFFFF) | (Math.round(alpha * 0.55f) << 24);
            built[i + 1] = new RadialGradient(cx, cy, Math.max(1f, radius),
                    new int[]{colour, faded, 0x00000000},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        }
        return built;
    }
}
