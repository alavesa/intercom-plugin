package fi.alavesa.intercom.text;

/**
 * The formatting a sender may type, turned into styled {@link Line}s. Pure: no Bukkit.
 * <ul>
 *   <li>Discord style: {@code **bold**}, {@code *italic*} or {@code _italic_}, {@code __underline__},
 *       {@code ~~strike~~}, and {@code # } at the start of a line for a large line. A marker without its
 *       partner stays an ordinary character; {@code \} in front of a marker makes it literal.</li>
 *   <li>Colour codes: {@code &0}-{@code &9}, {@code &a}-{@code &f} set the colour (and, as in Minecraft,
 *       switch the {@code &l}/{@code &o}/{@code &n}/{@code &m} styles off); {@code &l} bold, {@code &o}
 *       italic, {@code &n} underline, {@code &m} strike, {@code &r} back to the default. {@code &k}
 *       (scrambled text) is dropped.</li>
 * </ul>
 * Nothing here can produce anything but a colour index and four flags per character: the shader marker and
 * the banner font's own characters stay out of reach of typed text.
 */
public final class Markup {

    private static final String COLOR_CODES = "0123456789abcdef";

    private Markup() { }

    /**
     * A line typed by a player: Discord markers and colour codes.
     *
     * @param text         one cleaned line (see {@link Sanitizer})
     * @param defaultStyle style of unformatted text
     */
    public static Line parse(String text, int defaultStyle) {
        boolean large = false;
        if (text.startsWith("# ")) {
            large = true;
            text = text.substring(2).stripLeading();
        }
        return parse(text, defaultStyle, true, large);
    }

    /** Text from a config file or a permission plugin (a prefix): colour codes only, so {@code _} and {@code *} in names stay. */
    public static Line parseLegacy(String text, int defaultStyle) {
        return parse(text, defaultStyle, false, false);
    }

    private static Line parse(String text, int defaultStyle, boolean discord, boolean large) {
        int[] in = text.codePoints().toArray();
        int[] out = new int[in.length], styles = new int[in.length];
        int n = 0;
        int legacy = defaultStyle;                       // colour + flags set by & codes
        boolean bold = false, italicStar = false, italicUnderscore = false, underline = false, strike = false;
        for (int i = 0; i < in.length; i++) {
            int c = in[i];
            if (c == '&' && i + 1 < in.length) {
                int code = Character.toLowerCase(in[i + 1]);
                int color = COLOR_CODES.indexOf(code);
                boolean known = true;
                if (color >= 0) legacy = color;
                else if (code == 'l') legacy |= Line.BOLD;
                else if (code == 'o') legacy |= Line.ITALIC;
                else if (code == 'n') legacy |= Line.UNDERLINE;
                else if (code == 'm') legacy |= Line.STRIKE;
                else if (code == 'r') legacy = defaultStyle;
                else if (code != 'k') known = false;
                if (known) {
                    i++;
                    continue;
                }
            }
            if (discord) {
                if (c == '\\' && i + 1 < in.length && "*_~\\&#".indexOf(in[i + 1]) >= 0) {
                    c = in[++i];
                } else if (c == '*' || c == '_' || c == '~') {
                    boolean twice = i + 1 < in.length && in[i + 1] == c;
                    if (twice && (c == '*' ? bold : c == '_' ? underline : strike)) {
                        if (c == '*') bold = false; else if (c == '_') underline = false; else strike = false;
                        i++;
                        continue;
                    }
                    if (twice && closes(in, i + 2, c, true)) {
                        if (c == '*') bold = true; else if (c == '_') underline = true; else strike = true;
                        i++;
                        continue;
                    }
                    if (c == '*' && !twice) {
                        if (italicStar) { italicStar = false; continue; }
                        if (closes(in, i + 1, c, false)) { italicStar = true; continue; }
                    }
                    if (c == '_' && !twice) {
                        // inside a word an underscore is just an underscore (snake_case, player names)
                        if (italicUnderscore && (i + 1 == in.length || !Character.isLetterOrDigit(in[i + 1]))) { italicUnderscore = false; continue; }
                        if (!italicUnderscore && (i == 0 || !Character.isLetterOrDigit(in[i - 1])) && closesWord(in, i + 1)) { italicUnderscore = true; continue; }
                    }
                }
            }
            out[n] = c;
            styles[n] = legacy | (bold ? Line.BOLD : 0) | (italicStar || italicUnderscore ? Line.ITALIC : 0)
                | (underline ? Line.UNDERLINE : 0) | (strike ? Line.STRIKE : 0);
            n++;
        }
        return new Line(java.util.Arrays.copyOf(out, n), java.util.Arrays.copyOf(styles, n), large);
    }

    /** Is there a closing marker ({@code c}, or {@code cc} when {@code twice}) after {@code from}, with something in between? */
    private static boolean closes(int[] in, int from, int c, boolean twice) {
        for (int i = from + 1; i < in.length; i++) {
            if (in[i] != c || in[i - 1] == '\\') continue;
            if (!twice) return true;
            if (i + 1 < in.length && in[i + 1] == c) return true;
        }
        return false;
    }

    /** Is there an underscore after {@code from} that ends a word? */
    private static boolean closesWord(int[] in, int from) {
        for (int i = from + 1; i < in.length; i++) {
            if (in[i] == '_' && (i + 1 == in.length || !Character.isLetterOrDigit(in[i + 1]))) return true;
        }
        return false;
    }
}
