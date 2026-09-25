package com.cuiyi.passwordnotebook.ui;

/**
 * Central place for every colour and dimension used by the UI.
 *
 * The previous build hard-coded these values at each call site (as raw ints
 * such as 0xFF0A0A14), which made the theme impossible to adjust and hid the
 * intent of each number. Grouping them here keeps the drawing code readable
 * and makes it obvious when two colours are meant to match.
 */
public final class Theme {

    private Theme() {
        throw new AssertionError("no instance");
    }

    // ---- background ----

    /** Base canvas colour behind the rain. */
    public static final int BG_DEEP = 0xFF04070C;
    /** The rain overlay tint, drawn each frame before the glyphs. */
    public static final int RAIN_BASE = 0xFF04A0F2;
    /** Horizontal scanline colour, low alpha. */
    public static final int SCANLINE = 0x1100FF88;

    /**
     * Scrim drawn between the rain and the interface.
     *
     * The rain is deliberately high contrast, which makes it unreadable as a
     * backdrop for text. This layer sits on top of it and pulls the contrast
     * down without hiding the animation entirely.
     */
    public static final int SCRIM = 0xD904070C;
    /** Extra scrim under dense areas such as list rows. */
    public static final int SCRIM_STRONG = 0xE604070C;

    // ---- cyber rain palette ----

    /** Leading glyph. Bright mint green. */
    public static final int RAIN_HEAD = 0xFF00FF88;
    /** Head glow colour. */
    public static final int RAIN_GLOW = 0xCC00FF88;
    /** Trail base colour, modulated per glyph by the caller. */
    public static final int RAIN_TRAIL_R = 15;
    public static final int RAIN_TRAIL_G = 240;
    public static final int RAIN_TRAIL_B = 30;
    /** Maximum alpha for the first trailing glyph. */
    public static final int RAIN_TRAIL_ALPHA = 200;
    /** Floor so the tail never disappears entirely. */
    public static final float RAIN_TRAIL_MIN = 0.05f;

    public static final float RAIN_CHAR_SIZE = 56f;
    public static final float RAIN_COL_GAP = 26f;
    public static final int RAIN_SPEED_MIN = 800;
    public static final int RAIN_SPEED_MAX = 1600;
    public static final int RAIN_TAIL_MIN = 12;
    public static final int RAIN_TAIL_MAX = 37;
    public static final int RAIN_RESPAWN_ABOVE = 80;
    /** Longest frame delta we will honour, to avoid a jump after a pause. */
    public static final float RAIN_MAX_DELTA = 0.1f;
    /** Scroll speed of the scanlines, px per second. */
    public static final float SCANLINE_SPEED = 4f;

    // ---- surfaces ----

    /** Translucent card behind content. */
    public static final int SURFACE = 0x661A3A2A;
    /** Slightly stronger card used for inputs. */
    public static final int SURFACE_STRONG = 0xCC0A1220;
    /** Inner field colour. */
    public static final int FIELD = 0x441A3A2A;
    /** Border of glass cards. */
    public static final int SURFACE_STROKE = 0x4480FFA0;

    public static final int RADIUS_CARD = 16;
    public static final int RADIUS_FIELD = 10;
    public static final int STROKE_WIDTH = 2;

    // ---- text ----

    public static final int TEXT_PRIMARY = 0xFFD0FFD0;
    public static final int TEXT_SECONDARY = 0x88A0FFA0;
    public static final int TEXT_ACCENT = 0xFF00FF88;
    public static final int TEXT_DANGER = 0xFFFFA0A0;
    public static final int TEXT_MUTED = 0xFF6A8A7A;

    public static final float SIZE_TITLE = 17f;
    public static final float SIZE_BODY = 14f;
    public static final float SIZE_SMALL = 12f;
    public static final float SIZE_TINY = 11f;
    public static final float SIZE_PASSWORD = 32f;

    /**
     * Height reserved for the generated password. Fixed on purpose: the block
     * used to grow with the text, so a long password pushed every control below
     * it down and the whole page jumped on each regeneration. Sized for two
     * lines, which covers the default 16 characters at full size.
     */
    public static final int PASSWORD_BOX_DP = 108;

    /** Smallest size the password shrinks to before it starts truncating. */
    public static final float SIZE_PASSWORD_MIN = 17f;

    /** Longest password shown at full size before the type shrinks. */
    public static final int PASSWORD_FULL_FIT = 18;

    // ---- controls ----

    public static final int BUTTON_BG = 0xFF1A3A2A;
    public static final int BUTTON_BG_DANGER = 0xFF3A1A1A;
    public static final int BUTTON_PRIMARY = 0xFF1A5A3A;

    // ---- animation ----

    /** Press-in scale factor. */
    public static final float PRESS_IN = 0.92f;
    /** Overshoot scale after release. */
    public static final float RELEASE_OUT = 1.04f;
    public static final long PRESS_IN_MS = 120L;
    public static final long RELEASE_OUT_MS = 200L;
    public static final float PRESS_TENSION = 3.0f;
    public static final float RELEASE_TENSION = 2.0f;

    /** Delay between characters in the typing animation. */
    public static final long TYPING_STEP_MS = 35L;
    /** Scale pulse applied to each newly typed character. */
    public static final float TYPING_PULSE = 1.03f;
    public static final long TYPING_PULSE_MS = 60L;

    /** Debounce before running a search. */
    public static final long SEARCH_DEBOUNCE_MS = 220L;

    public static int dp(android.content.Context ctx, float value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
