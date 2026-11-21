package fi.alavesa.intercom.dialog;

import fi.alavesa.intercom.text.FontWidths;
import fi.alavesa.intercom.text.Spaces;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * The custom-textured look of the compose dialog, for players who have the pack.
 * <p>
 * Technique: font glyphs, not GUI sprite overrides. Vanilla draws every button of the game from the same
 * three sprites (widget/button, _highlighted, _disabled), so overriding those would re-skin every menu. Instead
 * the textures are glyphs of the pack font intercom:ui, placed with cursor-moving spaces inside text the
 * dialog shows anyway:
 * <ul>
 *   <li>a button's label = [button texture glyph, pulled left so it covers the button face] + the label text
 *       on top. The glyph leaves the button's outermost pixel free, which is where vanilla draws the white
 *       frame of the hovered / focused button - so "hovered" still reads. A pressed or disabled state does not
 *       exist for dialog buttons in vanilla;</li>
 *   <li>the dialog background = one tall glyph at the start of the hint line; it hangs down behind the text
 *       box (glyphs are drawn in text order, and the text box is drawn after the hint).</li>
 * </ul>
 * Glyphs are drawn untinted (white) and without shadow - a shadow would be a second, dark copy of the texture.
 * The sizes here match font/ui.json and tools/gen_placeholders.py.
 */
public final class DialogSkin {

    public static final Key FONT = Key.key("intercom", "ui");
    private static final Key DEFAULT_FONT = Key.key("minecraft", "default");

    /** Dialog button width; the texture glyph is 2 px narrower (1 px of the vanilla frame stays visible). */
    public static final int BUTTON_WIDTH = 110;
    private static final int BUTTON_GLYPH_WIDTH = 108;
    /** Width of the panel texture, and of the text box that sits inside it. */
    public static final int PANEL_WIDTH = 240, INPUT_WIDTH = 220;
    /** The dialog's text widget wraps at its width - 8; the hint line is the panel's advance (width + 1) wide. */
    public static final int BODY_WIDTH = PANEL_WIDTH + 10;
    private static final int PANEL_MARGIN = 6;

    public static final char GLYPH_SEND = '\uE001', GLYPH_CANCEL = '\uE002';
    private static final char GLYPH_PANEL = '\uE003';

    private static final Style GLYPH = Style.style().font(FONT).color(NamedTextColor.WHITE).shadowColor(ShadowColor.none()).build();

    private final FontWidths font;

    public DialogSkin(FontWidths font) { this.font = font; }

    private int width(Component text) {
        return font.width(PlainTextComponentSerializer.plainText().serialize(text));
    }

    /**
     * A button label with a texture behind it. The label's total advance stays the width of its text, so the
     * client centres it as usual; the glyph is drawn half a button to the left of the text's centre.
     */
    public Component buttonLabel(char glyph, Component label) {
        int textWidth = width(label);
        int pad = textWidth % 2; // an even width centres on a whole pixel
        int glyphLeft = (textWidth + pad) / 2 - BUTTON_GLYPH_WIDTH / 2;
        int glyphAdvance = BUTTON_GLYPH_WIDTH + 1;
        return Component.text()
            .append(Component.text(Spaces.shift(glyphLeft) + glyph + Spaces.shift(-glyphLeft - glyphAdvance), GLYPH))
            .append(label.font(DEFAULT_FONT))
            .append(Component.text(Spaces.shift(pad), GLYPH))
            .build();
    }

    /**
     * The hint line with the panel behind it. The line starts at the panel's left edge and is exactly as wide
     * as the panel's advance: the dialog's text widget counts lines by measuring from the line start, and a
     * cursor that ever went further right than the finished line would make it reserve an empty second line.
     *
     * @return null when the hint is too wide for the panel (the caller then shows the plain hint)
     */
    public Component body(Component hint) {
        int advance = PANEL_WIDTH + 1;
        int hintWidth = width(hint);
        // a hint that does not fit one line would wrap and push the panel out of place
        if (hintWidth > PANEL_WIDTH - 2 * PANEL_MARGIN) return null;
        int hintLeft = (PANEL_WIDTH - hintWidth) / 2;
        return Component.text()
            .append(Component.text(GLYPH_PANEL + Spaces.shift(hintLeft - advance), GLYPH))
            .append(hint.font(DEFAULT_FONT))
            .append(Component.text(Spaces.shift(advance - hintLeft - hintWidth), GLYPH))
            .build();
    }
}
