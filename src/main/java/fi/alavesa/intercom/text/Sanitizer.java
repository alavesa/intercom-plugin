package fi.alavesa.intercom.text;

import java.text.Normalizer;

/**
 * Turns whatever a player typed into plain banner text. Pure: no Bukkit.
 * <p>
 * The result contains only characters of the banner font ({@link FontWidths#known}), which is what makes it
 * safe:
 * <ul>
 *   <li>legacy colour codes ({@code §x}) are removed; MiniMessage tags and {@code &x} stay as the literal
 *       characters they are - banner and chat text is always inserted as plain text, never parsed;</li>
 *   <li>control characters, line breaks and tabs become spaces, runs of spaces collapse;</li>
 *   <li>private-use characters (where resource packs put their glyphs, and where the banner font keeps its
 *       cursor-moving spaces and background glyph), unassigned code points and lone surrogates are dropped,
 *       so a message can neither move the banner's layout nor draw another pack's icons;</li>
 *   <li>the shader marker is a text COLOUR, and a colour can only come from the server: there is nothing a
 *       typed character can do to produce or change it;</li>
 *   <li>characters the font does not have become a close ASCII equivalent or {@code ?}.</li>
 * </ul>
 */
public final class Sanitizer {

    private Sanitizer() { }

    public static String clean(String input, FontWidths font) {
        if (input == null) return "";
        // composed form: "a" + combining dots becomes the single character the font has
        String text = Normalizer.normalize(input, Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder(text.length());
        boolean lastSpace = true; // leading spaces are dropped
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint == '§') {
                // a legacy colour code is the section sign plus one character
                if (i < text.length()) i += Character.charCount(text.codePointAt(i));
                continue;
            }
            int type = Character.getType(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || type == Character.CONTROL) {
                if (!lastSpace) out.append(' ');
                lastSpace = true;
                continue;
            }
            if (type == Character.PRIVATE_USE || type == Character.SURROGATE || type == Character.UNASSIGNED
                || type == Character.FORMAT || type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK) {
                continue;
            }
            String replacement = font.known(codePoint) ? Character.toString(codePoint) : substitute(codePoint);
            out.append(replacement);
            lastSpace = false;
        }
        return out.toString().stripTrailing();
    }

    /** A character the font lacks -> something it has. */
    private static String substitute(int codePoint) {
        return switch (codePoint) {
            case '‘', '’', '‚', '′', '´' -> "'";
            case '“', '”', '„', '″' -> "\"";
            case '–', '—', '‒', '―', '−' -> "-";
            case '…' -> "...";
            case '•', '·' -> "*";
            default -> "?";
        };
    }

    /** Characters as a player counts them (an emoji outside the basic plane is one, not two). */
    public static int length(String text) {
        return text.codePointCount(0, text.length());
    }
}
