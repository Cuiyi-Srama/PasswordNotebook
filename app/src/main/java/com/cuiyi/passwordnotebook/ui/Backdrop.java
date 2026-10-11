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
 * <p>This exists because Android has no way to blur what is painted behind a
 * view. {@code View.setRenderEffect} blurs a view own content and
 * {@code Window.setBackgroundBlurRadius} blurs the window behind the app, so
 * neither produces glass. Every real implementation renders the backdrop
 * itself and samples it instead, which is what this class prepares.
 *
 * <p>The still is refreshed on a timer rather than per frame, at a cadence
 * fast enough that the rain still reads as moving behind the glass.
 */
public final class Backdrop {

    /**
     * The backdrop is captured at half resolution.
     *
     * It is going to be blurred anyway, so the lost detail is invisible in the
     * result, while the capture, the blur and the texture upload all shrink by
     * four. That saving is what makes a fast refresh affordable, and a fast
     * refresh is what makes the rain look alive through the glass.
     */
    private static final float CAPTURE_SCALE = 0.5f;

    private static Bitmap blurred;
    private static int originX;
    private static int originY;
    private static float captureScale = 1f;

    private Backdrop() {
    }

    /** The blurred backdrop, or null before the first {@link #capture}. */
    public static Bitmap blurred() {
        return blurred;
    }

    /** Where the captured region sits in window coordinates. */
    public static int originX() {
        return originX;
    }

    public static int originY() {
        return originY;
    }

    /**
     * Scale of the captured bitmap relative to window coordinates.
     *
     * Panes multiply their offsets by this before sampling, because the shader
     * indexes pixels of the scaled bitmap, not pixels of the window.
     */
    public static float captureScale() {
        return captureScale;
    }

    /**
     * Renders {@code rain} and {@code scrim} into one bitmap and blurs it.
     *
     * @return false when the views have no size yet, which is normal on the
     *         first pass and simply means there is nothing to capture.
     */
    public static boolean capture(View rain, View aurora, View scrim, int baseColor, float blurRadius) {
        // Take the size from whichever layer has been laid out. The one that
        // is hidden may never have been measured, and its zero would abort the
        // capture even though the visible layer is perfectly fine.
        View sized = null;
        View[] candidates = {rain, aurora};
        for (int i = 0; i < candidates.length; i++) {
            View candidate = candidates[i];
            if (candidate != null && candidate.getWidth() > 0 && candidate.getHeight() > 0) {
                sized = candidate;
                break;
            }
        }
        if (sized == null) {
            return false;
        }
        int fullWidth = sized.getWidth();
        int fullHeight = sized.getHeight();
        int width = Math.max(1, Math.round(fullWidth * CAPTURE_SCALE));
        int height = Math.max(1, Math.round(fullHeight * CAPTURE_SCALE));

        Bitmap raw;
        try {
            raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError oom) {
            // Half a screen of ARGB is still a few megabytes. Giving up on
            // glass is the right answer when memory is this tight.
            return false;
        }

        Canvas canvas = new Canvas(raw);
        canvas.scale(CAPTURE_SCALE, CAPTURE_SCALE);
        int[] location = new int[2];
        sized.getLocationInWindow(location);
        originX = location[0];
        originY = location[1];
        captureScale = CAPTURE_SCALE;

        canvas.drawColor(baseColor);
        // Draw only what is actually on screen. View.draw ignores visibility,
        // so a hidden layer would otherwise leak into the glass.
        if (rain != null && rain.getVisibility() == View.VISIBLE) {
            rain.draw(canvas);
        }
        if (aurora != null && aurora.getVisibility() == View.VISIBLE) {
            aurora.draw(canvas);
        }
        if (scrim != null && scrim.getVisibility() == View.VISIBLE) {
            scrim.draw(canvas);
        }

        Bitmap result = blur(raw, blurRadius * CAPTURE_SCALE);
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

    /** Frees the shared still. Called when the style no longer needs it. */
    public static void release() {
        Bitmap previous = blurred;
        blurred = null;
        if (previous != null) {
            previous.recycle();
        }
    }
}
