package fi.alavesa.intercom;

import fi.alavesa.intercom.text.FontWidths;
import fi.alavesa.intercom.text.LineWrapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineWrapperTest {

    private final FontWidths font = Fonts.widths();

    @Test
    void knowsVanillaWidths() {
        assertEquals(4, font.width(' '));
        assertEquals(2, font.width('i'));
        assertEquals(6, font.width('W'));
        assertEquals(6, font.width('ä'));
        assertEquals(6 + 2 + 4, font.width("Wi "));
    }

    @Test
    void wrapsAtSpacesWithinThePixelWidth() {
        String text = "Attention all personnel: containment breach in Sector 4. Proceed to the nearest shelter.";
        List<String> lines = LineWrapper.wrap(font, text, 260, 0);
        assertTrue(lines.size() > 1);
        for (String line : lines) assertTrue(font.width(line) <= 260, line);
        assertEquals(text, String.join(" ", lines));
    }

    @Test
    void respectsTheCharacterCap() {
        for (String line : LineWrapper.wrap(font, "iiii iiii iiii iiii iiii iiii", 260, 10)) assertTrue(line.length() <= 10, line);
    }

    @Test
    void splitsAWordThatIsWiderThanALine() {
        List<String> lines = LineWrapper.wrap(font, "W".repeat(100), 260, 0);
        assertEquals(100, String.join("", lines).length());
        for (String line : lines) assertTrue(font.width(line) <= 260);
    }

    @Test
    void ellipsizes() {
        String cut = LineWrapper.ellipsize(font, "W".repeat(100), 100);
        assertTrue(cut.endsWith("...") && font.width(cut) <= 100);
        assertEquals("short", LineWrapper.ellipsize(font, "short", 100));
    }
}
