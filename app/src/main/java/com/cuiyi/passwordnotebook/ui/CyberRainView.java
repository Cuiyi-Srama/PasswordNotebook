package com.cuiyi.passwordnotebook.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import java.util.Random;

/**
 * Falling-glyph background: the cyber / matrix look.
 *
 * Each column is an independent stream. The leading glyph is bright and has a
 * glow; the tail fades out with distance. A horizontal scanline overlay is
 * drawn beneath the glyphs to give the surface some texture.
 *
 * The view drives itself with postInvalidateOnAnimation so it shares the
 * display frame clock instead of sleeping on a background thread, and it stops
 * drawing entirely when paused, so the screen is not woken while the app is in
 * the background.
 */
public class CyberRainView extends View {

    private static final String GLYPHS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+-*/=<>[]{}|!@#$%^&";

    /** R G B used by the faded tail; alpha varies per glyph. */
    private static final int TRAIL_R = Theme.RAIN_TRAIL_R;
    private static final int TRAIL_G = Theme.RAIN_TRAIL_G;
    private static final int TRAIL_B = Theme.RAIN_TRAIL_B;

    private final Random random = new Random();
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundPaint = new Paint();
    private final Paint scanlinePaint = new Paint();

    private int columnCount;
    private float[] headY;
    private float[] speed;
    private int[] tailLength;

    private float width;
    private float height;
    private float scanlineOffset;
    private long lastFrameNanos;
    private boolean running = true;
    private boolean metricsReady;

    public CyberRainView(Context context) {
        this(context, null);
    }

    public CyberRainView(Context context, AttributeSet attrs) {
        super(context, attrs);
        headPaint.setColor(Theme.RAIN_HEAD);
        headPaint.setTextSize(Theme.RAIN_CHAR_SIZE);
        headPaint.setTypeface(Typeface.MONOSPACE);
        headPaint.setShadowLayer(20f, 0f, 0f, Theme.RAIN_GLOW);

        trailPaint.setTypeface(Typeface.MONOSPACE);
        trailPaint.setTextSize(Theme.RAIN_CHAR_SIZE);

        backgroundPaint.setColor(Theme.BG_DEEP);
        scanlinePaint.setColor(Theme.SCANLINE);
    }

    // ---------------- lifecycle ----------------

    /** Resume drawing. Called from the host's onResume. */
    public void resume() {
        if (!running) {
            running = true;
            lastFrameNanos = 0L;
            postInvalidateOnAnimation();
        }
    }

    /** Stop drawing. Called from the host's onPause. */
    public void pause() {
        running = false;
    }

    /** Release the column buffers. Called from the host's onDestroy. */
    public void release() {
        running = false;
        headY = null;
        speed = null;
        tailLength = null;
        metricsReady = false;
    }

    // ---------------- drawing ----------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Re-seed the columns for the new geometry.
        metricsReady = false;
    }

    private void prepareIfNeeded() {
        width = getWidth();
        height = getHeight();
        if (metricsReady || width <= 0f || height <= 0f) {
            return;
        }
        columnCount = (int) (width / Theme.RAIN_COL_GAP) + 1;
        headY = new float[columnCount];
        speed = new float[columnCount];
        tailLength = new int[columnCount];
        for (int i = 0; i < columnCount; i++) {
            resetColumn(i, true);
        }
        metricsReady = true;
    }

    /**
     * Give a column a fresh position, speed and tail length.
     *
     * @param spreadAcrossScreen when true the column starts anywhere on screen,
     *                           otherwise it starts above the top edge
     */
    private void resetColumn(int index, boolean spreadAcrossScreen) {
        if (spreadAcrossScreen) {
            headY[index] = random.nextFloat() * height;
        } else {
            headY[index] = -random.nextInt(Theme.RAIN_RESPAWN_ABOVE);
        }
        int span = Theme.RAIN_SPEED_MAX - Theme.RAIN_SPEED_MIN;
        speed[index] = Theme.RAIN_SPEED_MIN + random.nextInt(span);
        int tailSpan = Theme.RAIN_TAIL_MAX - Theme.RAIN_TAIL_MIN;
        tailLength[index] = Theme.RAIN_TAIL_MIN + random.nextInt(tailSpan);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        prepareIfNeeded();
        if (headY == null) {
            return;
        }

        float delta = computeDeltaSeconds();

        canvas.drawRect(0f, 0f, width, height, backgroundPaint);
        drawScanlines(canvas, delta);
        drawColumns(canvas, delta);

        if (running) {
            postInvalidateOnAnimation();
        }
    }

    /** Seconds since the previous frame, clamped so a pause does not cause a jump. */
    private float computeDeltaSeconds() {
        long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            return 1f / 60f;
        }
        float delta = (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        if (delta <= 0f || delta > Theme.RAIN_MAX_DELTA) {
            return 1f / 60f;
        }
        return delta;
    }

    private void drawScanlines(Canvas canvas, float delta) {
        scanlineOffset += Theme.SCANLINE_SPEED * delta;
        if (scanlineOffset >= Theme.RAIN_CHAR_SIZE) {
            scanlineOffset -= Theme.RAIN_CHAR_SIZE;
        }
        for (float y = scanlineOffset; y < height; y += Theme.RAIN_CHAR_SIZE) {
            canvas.drawLine(0f, y, width, y, scanlinePaint);
        }
    }

    private void drawColumns(Canvas canvas, float delta) {
        for (int col = 0; col < columnCount; col++) {
            headY[col] += speed[col] * delta;
            float tailReach = tailLength[col] * Theme.RAIN_CHAR_SIZE;
            if (headY[col] - tailReach > height) {
                resetColumn(col, false);
            }
            drawStream(canvas, col);
        }
    }

    private void drawStream(Canvas canvas, int col) {
        float x = col * Theme.RAIN_COL_GAP;
        int tail = tailLength[col];
        for (int i = 0; i < tail; i++) {
            float y = headY[col] - (i * Theme.RAIN_CHAR_SIZE);
            if (y < -Theme.RAIN_CHAR_SIZE || y > height + Theme.RAIN_CHAR_SIZE) {
                continue;
            }
            String glyph = glyph();
            if (i == 0) {
                canvas.drawText(glyph, x, y, headPaint);
            } else {
                float fade = Math.max(Theme.RAIN_TRAIL_MIN, 1f - ((float) i / tail));
                trailPaint.setColor(Color.argb(
                        (int) (Theme.RAIN_TRAIL_ALPHA * fade), TRAIL_R, TRAIL_G, TRAIL_B));
                canvas.drawText(glyph, x, y, trailPaint);
            }
        }
    }

    private String glyph() {
        return String.valueOf(GLYPHS.charAt(random.nextInt(GLYPHS.length())));
    }
}
