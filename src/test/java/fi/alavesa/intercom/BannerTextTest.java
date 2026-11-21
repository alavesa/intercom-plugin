package fi.alavesa.intercom;

import fi.alavesa.intercom.banner.BannerText;
import fi.alavesa.intercom.text.FontWidths;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BannerTextTest {

    private final FontWidths font = Fonts.widths();

    /** Pen movement of the whole component, as the client adds it up. */
    private int width(Component component) {
        int width = 0;
        if (component instanceof TextComponent text) {
            for (char ch : text.content().toCharArray()) {
                if (ch >= 0xF000 && ch < 0xF00A) width += 1 << (ch - 0xF000);
                else if (ch >= 0xF100 && ch < 0xF10A) width -= 1 << (ch - 0xF100);
                else if (ch == 0xE020) width += 9;
                else width += font.width(ch);
            }
        }
        for (Component child : component.children()) width += width(child);
        return width;
    }

    private static void colours(Component component, List<TextColor> out) {
        if (component.color() != null) out.add(component.color());
        for (Component child : component.children()) colours(child, out);
    }

    @Test
    void everyBannerIsZeroWide() {
        List<String> lines = List.of("Attention all personnel:", "containment breach in Sector 4.", "Älä hätäile");
        for (int phase = 0; phase < 4; phase++) {
            assertEquals(0, width(BannerText.build(font, lines, "- Piia", phase, 12345)));
            assertEquals(0, width(BannerText.build(font, lines.subList(0, 1), null, phase, 7)));
        }
    }

    @Test
    void markerCarriesPhaseTimeRowsAndRow() {
        List<TextColor> colours = new ArrayList<>();
        colours(BannerText.build(font, List.of("one", "two"), "- me", BannerText.PHASE_EXIT, 64 * 5 + 37), colours);
        assertEquals(4, colours.size()); // box, two lines, sender
        for (TextColor colour : colours) {
            assertEquals(250, colour.red());
            assertEquals((2 << 6) | 37, colour.green());
            assertEquals(2, (colour.blue() >> 4) & 7);  // 3 rows
            assertTrue((colour.blue() & 128) != 0);     // has a sender line
        }
        assertEquals(7, colours.get(0).blue() & 7);
        assertEquals(0, colours.get(1).blue() & 15);
        assertEquals(1, colours.get(2).blue() & 15);
        assertEquals(2 | 8, colours.get(3).blue() & 15);
    }

    @Test
    void holdIgnoresTime() {
        List<TextColor> colours = new ArrayList<>();
        colours(BannerText.build(font, List.of("x"), null, BannerText.PHASE_HOLD, 999), colours);
        assertNotNull(colours.get(0));
        assertEquals(0, colours.get(0).green());
    }
}
