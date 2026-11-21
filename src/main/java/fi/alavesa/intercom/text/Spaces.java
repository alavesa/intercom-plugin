package fi.alavesa.intercom.text;

/**
 * The cursor-moving spaces both pack fonts (intercom:banner, intercom:ui) define: U+F000+n advances the pen by
 * 2^n pixels and U+F100+n moves it back by 2^n, n = 0..9. Any distance up to 1023 px is a few of them.
 */
public final class Spaces {

    private static final char POSITIVE = '\uF000', NEGATIVE = '\uF100';

    private Spaces() { }

    /** Characters that move the pen by {@code px} (negative = left); empty for 0. */
    public static String shift(int px) {
        StringBuilder out = new StringBuilder();
        char base = px < 0 ? NEGATIVE : POSITIVE;
        int n = Math.abs(px);
        for (int bit = 9; bit >= 0; bit--) if ((n & (1 << bit)) != 0) out.append((char) (base + bit));
        return out.toString();
    }
}
