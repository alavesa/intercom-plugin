package fi.alavesa.intercom;

import fi.alavesa.intercom.banner.BannerText;
import fi.alavesa.intercom.text.FontWidths;
import fi.alavesa.intercom.text.Line;
import fi.alavesa.intercom.text.Markup;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BannerTextTest {

    private final FontWidths font = Fonts.widths();

    private static Line line(String text) { return Markup.parse(text, Line.WHITE); }

    /** Pen movement of the whole component, as the client adds it up (bold glyphs are a pixel wider). */
    private int width(Component component, boolean inheritedBold) {
        boolean bold = component.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE
            || (component.decoration(TextDecoration.BOLD) == TextDecoration.State.NOT_SET && inheritedBold);
        int width = 0;
        if (component instanceof TextComponent text) {
            for (char ch : text.content().toCharArray()) {
                if (ch >= 0xF000 && ch < 0xF00A) width += 1 << (ch - 0xF000);
                else if (ch >= 0xF100 && ch < 0xF10A) width -= 1 << (ch - 0xF100);
                else if (ch == 0xE020) width += 9;
                else width += font.width(ch) + (bold ? 1 : 0);
            }
        }
        for (Component child : component.children()) width += width(child, bold);
        return width;
    }

    private static void shadows(Component component, List<ShadowColor> out) {
        if (component.shadowColor() != null) out.add(component.shadowColor());
        for (Component child : component.children()) shadows(child, out);
    }

    @Test
    void everyBannerIsZeroWide() {
        List<Line> lines = List.of(line("# ALERT"), line("**Containment breach** in &cSector 4&r."), line("Älä *hätäile*"));
        for (int phase = 0; phase < 4; phase++) {
            assertEquals(0, width(BannerText.build(font, lines, line("&8- &c[Admin] &7Piia"), phase, 12345), false));
            assertEquals(0, width(BannerText.build(font, lines.subList(1, 2), null, phase, 7), false));
        }
    }

    @Test
    void shadowColourCarriesTheData() {
        List<ShadowColor> shadows = new ArrayList<>();
        Component banner = BannerText.build(font, List.of(line("# big"), line("&csmall")), line("me"), BannerText.PHASE_EXIT, 64 * 5 + 37);
        shadows(banner, shadows);
        assertEquals(4, shadows.size()); // box, two lines, sender
        for (ShadowColor shadow : shadows) {
            assertEquals(250, shadow.red());
            assertEquals((2 << 6) | 37, shadow.green());
            assertEquals(0xE0, shadow.alpha() & 0xF0);
        }
        // rows: large (20) at y 6, small (10) at 26, sender after a gap of 4 at 40; height 6 + 20 + 10 + 4 + 10 + 6 - 2
        assertEquals(128 | (54 / 2), shadows.get(0).blue());
        assertEquals(((6 / 2) << 1) | 1, shadows.get(1).blue());
        assertEquals((26 / 2) << 1, shadows.get(2).blue());
        assertEquals((40 / 2) << 1, shadows.get(3).blue());
        assertEquals(15, shadows.get(1).alpha() & 15);  // white
        assertEquals(12, shadows.get(2).alpha() & 15);  // &c
    }

    @Test
    void tallestBannerFitsTheMarker() {
        List<Line> lines = List.of(line("# a"), line("# b"), line("# c"), line("# d"));
        assertTrue(BannerText.height(lines, line("x")) <= BannerText.MAX_HEIGHT);
        BannerText.build(font, lines, line("x"), BannerText.PHASE_HOLD, 0);
        List<Line> tooMany = List.of(line("# a"), line("# b"), line("# c"), line("# d"), line("# e"), line("# f"));
        assertThrows(IllegalArgumentException.class, () -> BannerText.build(font, tooMany, null, BannerText.PHASE_HOLD, 0));
    }
}
