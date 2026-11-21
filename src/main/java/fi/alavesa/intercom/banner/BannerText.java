package fi.alavesa.intercom.banner;

import fi.alavesa.intercom.text.FontWidths;
import fi.alavesa.intercom.text.Spaces;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;

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
 *       text shader keeps each glyph's x and replaces its y by the row the marker names;</li>
 *   <li>the text colour is the marker. Red 250 says "banner"; green and blue carry the animation phase, its
 *       time, the row and the banner size. Shadows are off, or a dark copy of every glyph would be drawn too.</li>
 * </ul>
 * The marker layout is documented in the pack's intercom_marker.glsl; the constants here are its Java half.
 */
public final class BannerText {

    public static final Key FONT = Key.key("intercom", "banner");

    /** Marker: red. */
    public static final int MARKER_RED = 250;
    /** Marker: green bits 7-6. */
    public static final int PHASE_HOLD = 0, PHASE_ENTER = 1, PHASE_EXIT = 2, PHASE_STEP = 3;
    /** Marker: green bits 5-0 hold a time or a step in 0..63. */
    public static final int TIME_MASK = 63;
    /** Marker: blue bits 2-0 for the background glyph (rows use 0-4). */
    private static final int KIND_BOX = 7;
    private static final int FLAG_SENDER_ROW = 8, FLAG_HAS_SENDER = 128;

    private static final char CHAR_BACKGROUND = '\uE020';
    /** Advance of the background glyph: its 8 px bitmap plus the 1 px every bitmap glyph gets. */
    static final int BACKGROUND_ADVANCE = 9;

    private BannerText() { }

    /**
     * @param lines  the message, top line first: 1-4 lines that fit the banner (see LineWrapper)
     * @param sender the line under the message, or null for none
     * @param phase  one of the PHASE_ constants
     * @param time   ENTER/EXIT: the world's game time when the slide started (any long; only its low 6 bits
     *               are sent); STEP: shown amount 0-63; HOLD: ignored
     */
    public static Component build(FontWidths font, List<String> lines, String sender, int phase, long time) {
        int rows = lines.size() + (sender == null ? 0 : 1);
        if (lines.isEmpty() || rows > 5) throw new IllegalArgumentException("a banner has 1-4 lines and an optional sender line");
        int green = (phase << 6) | (phase == PHASE_HOLD ? 0 : (int) (time & TIME_MASK));
        int blueBase = ((rows - 1) << 4) | (sender == null ? 0 : FLAG_HAS_SENDER);

        TextComponent.Builder out = Component.text();
        int pen = 0;

        // the box: the shader ignores where this glyph is and stretches it over the whole banner
        out.append(run(String.valueOf(CHAR_BACKGROUND), green, blueBase | KIND_BOX));
        pen += BACKGROUND_ADVANCE;

        for (int row = 0; row < rows; row++) {
            boolean senderRow = row == lines.size();
            String text = senderRow ? sender : lines.get(row);
            if (text.isBlank()) continue;
            int width = font.width(text);
            int x = -(width / 2);
            out.append(run(Spaces.shift(x - pen) + text, green, blueBase | row | (senderRow ? FLAG_SENDER_ROW : 0)));
            pen = x + width;
        }
        // back to pen 0: the whole component is then 0 px wide (see the class comment)
        out.append(Component.text(Spaces.shift(-pen)).font(FONT));
        return out.build();
    }

    private static Component run(String text, int green, int blue) {
        return Component.text(text, TextColor.color(MARKER_RED, green, blue)).font(FONT).shadowColor(ShadowColor.none());
    }
}
