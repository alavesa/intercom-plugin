package fi.alavesa.intercom.banner;

import fi.alavesa.intercom.text.Line;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;

/**
 * The banner for a client that has not loaded the Intercom pack: one boss bar per line, in Minecraft's own font,
 * so the words are readable without the pack's font and shader. No slide, no box, no double size - those need the
 * pack - but the same text, colours and formatting at the top of the screen.
 */
public final class PlainBanner {

    /** The 16 colour codes in the order of {@link Line}'s colour index (0 = black ... 15 = white). */
    private static final NamedTextColor[] COLORS = {
        NamedTextColor.BLACK, NamedTextColor.DARK_BLUE, NamedTextColor.DARK_GREEN, NamedTextColor.DARK_AQUA,
        NamedTextColor.DARK_RED, NamedTextColor.DARK_PURPLE, NamedTextColor.GOLD, NamedTextColor.GRAY,
        NamedTextColor.DARK_GRAY, NamedTextColor.BLUE, NamedTextColor.GREEN, NamedTextColor.AQUA,
        NamedTextColor.RED, NamedTextColor.LIGHT_PURPLE, NamedTextColor.YELLOW, NamedTextColor.WHITE
    };

    private PlainBanner() { }

    /**
     * One component per boss bar, top to bottom: the message lines, the sender line ("- Name") at the end of the last
     * one. Every boss bar draws its own bar graphic under the name, so fewer rows look calmer.
     */
    public static List<Component> rows(List<Line> lines, Line sender) {
        List<Component> rows = new ArrayList<>();
        for (Line line : lines) if (!line.isBlank()) rows.add(component(line));
        if (sender != null && !sender.isBlank()) {
            Component signed = component(sender);
            if (rows.isEmpty()) rows.add(signed);
            else rows.set(rows.size() - 1, rows.getLast().append(Component.text("   ")).append(signed));
        }
        return rows;
    }

    static Component component(Line line) {
        TextComponent.Builder out = Component.text();
        for (int i = 0; i < line.length(); ) {
            int end = line.runEnd(i);
            int style = line.styles()[i];
            Component run = Component.text(new String(line.codePoints(), i, end - i), COLORS[style & Line.COLOR_MASK])
                // a large ("# ") line cannot be drawn bigger without the pack: bold stands in for it
                .decoration(TextDecoration.BOLD, (style & Line.BOLD) != 0 || line.large())
                .decoration(TextDecoration.ITALIC, (style & Line.ITALIC) != 0)
                .decoration(TextDecoration.UNDERLINED, (style & Line.UNDERLINE) != 0)
                .decoration(TextDecoration.STRIKETHROUGH, (style & Line.STRIKE) != 0);
            out.append(run);
            i = end;
        }
        return out.build();
    }
}
