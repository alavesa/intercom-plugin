package fi.alavesa.intercom.text;

import java.util.Arrays;

/**
 * One line of banner text with a style per character. Immutable.
 * <p>
 * A style is an int: bits 0-3 the colour (the 16 Minecraft colour codes, 0 = black ... 15 = white), then one
 * bit each for {@link #BOLD}, {@link #ITALIC}, {@link #UNDERLINE} and {@link #STRIKE}.
 *
 * @param codePoints the characters
 * @param styles     the style of each character, same length
 * @param large      the whole line is drawn at double size (a "# heading")
 */
public record Line(int[] codePoints, int[] styles, boolean large) {

    public static final int COLOR_MASK = 15, BOLD = 16, ITALIC = 32, UNDERLINE = 64, STRIKE = 128;
    public static final int WHITE = 15, GRAY = 7;

    public int length() { return codePoints.length; }

    public boolean isBlank() {
        for (int codePoint : codePoints) if (codePoint != ' ') return false;
        return true;
    }

    /** Advance of the character at {@code index}: bold glyphs are one pixel wider, as on the client. */
    public int width(FontWidths font, int index) {
        return font.width(codePoints[index]) + ((styles[index] & BOLD) != 0 ? 1 : 0);
    }

    /** Width in GUI pixels at normal size (a large line is drawn twice this wide). */
    public int width(FontWidths font) {
        int total = 0;
        for (int i = 0; i < codePoints.length; i++) total += width(font, i);
        return total;
    }

    public Line slice(int from, int to) {
        return new Line(Arrays.copyOfRange(codePoints, from, to), Arrays.copyOfRange(styles, from, to), large);
    }

    public String plain() { return new String(codePoints, 0, codePoints.length); }

    /** Index after the run of equally styled characters that starts at {@code from}. */
    public int runEnd(int from) {
        int end = from + 1;
        while (end < codePoints.length && styles[end] == styles[from]) end++;
        return end;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Line other && large == other.large && Arrays.equals(codePoints, other.codePoints) && Arrays.equals(styles, other.styles);
    }

    @Override
    public int hashCode() { return 31 * Arrays.hashCode(codePoints) + Arrays.hashCode(styles) + (large ? 1 : 0); }

    @Override
    public String toString() { return (large ? "# " : "") + plain(); }
}
