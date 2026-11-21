package fi.alavesa.intercom.text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Pixel widths (advances) of the characters of Minecraft's default bitmap font, so the server can wrap and
 * centre banner text exactly like the client draws it. The table (font/default_widths.tsv) is generated from
 * the vanilla 26.3 client jar by tools/gen_widths.py and covers ASCII, Latin-1 (ä ö å ...), Greek, Cyrillic
 * and the symbols Mojang drew - about 2400 characters.
 * <p>
 * The table is also the banner's alphabet: the banner font (intercom:banner) contains exactly these
 * characters, so a character without a width cannot be shown and is replaced before it gets here
 * (see {@link Sanitizer}).
 */
public final class FontWidths {

    private final int[] low = new int[256];
    private final Map<Integer, Integer> other = new HashMap<>();

    public FontWidths(InputStream in) throws IOException {
        java.util.Arrays.fill(low, -1);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int tab = line.indexOf('\t');
                int codePoint = Integer.parseInt(line.substring(0, tab), 16);
                int advance = Integer.parseInt(line.substring(tab + 1).trim());
                if (codePoint < 256) low[codePoint] = advance;
                else other.put(codePoint, advance);
            }
        }
    }

    /** True when the banner font has this character. */
    public boolean known(int codePoint) {
        return codePoint < 256 ? codePoint >= 0 && low[codePoint] >= 0 : other.containsKey(codePoint);
    }

    /** Advance of one character; 0 for a character the font does not have. */
    public int width(int codePoint) {
        if (codePoint < 256) return codePoint >= 0 ? Math.max(0, low[codePoint]) : 0;
        return other.getOrDefault(codePoint, 0);
    }

    public int width(CharSequence text) {
        int total = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = Character.codePointAt(text, i);
            total += width(codePoint);
            i += Character.charCount(codePoint);
        }
        return total;
    }
}
