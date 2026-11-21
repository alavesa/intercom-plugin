package fi.alavesa.intercom.text;

import java.util.ArrayList;
import java.util.List;

/** Word-wraps banner text by measured pixel width. Pure: no Bukkit. */
public final class LineWrapper {

    private LineWrapper() { }

    /**
     * Breaks {@code text} (already {@link Sanitizer#clean cleaned}: single spaces, no line breaks) into lines no
     * wider than {@code maxPx} and no longer than {@code maxChars} characters (0 = no character limit). Breaks
     * at spaces; a word that is wider than a whole line is split between characters. The caller compares the
     * number of lines with what the banner can hold - nothing is cut off here.
     */
    public static List<String> wrap(FontWidths font, String text, int maxPx, int maxChars) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int lineWidth = 0, lineChars = 0;
        int space = font.width(' ');
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            int wordWidth = font.width(word), wordChars = word.codePointCount(0, word.length());
            if (lineChars > 0 && lineWidth + space + wordWidth <= maxPx && fits(lineChars + 1 + wordChars, maxChars)) {
                line.append(' ').append(word);
                lineWidth += space + wordWidth;
                lineChars += 1 + wordChars;
                continue;
            }
            if (lineChars > 0) {
                lines.add(line.toString());
                line.setLength(0);
                lineWidth = 0;
                lineChars = 0;
            }
            // the word starts a line; a word longer than a line fills lines character by character
            for (int i = 0; i < word.length(); ) {
                int codePoint = word.codePointAt(i);
                i += Character.charCount(codePoint);
                int width = font.width(codePoint);
                if (lineChars > 0 && (lineWidth + width > maxPx || !fits(lineChars + 1, maxChars))) {
                    lines.add(line.toString());
                    line.setLength(0);
                    lineWidth = 0;
                    lineChars = 0;
                }
                line.appendCodePoint(codePoint);
                lineWidth += width;
                lineChars++;
            }
        }
        if (lineChars > 0) lines.add(line.toString());
        return lines;
    }

    private static boolean fits(int chars, int maxChars) { return maxChars <= 0 || chars <= maxChars; }

    /** Cuts {@code text} so that it is at most {@code maxPx} wide, ending in "..." when something was cut. */
    public static String ellipsize(FontWidths font, String text, int maxPx) {
        if (font.width(text) <= maxPx) return text;
        int budget = maxPx - font.width("...");
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int width = font.width(codePoint);
            if (used + width > budget) break;
            out.appendCodePoint(codePoint);
            used += width;
            i += Character.charCount(codePoint);
        }
        return out.toString().stripTrailing() + "...";
    }
}
