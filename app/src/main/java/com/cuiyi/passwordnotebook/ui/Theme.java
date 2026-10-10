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

    // ---- interface styles ----
    /**
     * Three looks, chosen in settings.
     *
     * CYBER is the original: rain, neon edges, translucent panels.
     * MINIMAL drops the animation and makes the panels almost opaque, which
     * is both the calmest to read and the cheapest to draw.
     * GLASS paints each panel from a blurred capture of the backdrop behind
     * it, so the rain shows through the way it would through a real pane.
     */
    public static final int STYLE_CYBER = 0;
    public static final int STYLE_MINIMAL = 1;
    public static final int STYLE_GLASS = 2;
    public static final int STYLE_DEFAULT = STYLE_CYBER;

    /** Nearly opaque: the minimal style wants legibility, not depth. */
    public static final int SURFACE_MINIMAL = 0xF20C1218;

    /** Glass: how far the blur reaches, and how much colour is mixed in. */
    public static final int GLASS_BLUR_DP = 18;
    public static final int GLASS_THICKNESS_DP = 16;
    public static final float GLASS_TINT = 0.12f;
    public static final int GLASS_TINT_COLOR = 0xFF7FFFD8;

    // ---- glassmorphism ----

    /**
     * Real frosted glass, as opposed to the flat translucent panel this used to
     * be.
     *
     * A glass card is three things together: a fill transparent enough to let
     * the background through, a light one pixel edge standing in for the
     * highlight a real pane catches, and a blur of whatever sits behind it.
     * The first two only look right with the third; without the blur the card
     * reads as a plain coloured rectangle, which is exactly what the old
     * SURFACE value produced.
     */
    public static final int GLASS_FILL = 0x1F8FFFE0;
    /** Toward white, not toward the accent: glass edges bend light neutrally. */
    public static final int GLASS_STROKE = 0x4DFFFFFF;
    public static final int GLASS_STROKE_WIDTH = 1;

    /**
     * Blur radii, in dp.
     *
     * Each blurred card costs a GPU pass, so these stay modest. The values are
     * far enough apart that the three settings are tellable apart at a glance
     * without the heaviest one turning scrolling into a slideshow.
     */
    public static final int GLASS_BLUR_LIGHT = 10;
    public static final int GLASS_BLUR_MEDIUM = 20;
    public static final int GLASS_BLUR_HEAVY = 34;

    /** Index of the middle setting, used as the default. */
    public static final int GLASS_BLUR_DEFAULT = 1;

    public static final int RADIUS_CARD = 16;
    public static final int RADIUS_FIELD = 10;
    public static final int STROKE_WIDTH = 2;

    // ---- tag chips ----

    /**
     * Tag pill colours.
     *
     * The tag used to be an unadorned line of text, which read as stray body
     * copy rather than as a label, and gave no hint that it could be tapped to
     * narrow the list. A filled pill with a border marks it as an object.
     */
    public static final int TAG_FILL = 0x2E00FF88;
    /** Active filter pill: brighter fill, solid border. */
    public static final int TAG_FILL_ACTIVE = 0x6600FF88;
    /** Border of an inactive pill. */
    public static final int TAG_STROKE = 0x5510C060;
    public static final int RADIUS_CHIP = 999;
    public static final int CHIP_PAD_H = 12;
    public static final int CHIP_PAD_V = 5;
    public static final int CHIP_GAP = 6;

    /**
     * How many tags the record filter bar shows before it stops.
     *
     * The bar scrolls, so the cap is not about fitting the row. It is about
     * keeping the strip scannable: past roughly a dozen pills the eye stops
     * reading it and starts hunting, at which point the search box is the
     * better tool. Tags beyond the cap are still reachable by tapping the tag
     * on a record that carries it.
     */
    public static final int MAX_TAG_CHIPS = 12;

    /** Suggestions offered under the tag field when saving or editing. */
    public static final int MAX_TAG_SUGGESTIONS = 8;

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

    /** Delay before a periodic derivation is started, per keystroke. */
    public static final long GENERATE_DEBOUNCE_MS = 350L;

    // ---- layout scale ----

    /**
     * The four spacing steps everything is built from.
     *
     * Before this the file used seventeen distinct dp values (4, 6, 7, 8, 9,
     * 10, 11, 12, 14, 18, 20 ...) with no rule behind any of them. The damage
     * was not the count but the collisions: stacked cards elsewhere used 12 and
     * record cards used 14, so the same kind of thing sat at two different
     * indents on two screens. Restricting the vocabulary makes alignment a
     * property of the scale instead of something to remember at each call site.
     */
    public static final int SPACE_XS = 4;
    public static final int SPACE_S = 8;
    public static final int SPACE_M = 12;
    public static final int SPACE_L = 16;

    /**
     * Horizontal margin of the scrolling content from the screen edge.
     *
     * Paired with PAD_CARD / PAD_FIELD: text inside a card and text inside an
     * input both land at PAD_SCREEN + PAD_CARD, so the page has one left edge
     * instead of the two it had when cards used 14 and fields used 12.
     */
    public static final int PAD_SCREEN_H = 14;
    public static final int PAD_SCREEN_TOP = 10;
    public static final int PAD_SCREEN_BOTTOM = 24;

    /** Inside a card. Equal to PAD_FIELD on purpose: see PAD_SCREEN_H. */
    public static final int PAD_CARD_H = 14;
    public static final int PAD_CARD_V = 12;

    /** Inside an input field. Equal to PAD_CARD_H on purpose. */
    public static final int PAD_FIELD_H = 14;
    public static final int PAD_FIELD_V = 11;

    /**
     * Wrap on a wide screen.
     *
     * A phone never hits this. A tablet or an unfolded foldable would otherwise
     * stretch a line of body text across 900dp, which is past the point where
     * the eye can track from the end of one line to the start of the next.
     */
    public static final int MAX_CONTENT_WIDTH_DP = 560;

    /** Ceiling for the password box once the system font scale is applied. */
    public static final int PASSWORD_BOX_MAX_DP = 220;

    /**
     * Gap between side by side controls.
     *
     * Equal weight buttons otherwise expand to touch each other, so the glass
     * cards merge into one slab of border and the row stops reading as separate
     * targets. Applied as a half gap on every child so the outer edges stay
     * flush with the content margins.
     */
    public static final int SPACE_INLINE = 10;

    /** Vertical gap between stacked controls. */
    public static final int SPACE_BLOCK = 10;

    /** Shown while the periodic derivation runs off the main thread. */
    public static final String GENERATING_LABEL = "生成中";

    public static int dp(android.content.Context ctx, float value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
