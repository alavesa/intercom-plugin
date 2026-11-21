package fi.alavesa.intercom.announce;

import fi.alavesa.intercom.IntercomPlugin;
import fi.alavesa.intercom.banner.BannerScheduler;
import fi.alavesa.intercom.config.Settings;
import fi.alavesa.intercom.text.FontWidths;
import fi.alavesa.intercom.text.LineWrapper;
import fi.alavesa.intercom.text.Sanitizer;
import net.kyori.adventure.sound.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The one way an announcement gets on screen: checks the sender and the text, applies the cooldown, logs,
 * and queues - one banner at a time, in the order they were accepted. Nothing here trusts the client: the
 * dialog's own length limit and the permission check done when the dialog opened are repeated.
 * Main thread only.
 */
public final class AnnouncementManager {

    public static final String PERMISSION_ANNOUNCE = "intercom.announce", PERMISSION_ADMIN = "intercom.admin",
        PERMISSION_BYPASS_COOLDOWN = "intercom.bypass.cooldown";

    public enum Outcome { SENT, QUEUED, NO_PERMISSION, BLANK, TOO_LONG, DOES_NOT_FIT, COOLDOWN, QUEUE_FULL }

    /**
     * @param cleaned the text after cleaning (what the sender may want back in the dialog to fix)
     * @param number  TOO_LONG: its length; DOES_NOT_FIT: lines needed; COOLDOWN: seconds left; QUEUED: position
     */
    public record Result(Outcome outcome, String cleaned, int number) {

        public boolean accepted() { return outcome == Outcome.SENT || outcome == Outcome.QUEUED; }
    }

    private final IntercomPlugin plugin;
    private final BannerScheduler scheduler;
    private final AnnouncementLog log;
    private final Deque<Announcement> queue = new ArrayDeque<>();
    /** Sender -> System.nanoTime() of their last accepted announcement. */
    private final Map<UUID, Long> lastSent = new HashMap<>();

    public AnnouncementManager(IntercomPlugin plugin, BannerScheduler scheduler, AnnouncementLog log) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.log = log;
    }

    public static boolean mayAnnounce(CommandSender sender) {
        return sender.hasPermission(PERMISSION_ANNOUNCE) || sender.hasPermission(PERMISSION_ADMIN);
    }

    /**
     * @param raw          the text as it came from the client or the command line
     * @param skipCooldown /announce test: neither checks nor starts the cooldown
     */
    public Result submit(CommandSender sender, String raw, boolean skipCooldown) {
        Settings settings = plugin.settings();
        Settings.Announcement cfg = settings.announcement();
        FontWidths font = plugin.fontWidths();

        if (!mayAnnounce(sender)) return new Result(Outcome.NO_PERMISSION, "", 0);

        String text = Sanitizer.clean(raw, font);
        if (text.isEmpty()) return new Result(Outcome.BLANK, "", 0);
        int length = Sanitizer.length(text);
        if (length > cfg.maxCharacters()) return new Result(Outcome.TOO_LONG, text, length);

        List<String> lines = LineWrapper.wrap(font, text, settings.banner().textWidth(), cfg.maxCharsPerLine());
        if (lines.size() > cfg.maxLines()) return new Result(Outcome.DOES_NOT_FIT, text, lines.size());

        UUID senderId = sender instanceof Player player ? player.getUniqueId() : null;
        boolean cooldownApplies = !skipCooldown && senderId != null && cfg.cooldownSeconds() > 0
            && !sender.hasPermission(PERMISSION_BYPASS_COOLDOWN) && !sender.hasPermission(PERMISSION_ADMIN);
        long now = System.nanoTime();
        if (cooldownApplies) {
            Long last = lastSent.get(senderId);
            if (last != null) {
                long leftNanos = cfg.cooldownSeconds() * 1_000_000_000L - (now - last);
                if (leftNanos > 0) return new Result(Outcome.COOLDOWN, text, (int) Math.ceil(leftNanos / 1e9));
            }
        }
        boolean wait = scheduler.busy() || !queue.isEmpty();
        if (wait && queue.size() >= cfg.queueSize()) return new Result(Outcome.QUEUE_FULL, text, 0);

        String senderName = sender instanceof Player player ? player.getName() : "Console";
        String senderLine = null;
        if (cfg.showSender()) {
            // the name goes through the same cleaning as the message: only banner-font characters reach the banner
            String line = Sanitizer.clean(cfg.senderFormat().replace("{name}", senderName), font);
            if (!line.isEmpty()) senderLine = LineWrapper.ellipsize(font, line, settings.banner().textWidth());
        }
        Announcement announcement = new Announcement(senderId, senderName, text, lines, senderLine);

        if (cooldownApplies) lastSent.put(senderId, now);
        if (settings.logging().console()) {
            plugin.getLogger().info("Announcement by " + senderName + " (" + (senderId == null ? "console" : senderId) + "): " + text);
        }
        if (settings.logging().file()) log.append(announcement);
        Sound confirm = settings.sounds().confirm().sound();
        if (confirm != null && sender instanceof Player player) {
            // the pack's own sound is silent without the pack - which is fine for a confirmation
            player.playSound(confirm, Sound.Emitter.self());
        }

        if (wait) {
            queue.add(announcement);
            return new Result(Outcome.QUEUED, text, queue.size());
        }
        scheduler.play(announcement, this::next);
        return new Result(Outcome.SENT, text, 0);
    }

    private void next() {
        Announcement announcement = queue.poll();
        if (announcement != null) scheduler.play(announcement, this::next);
    }

    public int queued() { return queue.size(); }

    /** Stops the banner that is showing and drops everything waiting. @return how many were waiting */
    public int clear() {
        int waiting = queue.size();
        queue.clear();
        scheduler.stop();
        return waiting;
    }

    public void forget(Player player) {
        // the cooldown deliberately survives a relog; entries are dropped once they have run out
        long now = System.nanoTime();
        long cooldownNanos = plugin.settings().announcement().cooldownSeconds() * 1_000_000_000L;
        lastSent.values().removeIf(last -> now - last > cooldownNanos);
    }
}
