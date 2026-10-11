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
     * Samples the backdrop with a rounded rectangle distance field.
     *
     * <p>Takes the gradient of the field as an outward normal and pulls the
     * sample coordinate inward by an amount that grows towards the edge, so the
     * border bends light the way a real pane does.
     */
    private static final String AGSL =
            "uniform shader uBackdrop;\n"

            + "uniform float2 uOffset;\n"

            + "uniform float2 uSize;\n"

            + "uniform float uBand;\n"

            + "uniform float uStrength;\n"

            + "uniform float uTint;\n"

            + "uniform float3 uTintColor;\n"

            + "half4 main(float2 coord) {\n"

            + "    float2 halfSize = uSize * 0.5;\n"

            + "    float2 p = coord - halfSize;\n"

            + "    float2 n = p / halfSize;\n"

            + "    float r = length(n);\n"

            + "    float2 dir = normalize(p + float2(0.0001, 0.0001));\n"

            + "    float band = smoothstep(1.0 - uBand, 1.0, r);\n"

            + "    float2 disp = dir * band * uStrength;\n"

            + "    float4 col;\n"

            + "    col.r = uBackdrop.eval(uOffset + coord + disp * 1.00).r;\n"

            + "    col.g = uBackdrop.eval(uOffset + coord + disp * 0.97).g;\n"

            + "    col.b = uBackdrop.eval(uOffset + coord + disp * 0.94).b;\n"

            + "    col.a = 1.0;\n"

            + "    col.rgb = mix(col.rgb, uTintColor, uTint);\n"

            + "    col.rgb += float3(1.0, 1.0, 1.0) * smoothstep(0.55, 1.0, band) * 0.22;\n"

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
                shader.setFloatUniform("uOffset", offsetX, offsetY);
                shader.setFloatUniform("uSize", oval.width(), oval.height());
                // Refraction lives in a band just inside the edge. Past that
                // the pane is a plain window, which is what makes real liquid
                // glass look clear in the middle instead of fogged all over.
                shader.setFloatUniform("uBand", 0.42f);
                shader.setFloatUniform("uStrength",
                        Math.min(oval.width(), oval.height()) * 0.07f);
                shader.setFloatUniform("uTint", tintAmount);
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
