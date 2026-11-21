package fi.alavesa.intercom.banner;

import fi.alavesa.intercom.text.FontWidths;
import fi.alavesa.intercom.text.Line;
import fi.alavesa.intercom.text.Spaces;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.List;

/**
 * Lays a whole banner out inside ONE text component - the name of a boss bar. Pure: no Bukkit.
 * <p>
 * How it works (font intercom:banner; shaders/include/intercom_*.glsl in the pack):
 * <ul>
 *   <li>the font is Minecraft's default bitmap font plus signed spaces of +-2^n px, so the pen can be put
 *       anywhere, and one background glyph;</li>
 *   <li>the component always ends with the pen back at 0: its total width is 0, so the client's centring puts
 *       pen 0 at the screen centre. Every line starts at minus half its own width - it is centred - and the
 *       text shader keeps each glyph's x and replaces its y;</li>
 *   <li>what the shader needs to know rides in the glyph's SHADOW colour, because a shadow colour has four
 *       free bytes (alpha too) where a text colour has three. The shadow copy of every glyph is the one the
 *       shader draws; the glyph itself gets the fixed colour {@link #HIDDEN} and is thrown away. Bold,
 *       italic, underline and strike-through are the client's own and need no bits.</li>
 * </ul>
 * The marker layout is documented in the pack's intercom_marker.glsl; the constants here are its Java half.
 */
public final class BannerText {

    public static final Key FONT = Key.key("intercom", "banner");

    /** Colour of the glyphs themselves: the shader removes exactly this colour from GUI text. */
    static final TextColor HIDDEN = TextColor.color(249, 28, 10);
    /** Marker: shadow red, and the high nibble of shadow alpha (the low nibble is the colour index). */
    public static final int MARKER_RED = 250, MARKER_ALPHA = 0xE0;
    /** Marker: green bits 7-6. */
    public static final int PHASE_HOLD = 0, PHASE_ENTER = 1, PHASE_EXIT = 2, PHASE_STEP = 3;
    /** Marker: green bits 5-0 hold a time or a step in 0..63. */
    public static final int TIME_MASK = 63;
    /** Marker: blue bit 7 = the background glyph (bits 6-0 = banner height / 2); else bits 6-1 = row y / 2, bit 0 = large. */
    private static final int BLUE_BOX = 128, BLUE_LARGE = 1;

    /** Vertical layout, GUI pixels. All even: positions travel as y / 2. */
    public static final int PAD_Y = 6, LINE_HEIGHT = 10, SENDER_GAP = 4;
    /** The tallest banner the marker can describe (row y is 6 bits of 2 px). */
    public static final int MAX_HEIGHT = 126;

    private static final char CHAR_BACKGROUND = '';
    /** Advance of the background glyph: its 8 px bitmap plus the 1 px every bitmap glyph gets. */
    static final int BACKGROUND_ADVANCE = 9;

    private BannerText() { }

    /** Height of one row: a large line is drawn at double size. */
    public static int rowHeight(Line line) { return line.large() ? 2 * LINE_HEIGHT : LINE_HEIGHT; }

    /** Height of the whole banner for these rows. */
    public static int height(List<Line> lines, Line sender) {
        int height = 2 * PAD_Y - 2;
        for (Line line : lines) height += rowHeight(line);
        if (sender != null) height += SENDER_GAP + LINE_HEIGHT;
        return height;
    }

    /**
     * @param lines  the message, top line first; lines that fit the banner (see LineWrapper)
     * @param sender the line under the message, or null for none
     * @param phase  one of the PHASE_ constants
     * @param time   ENTER/EXIT: the world's game time when the slide started (any long; only its low 6 bits
     *               are sent); STEP: shown amount 0-63; HOLD: ignored
     */
    public static Component build(FontWidths font, List<Line> lines, Line sender, int phase, long time) {
        int height = height(lines, sender);
        if (lines.isEmpty() || height > MAX_HEIGHT) throw new IllegalArgumentException("banner of " + lines.size() + " lines does not fit");
        int green = (phase << 6) | (phase == PHASE_HOLD ? 0 : (int) (time & TIME_MASK));

        TextComponent.Builder out = Component.text();
        int pen = 0;

        // the box: the shader ignores where this glyph is and stretches it over the whole banner
        out.append(Component.text(String.valueOf(CHAR_BACKGROUND), style(green, BLUE_BOX | (height / 2), Line.WHITE)));
        pen += BACKGROUND_ADVANCE;

        int y = PAD_Y;
        for (int row = 0; row <= lines.size(); row++) {
            Line line = row < lines.size() ? lines.get(row) : sender;
            if (line == null) break;
            if (row == lines.size()) y += SENDER_GAP;
            int blue = ((y / 2) << 1) | (line.large() ? BLUE_LARGE : 0);
            // centred at normal size; the shader doubles a large line about the screen centre
            int x = -(line.width(font) / 2);
            for (int from = 0; from < line.length(); ) {
                int to = line.runEnd(from);
                int runStyle = line.styles()[from];
                String text = new String(line.codePoints(), from, to - from);
                Style style = style(green, blue, runStyle & Line.COLOR_MASK)
                    .decoration(TextDecoration.BOLD, (runStyle & Line.BOLD) != 0)
                    .decoration(TextDecoration.ITALIC, (runStyle & Line.ITALIC) != 0)
                    .decoration(TextDecoration.UNDERLINED, (runStyle & Line.UNDERLINE) != 0)
                    .decoration(TextDecoration.STRIKETHROUGH, (runStyle & Line.STRIKE) != 0);
                // the pen move is its own unstyled piece: an underlined or bold space would be drawn / mis-measured
                if (x != pen) out.append(Component.text(Spaces.shift(x - pen)).font(FONT));
                out.append(Component.text(text, style));
                for (int i = from; i < to; i++) x += line.width(font, i);
                pen = x;
                from = to;
            }
            y += rowHeight(line);
        }
        // back to pen 0: the whole component is then 0 px wide (see the class comment)
        out.append(Component.text(Spaces.shift(-pen)).font(FONT));
        return out.build();
    }

    private static Style style(int green, int blue, int color) {
        return Style.style().font(FONT).color(HIDDEN)
            .shadowColor(ShadowColor.shadowColor(MARKER_RED, green, blue, MARKER_ALPHA | color)).build();
    }
}
