package fi.alavesa.intercom.text;

import java.util.ArrayList;
import java.util.List;

/** Word-wraps styled banner text by measured pixel width. Pure: no Bukkit. */
public final class LineWrapper {

    private LineWrapper() { }

    /**
     * Breaks {@code text} into lines no wider than {@code maxPx} (at the size the line is drawn: a large line
     * counts double) and no longer than {@code maxChars} characters (0 = no character limit). Breaks at
     * spaces; a word that is wider than a whole line is split between characters. The caller compares the
     * number of lines with what the banner can hold - nothing is cut off here.
     */
    public static List<Line> wrap(FontWidths font, Line text, int maxPx, int maxChars) {
        int limit = text.large() ? maxPx / 2 : maxPx;
        List<Line> lines = new ArrayList<>();
        int lineStart = -1, lineEnd = -1, lineWidth = 0; // [lineStart, lineEnd) of text is the line being filled
        int i = 0, n = text.length();
        while (i < n) {
            if (text.codePoints()[i] == ' ') {
                i++;
                continue;
            }
            int wordStart = i, wordWidth = 0;
            while (i < n && text.codePoints()[i] != ' ') wordWidth += text.width(font, i++);
            int wordEnd = i;
            if (lineStart >= 0) {
                // the gap between the previous word and this one, as typed (normally one space)
                int gap = 0;
                for (int k = lineEnd; k < wordStart; k++) gap += text.width(font, k);
                if (lineWidth + gap + wordWidth <= limit && fits(wordEnd - lineStart, maxChars)) {
                    lineEnd = wordEnd;
                    lineWidth += gap + wordWidth;
                    continue;
                }
                lines.add(text.slice(lineStart, lineEnd));
            }
            // the word starts a line; a word longer than a line fills lines character by character
            lineStart = wordStart;
            lineWidth = 0;
            for (int k = wordStart; k < wordEnd; k++) {
                int width = text.width(font, k);
                if (k > lineStart && (lineWidth + width > limit || !fits(k + 1 - lineStart, maxChars))) {
                    lines.add(text.slice(lineStart, k));
                    lineStart = k;
                    lineWidth = 0;
                }
                lineWidth += width;
            }
            lineEnd = wordEnd;
        }
        if (lineStart >= 0) lines.add(text.slice(lineStart, lineEnd));
        return lines;
    }

    private static boolean fits(int chars, int maxChars) { return maxChars <= 0 || chars <= maxChars; }

    /** Cuts {@code line} so that it is at most {@code maxPx} wide, ending in "..." when something was cut. */
    public static Line ellipsize(FontWidths font, Line line, int maxPx) {
        if (line.width(font) <= maxPx) return line;
        int budget = maxPx - 3 * font.width('.');
        int used = 0, end = 0;
        while (end < line.length() && used + line.width(font, end) <= budget) used += line.width(font, end++);
        int[] codePoints = java.util.Arrays.copyOf(line.codePoints(), end + 3), styles = java.util.Arrays.copyOf(line.styles(), end + 3);
        for (int k = end; k < end + 3; k++) {
            codePoints[k] = '.';
            styles[k] = end > 0 ? line.styles()[end - 1] & ~Line.BOLD : Line.WHITE;
        }
        return new Line(codePoints, styles, line.large());
    }
}
