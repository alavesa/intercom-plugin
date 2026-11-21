package fi.alavesa.intercom;

import fi.alavesa.intercom.text.Line;
import fi.alavesa.intercom.text.Markup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkupTest {

    private static Line parse(String text) { return Markup.parse(text, Line.WHITE); }

    /** Style of the first occurrence of {@code ch}. */
    private static int style(Line line, char ch) { return line.styles()[line.plain().indexOf(ch)]; }

    @Test
    void discordMarkers() {
        Line line = parse("a **b** *c* _d_ __e__ ~~f~~ g");
        assertEquals("a b c d e f g", line.plain());
        assertEquals(Line.WHITE, style(line, 'a'));
        assertEquals(Line.WHITE | Line.BOLD, style(line, 'b'));
        assertEquals(Line.WHITE | Line.ITALIC, style(line, 'c'));
        assertEquals(Line.WHITE | Line.ITALIC, style(line, 'd'));
        assertEquals(Line.WHITE | Line.UNDERLINE, style(line, 'e'));
        assertEquals(Line.WHITE | Line.STRIKE, style(line, 'f'));
        assertEquals(Line.WHITE, style(line, 'g'));
    }

    @Test
    void nestedMarkers() {
        Line line = parse("**bold and *both* again**");
        assertEquals("bold and both again", line.plain());
        assertEquals(Line.WHITE | Line.BOLD | Line.ITALIC, style(line, 't'));
        assertEquals(Line.WHITE | Line.BOLD, style(line, 'g'));
    }

    @Test
    void unmatchedMarkersStayText() {
        assertEquals("2 * 3 = 6", parse("2 * 3 = 6").plain());
        assertEquals("a ** b", parse("a ** b").plain());
        assertEquals("snake_case_name and Some_Player", parse("snake_case_name and Some_Player").plain());
        assertEquals("*not italic*", parse("\\*not italic\\*").plain());
        assertEquals("R&D", parse("R\\&D").plain());
        assertEquals("fish & chips", parse("fish & chips").plain());
    }

    @Test
    void colourCodes() {
        Line line = parse("&cred &lbold &9blue &rplain &kx");
        assertEquals("red bold blue plain x", line.plain());
        assertEquals(12, style(line, 'r'));
        assertEquals(12 | Line.BOLD, style(line, 'o'));
        assertEquals(9, style(line, 'u'));               // a colour switches the styles off, as in Minecraft
        assertEquals(Line.WHITE, style(line, 'p'));
        assertEquals(Line.WHITE, style(line, 'x'));
        assertEquals(10, style(parse("&Aupper"), 'u'));
    }

    @Test
    void largeLine() {
        assertTrue(parse("# ALERT").large());
        assertEquals("ALERT", parse("# ALERT").plain());
        assertFalse(parse("#hashtag").large());
        assertFalse(parse("not # large").large());
    }

    @Test
    void legacyOnlyLeavesDiscordMarkersAlone() {
        Line line = Markup.parseLegacy("&8- &c[Admin] &7Some_Name_ *x*", Line.GRAY);
        assertEquals("- [Admin] Some_Name_ *x*", line.plain());
        assertEquals(8, line.styles()[0]);
        assertEquals(12, style(line, 'A'));
        assertEquals(7, style(line, 'S'));
    }
}
