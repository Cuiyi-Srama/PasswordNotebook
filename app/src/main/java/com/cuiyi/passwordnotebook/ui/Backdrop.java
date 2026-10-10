package com.cuiyi.passwordnotebook.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

/**
 * One blurred still of the background, shared by every glass pane on screen.
 *
 * <p>This exists because Android has no way to blur what is painted *behind* a
 * view. {@code View.setRenderEffect} blurs a view's own content and
 * {@code Window.setBackgroundBlurRadius} blurs the window behind the app, so
 * neither produces glass. What every real implementation does instead is
 * render the backdrop itself and sample it, which is what this class prepares.
 *
 * <p>The still is refreshed on a timer rather than per frame. The rain drifts
 * slowly, and once blurred the difference between a live and a slightly stale
 * copy is not visible, so the cost of a full screen capture and blur is paid a
 * couple of times a second instead of sixty.
 */
public final class Backdrop {

    private static Bitmap blurred;
    private static int originX;
    private static int originY;

    private Backdrop() {
    }

    /** The blurred backdrop, or null before the first {@link #capture}. */
    public static Bitmap blurred() {
        return blurred;
    }

    /**
     * Renders {@code rain} and {@code scrim} into one bitmap and blurs it.
     *
     * @return false when the views have no size yet, which is normal on the
     *         first pass and simply means there is nothing to capture.
     */
    public static boolean capture(View rain, View scrim, int baseColor, float blurRadius) {
        if (rain == null) {
            return false;
        }
        int width = rain.getWidth();
        int height = rain.getHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }

        Bitmap raw;
        try {
            raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError oom) {
            // A full screen ARGB buffer is around 10 MB. Giving up on glass is
            // the right answer when memory is this tight.
            return false;
        }

        int[] location = new int[2];
        rain.getLocationInWindow(location);
        originX = location[0];
        originY = location[1];

        Canvas canvas = new Canvas(raw);
        canvas.drawColor(baseColor);
        rain.draw(canvas);
        if (scrim != null && scrim.getVisibility() == View.VISIBLE) {
            scrim.draw(canvas);
        }

        Bitmap result = blur(raw, blurRadius);
        if (result != raw) {
            raw.recycle();
        }
        Bitmap previous = blurred;
        blurred = result;
        if (previous != null && previous != result) {
            previous.recycle();
        }
        return true;
    }

    /**
     * Gaussian blur through a render node.
     *
     * <p>Below API 31 there is no RenderEffect at all, so the still is handed
     * back untouched: the glass then shows a sharp backdrop, which is wrong but
     * harmless, and the styles that do not rely on blur are unaffected.
     */
    private static Bitmap blur(Bitmap source, float radius) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || radius <= 0f) {
            return source;
        }
        try {
            RenderNode node = new RenderNode("glass-backdrop");
            node.setPosition(0, 0, source.getWidth(), source.getHeight());
            node.setRenderEffect(RenderEffect.createBlurEffect(
                    radius, radius, Shader.TileMode.CLAMP));

            RecordingCanvas recording = node.beginRecording(source.getWidth(), source.getHeight());
            recording.drawBitmap(source, 0f, 0f, null);
            node.endRecording();

            Bitmap out = Bitmap.createBitmap(
                    source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            canvas.drawRenderNode(node);
            return out;
        } catch (Throwable t) {
            return source;
        }
    }

    /** Where the captured region sits in window coordinates. */
    public static int originX() {
        return originX;
    }

    public static int originY() {
        return originY;
    }

    /** Frees the shared still. Called when the style no longer needs it. */
    public static void release() {
        Bitmap previous = blurred;
        blurred = null;
        if (previous != null) {
            previous.recycle();
        }
    }
}
