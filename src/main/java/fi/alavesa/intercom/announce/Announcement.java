package fi.alavesa.intercom.announce;

import fi.alavesa.intercom.text.Line;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.UUID;

/**
 * One accepted announcement, ready to show.
 *
 * @param senderId   the sending player, or null for the console and other plugins
 * @param senderName name as written to the log
 * @param text       the message without formatting (plain text, one line) - for the log
 * @param lines      the message wrapped for the banner, top line first
 * @param senderLine the line under the message on the banner, or null for none
 * @param chat       message and sender as chat components (fallback and chat copy)
 * @param chatSender see {@code chat}
 * @param openSound  played when the banner appears instead of the configured chime; null = the configured one
 */
public record Announcement(UUID senderId, String senderName, String text, List<Line> lines, Line senderLine,
                           Component chat, Component chatSender, Sound openSound) {

    public Announcement {
        lines = List.copyOf(lines);
    }
}
