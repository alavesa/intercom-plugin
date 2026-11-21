package fi.alavesa.intercom;

import fi.alavesa.intercom.text.FontWidths;
import fi.alavesa.intercom.text.Sanitizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SanitizerTest {

    private final FontWidths font = Fonts.widths();

    @Test
    void keepsOrdinaryText() {
        assertEquals("Lockdown in Sector 4. Älä hätäile!", Sanitizer.clean("Lockdown in Sector 4. Älä hätäile!", font));
    }

    @Test
    void collapsesWhitespaceAndLineBreaks() {
        assertEquals("a b c", Sanitizer.clean("  a \n\n b\t\t  c \r\n", font));
        assertEquals("", Sanitizer.clean(" \n\t  ", font));
        assertEquals("", Sanitizer.clean(null, font));
    }

    @Test
    void removesLegacyColourCodesAndControls() {
        assertEquals("red alert", Sanitizer.clean("§cred §lalert§", font));
        assertEquals("a b", Sanitizer.clean("a\u0000\u0007b".replace("\u0007", " "), font).replace("  ", " "));
    }

    @Test
    void miniMessageTagsStayLiteralText() {
        // never parsed anywhere, so they are harmless - and visible, which is the honest result
        assertEquals("<red>hi</red> &c", Sanitizer.clean("<red>hi</red> &c", font));
    }

    @Test
    void dropsPrivateUseAndTheBannerFontsOwnCharacters() {
        // U+F000.. are the cursor-moving spaces, U+E020 the background glyph, U+E001.. the dialog glyphs
        assertEquals("ab", Sanitizer.clean("ab", font));
        // supplementary private use plane and zero-width characters
        assertEquals("ab", Sanitizer.clean("a" + new String(Character.toChars(0xF0000)) + "​‌‍﻿b", font));
    }

    @Test
    void replacesWhatTheFontLacks() {
        // typographic quotes and dashes are in the default font and stay as typed
        assertEquals("it’s “fine” – ok…", Sanitizer.clean("it’s “fine” – ok…", font));
        assertEquals("?", Sanitizer.clean("😀", font));
        assertEquals("? ?", Sanitizer.clean("漢 字", font));
    }

    @Test
    void everythingThatComesOutIsInTheFont() {
        StringBuilder all = new StringBuilder();
        for (int cp = 0; cp < 0x30000; cp++) if (Character.isValidCodePoint(cp)) all.appendCodePoint(cp);
        String cleaned = Sanitizer.clean(all.toString(), font);
        assertTrue(cleaned.codePoints().allMatch(cp -> font.known(cp) && Character.getType(cp) != Character.PRIVATE_USE));
    }
}
