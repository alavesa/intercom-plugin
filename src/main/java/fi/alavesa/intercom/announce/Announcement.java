package fi.alavesa.intercom.announce;

import java.util.List;
import java.util.UUID;

/**
 * One accepted announcement, ready to show.
 *
 * @param senderId   the sending player, or null for the console
 * @param senderName name as shown in chat and the log
 * @param text       the cleaned message (plain text, one line)
 * @param lines      {@code text} wrapped for the banner, top line first
 * @param senderLine the line under the message on the banner, or null for none
 */
public record Announcement(UUID senderId, String senderName, String text, List<String> lines, String senderLine) {

    public Announcement {
        lines = List.copyOf(lines);
    }
}
