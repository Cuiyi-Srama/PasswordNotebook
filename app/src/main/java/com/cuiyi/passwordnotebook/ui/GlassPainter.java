package com.cuiyi.passwordnotebook.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

/**
 * Draws one frosted glass pane.
 *
 * <p>Separated from its hosts because the same look has to appear on two things
 * that have nothing in common: containers, which are view groups and paint in
 * onDraw, and buttons, which are text views and can only supply a background
 * drawable. Keeping the shader and the fallback logic in one place means a fix
 * or a tweak lands on both.
 */
public final class GlassPainter {

    /**
     * Magnifies the backdrop near the edges, like a lens.
     *
     * Ported from the reference implementation of this effect: the sample
     * coordinate is scaled towards the centre by an amount that grows near the
     * edge, so the middle of the pane shows the backdrop unchanged while the
     * border shows it enlarged. That inward pull is what reads as refraction; a
     * plain sideways shift only looks like the image has been torn.
     */
    private static final String AGSL =
            "uniform shader uBackdrop;\n"

            + "uniform float2 uOffset;\n"

            + "uniform float2 uSize;\n"

            + "uniform float uDepth;\n"

            + "uniform float uStrength;\n"

            + "uniform float uAberration;\n"

            + "uniform float uExposure;\n"

            + "uniform float3 uTintColor;\n"

            + "uniform float uTint;\n"

            + "float sdRoundRect(float2 p, float2 halfSize, float r) {\n"

            + "    float2 q = abs(p) - halfSize + r;\n"

            + "    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - r;\n"

            + "}\n"

            + "float smoothStep(float a, float b, float t) {\n"

            + "    t = clamp((t - a) / (b - a), 0.0, 1.0);\n"

            + "    return t * t * (3.0 - 2.0 * t);\n"

            + "}\n"

            + "half4 main(float2 coord) {\n"

            + "    float2 halfSize = uSize * 0.5;\n"

            + "    float2 p = coord - halfSize;\n"

            + "    // Distance to the rounded edge, in pixels. Negative inside.\n"

            + "    float d = sdRoundRect(p, halfSize, min(halfSize.x, halfSize.y));\n"

            + "    // Zero in the middle, one at the edge. uDepth sets how wide the band is.\n"

            + "    float edge = smoothStep(uDepth, 0.0, d);\n"

            + "    // Scale below one pulls the sample towards the centre, which magnifies.\n"

            + "    float scale = mix(1.0, uStrength, edge);\n"

            + "    float2 offset = uOffset + halfSize + p * scale;\n"

            + "    float ca = uAberration * edge;\n"

            + "    float4 col;\n"

            + "    col.r = uBackdrop.eval(offset * (1.0 + ca)).r;\n"

            + "    col.g = uBackdrop.eval(offset).g;\n"

            + "    col.b = uBackdrop.eval(offset * (1.0 - ca)).b;\n"

            + "    col.a = 1.0;\n"

            + "    // Lifting the whole pane rather than drawing a rim: a lit band around the\n"

            + "    // border is what made the first attempt look like painted plastic.\n"

            + "    col.rgb = col.rgb * uExposure;\n"

            + "    col.rgb = mix(col.rgb, uTintColor, uTint * edge);\n"

            + "    return half4(col);\n"

            + "}\n";

    private static Paint sharedPaint;
    private static RuntimeShader sharedShader;
    private static boolean shaderUnusable;

    private GlassPainter() {
    }

    /** Resets the cached shader. Called when the style changes. */
    public static void resetShader() {
        sharedShader = null;
        shaderUnusable = false;
    }

    /**
     * Paints a pane into {@code bounds}, in the view coordinate space.
     *
     * @param host the view the bounds belong to, used to work out where on
     *             screen the pane sits. May be null, in which case the pane is
     *             drawn from the top left of the backdrop.
     * @return false when there is no backdrop yet.
     */
    public static boolean paint(Canvas canvas, Rect bounds, View host, float radiusPx,
                                float thicknessPx, int tintColor, float tintAmount) {
        Bitmap backdrop = Backdrop.blurred();
        if (backdrop == null || bounds.width() <= 0 || bounds.height() <= 0) {
            return false;
        }

        float scale = Backdrop.captureScale();
        float offsetX = 0f;
        float offsetY = 0f;
        if (host != null) {
            int[] location = new int[2];
            host.getLocationInWindow(location);
            offsetX = (location[0] - Backdrop.originX()) * scale;
            offsetY = (location[1] - Backdrop.originY()) * scale;
        }

        if (sharedPaint == null) {
            sharedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        Paint paint = sharedPaint;
        paint.setShader(null);
        paint.setAlpha(255);

        RectF oval = new RectF(bounds);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !shaderUnusable) {
            RuntimeShader shader = obtainShader(backdrop);
            if (shader != null) {
                float minSide = Math.min(oval.width(), oval.height());
                shader.setFloatUniform("uOffset", offsetX, offsetY);
                shader.setFloatUniform("uSize", oval.width(), oval.height());
                // How far in from the edge the glass starts bending, and how
                // much it magnifies there. Ported from the reference
                // implementation, where the same two numbers are expressed as
                // a fraction of the shape.
                shader.setFloatUniform("uDepth", minSide * 0.33f);
                shader.setFloatUniform("uStrength", 0.86f);
                shader.setFloatUniform("uAberration", 0.006f);
                shader.setFloatUniform("uExposure", 1.06f);
                shader.setFloatUniform("uTint", 0.05f);
                shader.setFloatUniform("uTintColor",
                        ((tintColor >> 16) & 0xFF) / 255f,
                        ((tintColor >> 8) & 0xFF) / 255f,
                        (tintColor & 0xFF) / 255f);
                paint.setShader(shader);
            }
        }

        if (paint.getShader() == null) {
            BitmapShader sample = new BitmapShader(
                    backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix matrix = new Matrix();
            matrix.setScale(1f / scale, 1f / scale);
            matrix.postTranslate(-offsetX / scale, -offsetY / scale);
            sample.setLocalMatrix(matrix);
            paint.setShader(sample);
            paint.setAlpha(235);
        }

        canvas.save();
        Path outline = new Path();
        outline.addRoundRect(oval, radiusPx, radiusPx, Path.Direction.CW);
        canvas.clipPath(outline);
        canvas.drawRect(oval, paint);
        canvas.restore();
        return true;
    }

    private static RuntimeShader obtainShader(Bitmap backdrop) {
        try {
            if (sharedShader == null) {
                RuntimeShader created = new RuntimeShader(AGSL);
                created.setInputBuffer("uBackdrop",
                        new BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                sharedShader = created;
            }
            return sharedShader;
        } catch (Throwable t) {
            // The program is compiled by the driver on first use. A driver that
            // rejects it must not take the whole interface down with it.
            shaderUnusable = true;
            sharedShader = null;
            return null;
        }
    }
}
