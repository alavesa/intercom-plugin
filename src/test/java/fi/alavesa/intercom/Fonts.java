package fi.alavesa.intercom;

import fi.alavesa.intercom.text.FontWidths;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

final class Fonts {

    private Fonts() { }

    static FontWidths widths() {
        try (InputStream in = Fonts.class.getResourceAsStream("/font/default_widths.tsv")) {
            return new FontWidths(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
