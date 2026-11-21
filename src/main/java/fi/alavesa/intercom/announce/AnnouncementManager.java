package fi.alavesa.intercom.announce;

import fi.alavesa.intercom.IntercomPlugin;
import fi.alavesa.intercom.banner.BannerScheduler;
import fi.alavesa.intercom.banner.BannerText;
import fi.alavesa.intercom.config.Settings;
import fi.alavesa.intercom.text.FontWidths;
import fi.alavesa.intercom.text.Line;
import fi.alavesa.intercom.text.LineWrapper;
import fi.alavesa.intercom.text.Markup;
import fi.alavesa.intercom.text.Sanitizer;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
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

    private static final NamedTextColor[] CHAT_COLORS = {
        NamedTextColor.BLACK, NamedTextColor.DARK_BLUE, NamedTextColor.DARK_GREEN, NamedTextColor.DARK_AQUA,
        NamedTextColor.DARK_RED, NamedTextColor.DARK_PURPLE, NamedTextColor.GOLD, NamedTextColor.GRAY,
        NamedTextColor.DARK_GRAY, NamedTextColor.BLUE, NamedTextColor.GREEN, NamedTextColor.AQUA,
        NamedTextColor.RED, NamedTextColor.LIGHT_PURPLE, NamedTextColor.YELLOW, NamedTextColor.WHITE};

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

    /** 75 -> "1:15". */
    public static String clock(int seconds) { return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60; }

    private boolean cooldownApplies(CommandSender sender) {
        return sender instanceof Player && plugin.settings().announcement().cooldownSeconds() > 0
            && !sender.hasPermission(PERMISSION_BYPASS_COOLDOWN) && !sender.hasPermission(PERMISSION_ADMIN);
    }

    /** Seconds this player still has to wait, 0 when they may announce now. */
    public int cooldownLeft(Player player) {
        if (!cooldownApplies(player)) return 0;
        Long last = lastSent.get(player.getUniqueId());
        if (last == null) return 0;
        long leftNanos = plugin.settings().announcement().cooldownSeconds() * 1_000_000_000L - (System.nanoTime() - last);
        return leftNanos <= 0 ? 0 : (int) Math.ceil(leftNanos / 1e9);
    }

    /**
     * An announcement by a player or the console.
     *
     * @param raw          the text as it came from the client or the command line
     * @param skipCooldown /announce test: neither checks nor starts the cooldown
     * @param atMicrophone the server knows the player is using a microphone, which stands in for intercom.announce
     */
    public Result submit(CommandSender sender, String raw, boolean skipCooldown, boolean atMicrophone) {
        if (!atMicrophone && !mayAnnounce(sender)) return new Result(Outcome.NO_PERMISSION, "", 0);
        Player player = sender instanceof Player p ? p : null;
        boolean cooldown = !skipCooldown && cooldownApplies(sender);
        if (cooldown) {
            int left = cooldownLeft(player);
            if (left > 0) return new Result(Outcome.COOLDOWN, raw == null ? "" : raw, left);
        }
        String name = player != null ? player.getName() : "Console";
        String prefix = player != null ? plugin.prefix(player) : "";
        // § is how a prefix may spell its colour codes; in the banner format they are all &
        String senderLine = plugin.settings().announcement().senderFormat()
            .replace("{prefix}", prefix.replace('§', '&')).replace("{name}", name);
        Result result = accept(player == null ? null : player.getUniqueId(), name, senderLine, raw, null);
        if (result.accepted()) {
            if (cooldown) lastSent.put(player.getUniqueId(), System.nanoTime());
            Sound confirm = plugin.settings().sounds().confirm().sound();
            // the pack's own sound is silent without the pack - which is fine for a confirmation
            if (confirm != null && player != null) player.playSound(confirm, Sound.Emitter.self());
        }
        return result;
    }

    /**
     * An announcement made by another plugin (see IntercomPlugin#announce): no permission, no cooldown.
     *
     * @param senderLine the line under the message, with {@code &} colour codes; empty = no sender line
     * @param openSound  played instead of the configured chime; null = the configured one
     */
    public Result submitAs(String senderName, String senderLine, String raw, Sound openSound) {
        return accept(null, senderName, senderLine, raw, openSound);
    }

    private Result accept(UUID senderId, String senderName, String senderFormat, String raw, Sound openSound) {
        Settings settings = plugin.settings();
        Settings.Announcement cfg = settings.announcement();
        FontWidths font = plugin.fontWidths();
        int maxPx = settings.banner().textWidth();

        // every line the sender typed is its own paragraph ("# " makes one large); each is wrapped by itself
        List<Line> lines = new ArrayList<>();
        StringBuilder cleaned = new StringBuilder(), plain = new StringBuilder();
        TextComponent.Builder chat = Component.text();
        int length = 0;
        // a typed "\n" also breaks the line: commands and other plugins have no other way to send two lines
        for (String rawLine : (raw == null ? "" : raw).replace("\\n", "\n").split("\\R")) {
            String text = Sanitizer.clean(rawLine, font);
            if (text.isEmpty()) continue;
            Line paragraph = Markup.parse(text, Line.WHITE);
            if (paragraph.isBlank()) continue;
            if (!cleaned.isEmpty()) {
                cleaned.append('\n');
                plain.append(' ');
                chat.append(Component.space());
                length++;
            }
            cleaned.append(text);
            plain.append(paragraph.plain());
            chat.append(chatComponent(paragraph));
            length += paragraph.length();
            lines.addAll(LineWrapper.wrap(font, paragraph, maxPx, cfg.maxCharsPerLine()));
        }
        if (lines.isEmpty()) return new Result(Outcome.BLANK, "", 0);
        if (length > cfg.maxCharacters()) return new Result(Outcome.TOO_LONG, cleaned.toString(), length);

        Line sender = null;
        if (cfg.showSender() && !senderFormat.isBlank()) {
            // the sender line goes through the same cleaning as the message: only banner-font characters reach the banner
            Line parsed = Markup.parseLegacy(Sanitizer.clean(senderFormat, font), Line.GRAY);
            if (!parsed.isBlank()) sender = LineWrapper.ellipsize(font, parsed, maxPx);
        }
        if (lines.size() > cfg.maxLines() || BannerText.height(lines, sender) > BannerText.MAX_HEIGHT) {
            return new Result(Outcome.DOES_NOT_FIT, cleaned.toString(), lines.size());
        }

        boolean wait = scheduler.busy() || !queue.isEmpty();
        if (wait && queue.size() >= cfg.queueSize()) return new Result(Outcome.QUEUE_FULL, cleaned.toString(), 0);

        Component chatSender = sender == null ? Component.text(senderName, NamedTextColor.GRAY) : chatComponent(sender);
        Announcement announcement = new Announcement(senderId, senderName, plain.toString(), lines, sender, chat.build(), chatSender, openSound);

        if (settings.logging().console()) {
            plugin.getLogger().info("Announcement by " + senderName + " (" + (senderId == null ? "server" : senderId) + "): " + announcement.text());
        }
        if (settings.logging().file()) log.append(announcement);

        if (wait) {
            queue.add(announcement);
            return new Result(Outcome.QUEUED, cleaned.toString(), queue.size());
        }
        scheduler.play(announcement, this::next);
        return new Result(Outcome.SENT, cleaned.toString(), 0);
    }

    /** The same styled text for chat (fallback and chat copy). */
    private static Component chatComponent(Line line) {
        TextComponent.Builder out = Component.text();
        for (int from = 0; from < line.length(); ) {
            int to = line.runEnd(from);
            int style = line.styles()[from];
            out.append(Component.text(new String(line.codePoints(), from, to - from), CHAT_COLORS[style & Line.COLOR_MASK])
                .decoration(TextDecoration.BOLD, (style & Line.BOLD) != 0 || line.large())
                .decoration(TextDecoration.ITALIC, (style & Line.ITALIC) != 0)
                .decoration(TextDecoration.UNDERLINED, (style & Line.UNDERLINE) != 0)
                .decoration(TextDecoration.STRIKETHROUGH, (style & Line.STRIKE) != 0));
            from = to;
        }
        return out.build();
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
