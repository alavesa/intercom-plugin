package fi.alavesa.intercom;

import fi.alavesa.intercom.text.FontWidths;
import fi.alavesa.intercom.text.Line;
import fi.alavesa.intercom.text.LineWrapper;
import fi.alavesa.intercom.text.Markup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineWrapperTest {

    private final FontWidths font = Fonts.widths();

    private Line plain(String text) { return Markup.parseLegacy(text, Line.WHITE); }

    @Test
    void knowsVanillaWidths() {
        assertEquals(4, font.width(' '));
        assertEquals(2, font.width('i'));
        assertEquals(6, font.width('W'));
        assertEquals(6, font.width('ä'));
        assertEquals(6 + 2 + 4, font.width("Wi "));
        // bold is one pixel wider per character
        assertEquals(3 * 6 + 3, Markup.parse("**WWW**", Line.WHITE).width(font));
    }

    @Test
    void wrapsAtSpacesWithinThePixelWidth() {
        String text = "Attention all personnel: containment breach in Sector 4. Proceed to the nearest shelter.";
        List<Line> lines = LineWrapper.wrap(font, plain(text), 260, 0);
        assertTrue(lines.size() > 1);
        for (Line line : lines) assertTrue(line.width(font) <= 260, line.plain());
        assertEquals(text, lines.stream().map(Line::plain).collect(Collectors.joining(" ")));
    }

    @Test
    void aLargeLineHasHalfTheRoom() {
        String text = "Attention all personnel: containment breach in Sector 4.";
        List<Line> lines = LineWrapper.wrap(font, Markup.parse("# " + text, Line.WHITE), 260, 0);
        for (Line line : lines) assertTrue(line.large() && line.width(font) <= 130, line.plain());
        assertEquals(text, lines.stream().map(Line::plain).collect(Collectors.joining(" ")));
    }

    @Test
    void keepsStylesAcrossTheBreak() {
        List<Line> lines = LineWrapper.wrap(font, Markup.parse("&c" + "word ".repeat(30), Line.WHITE), 260, 0);
        assertTrue(lines.size() > 1);
        for (Line line : lines) for (int style : line.styles()) assertEquals(12, style);
    }

    @Test
    void respectsTheCharacterCap() {
        for (Line line : LineWrapper.wrap(font, plain("iiii iiii iiii iiii iiii iiii"), 260, 10)) assertTrue(line.length() <= 10, line.plain());
    }

    @Test
    void splitsAWordThatIsWiderThanALine() {
        List<Line> lines = LineWrapper.wrap(font, plain("W".repeat(100)), 260, 0);
        assertEquals(100, lines.stream().mapToInt(Line::length).sum());
        for (Line line : lines) assertTrue(line.width(font) <= 260);
    }

    @Test
    void ellipsizes() {
        Line cut = LineWrapper.ellipsize(font, plain("W".repeat(100)), 100);
        assertTrue(cut.plain().endsWith("...") && cut.width(font) <= 100);
        assertEquals("short", LineWrapper.ellipsize(font, plain("short"), 100).plain());
    }
}
