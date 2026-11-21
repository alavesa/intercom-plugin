package fi.alavesa.intercom.announce;

import fi.alavesa.intercom.config.Messages;
import fi.alavesa.intercom.config.Settings;
import org.bukkit.command.CommandSender;

/**
 * Tells a sender why their announcement was NOT made (messages.yml). An accepted announcement says nothing: the
 * banner and its chime are the confirmation.
 */
public final class Feedback {

    private Feedback() { }

    public static void send(CommandSender to, AnnouncementManager.Result result, Messages messages, Settings settings) {
        Settings.Announcement cfg = settings.announcement();
        switch (result.outcome()) {
            case SENT, QUEUED -> { }
            case NO_PERMISSION -> messages.send(to, "no-permission");
            case BLANK -> messages.send(to, "blank");
            case TOO_LONG -> messages.send(to, "too-long", Messages.text("length", result.number()), Messages.text("max", cfg.maxCharacters()));
            case DOES_NOT_FIT -> messages.send(to, "does-not-fit", Messages.text("lines", result.number()), Messages.text("max", cfg.maxLines()));
            case COOLDOWN -> messages.send(to, "cooldown", Messages.text("seconds", result.number()), Messages.text("time", AnnouncementManager.clock(result.number())));
            case QUEUE_FULL -> messages.send(to, "queue-full");
        }
    }
}
