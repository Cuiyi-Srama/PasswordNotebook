package com.cuiyi.passwordnotebook.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.widget.LinearLayout;

/**
 * A card that draws the blurred backdrop behind itself, the way a real pane of
 * glass would.
 *
 * <p>Android offers no way to blur what is painted behind a view, so the pane
 * paints the backdrop itself, clipped to its own rounded outline, and bends the
 * sample coordinates near the edges to imitate refraction. The content views
 * added to this card are drawn on top afterwards by the normal view pipeline.
 *
 * <p>Two paths exist. On API 33 and up an AGSL shader does the refraction. If
 * the shader is rejected by the driver the card falls back to a plain blurred
 * sample, and if there is no backdrop at all it falls back to a flat fill, so a
 * failure anywhere degrades the look but never breaks the screen.
 */
public class GlassCard extends LinearLayout {

    /**
     * Samples the backdrop with a rounded rectangle distance field.
     *
     * Takes the gradient of the field as an outward normal and pulls the sample
     * coordinate inward by an amount that grows towards the edge, so the border
     * bends light the way a real pane does. That bend is the one detail that
     * separates glass from a translucent rectangle.
     */
    private static final String AGSL =
            "uniform shader uBackdrop;\n"

            + "uniform float2 uOffset;\n"

            + "uniform float2 uSize;\n"

            + "uniform float uRadius;\n"

            + "uniform float uThickness;\n"

            + "uniform float uTint;\n"

            + "uniform float3 uTintColor;\n"

            + "float sdRoundRect(float2 p, float2 b, float r) {\n"

            + "    float2 q = abs(p) - b + r;\n"

            + "    return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;\n"

            + "}\n"

            + "half4 main(float2 coord) {\n"

            + "    float2 halfSize = uSize * 0.5;\n"

            + "    float2 p = coord - halfSize;\n"

            + "    float d = sdRoundRect(p, halfSize, uRadius);\n"

            + "    float dx = sdRoundRect(p + float2(1.0, 0.0), halfSize, uRadius) - d;\n"

            + "    float dy = sdRoundRect(p + float2(0.0, 1.0), halfSize, uRadius) - d;\n"

            + "    float2 n = normalize(float2(dx, dy) + float2(0.0001, 0.0001));\n"

            + "    float band = 1.0 - smoothstep(0.0, uThickness, -d);\n"

            + "    float2 offset = n * band * uThickness * 0.45;\n"

            + "    float4 col = float4(uBackdrop.eval(uOffset + coord + offset));\n"

            + "    col.rgb = mix(col.rgb, uTintColor, uTint);\n"

            + "    float rim = (1.0 - smoothstep(0.0, uThickness * 0.55, -d)) * step(-uThickness, d);\n"

            + "    col.rgb += float3(1.0, 1.0, 1.0) * rim * 0.20;\n"

            + "    col.a = 1.0;\n"

            + "    return half4(col);\n"

            + "}\n";

    private static Paint sharedPaint;
    private static RuntimeShader sharedShader;
    private static boolean shaderUnusable;

    private final float radiusDp;
    private final float thicknessDp;
    private final float tintAmount;
    private final float tintR;
    private final float tintG;
    private final float tintB;

    public GlassCard(Context context, float radiusDp, float thicknessDp,
                     int tintColor, float tintAmount) {
        super(context);
        this.radiusDp = radiusDp;
        this.thicknessDp = thicknessDp;
        this.tintAmount = tintAmount;
        this.tintR = ((tintColor >> 16) & 0xFF) / 255f;
        this.tintG = ((tintColor >> 8) & 0xFF) / 255f;
        this.tintB = (tintColor & 0xFF) / 255f;
        setWillNotDraw(false);
    }

    /** Resets the cached shader. Called when the style changes. */
    public static void resetShader() {
        sharedShader = null;
        shaderUnusable = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!paintGlass(canvas)) {
            // No backdrop yet, or the first layout pass. A flat fill keeps the
            // card readable rather than leaving a hole.
            Paint fallback = new Paint(Paint.ANTI_ALIAS_FLAG);
            fallback.setColor(Theme.SURFACE);
            canvas.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()),
                    dp(radiusDp), dp(radiusDp), fallback);
        }
        super.onDraw(canvas);
    }

    /** @return true when the glass pane was painted. */
    private boolean paintGlass(Canvas canvas) {
        Bitmap backdrop = Backdrop.blurred();
        int width = getWidth();
        int height = getHeight();
        if (backdrop == null || width <= 0 || height <= 0) {
            return false;
        }

        int[] location = new int[2];
        getLocationInWindow(location);
        float offsetX = location[0] - Backdrop.originX();
        float offsetY = location[1] - Backdrop.originY();

        if (sharedPaint == null) {
            sharedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        Paint paint = sharedPaint;
        paint.setShader(null);
        paint.setAlpha(255);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !shaderUnusable) {
            RuntimeShader shader = obtainShader(backdrop);
            if (shader != null) {
                shader.setFloatUniform("uOffset", offsetX, offsetY);
                shader.setFloatUniform("uSize", (float) width, (float) height);
                shader.setFloatUniform("uRadius", dp(radiusDp));
                shader.setFloatUniform("uThickness", dp(thicknessDp));
                shader.setFloatUniform("uTint", tintAmount);
                shader.setFloatUniform("uTintColor", tintR, tintG, tintB);
                paint.setShader(shader);
            }
        }

        if (paint.getShader() == null) {
            // Fallback: plain blurred sample, no refraction.
            BitmapShader sample = new BitmapShader(
                    backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix matrix = new Matrix();
            matrix.setTranslate(-offsetX, -offsetY);
            sample.setLocalMatrix(matrix);
            paint.setShader(sample);
            paint.setAlpha(235);
        }

        canvas.save();
        Path outline = new Path();
        outline.addRoundRect(new RectF(0, 0, width, height),
                dp(radiusDp), dp(radiusDp), Path.Direction.CW);
        canvas.clipPath(outline);
        canvas.drawRect(0f, 0f, (float) width, (float) height, paint);
        canvas.restore();
        return true;
    }

    private RuntimeShader obtainShader(Bitmap backdrop) {
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

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density + 0.5f;
    }
}
