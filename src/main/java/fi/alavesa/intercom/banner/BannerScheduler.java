package fi.alavesa.intercom.banner;

import fi.alavesa.intercom.IntercomPlugin;
import fi.alavesa.intercom.announce.Announcement;
import fi.alavesa.intercom.config.Messages;
import fi.alavesa.intercom.config.Settings;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Plays ONE announcement to everybody online: slide in, hold, slide out, a short gap, then {@code onDone}.
 * <p>
 * The banner is a boss bar: its name is the whole banner ({@link BannerText}), the pack's text shader moves
 * it into place and animates it, and the pack blanks the blue bar graphic. Every player is sent the bar,
 * also those whose client did not load the pack. Without the pack's font and shader the name is drawn as
 * placeholder boxes at the top of the screen, so those players get the configured fallback (chat / action
 * bar) as well; so does everyone when the pack carries no banner shader at all.
 * <p>
 * Why a boss bar and not the action bar or a title: it is the only HUD text channel that several plugins can
 * use at once without overwriting each other (each bar is its own object), it stays exactly as long as the
 * server says, and its name can be replaced in place. Action bars and titles are single slots that any other
 * plugin or vanilla message replaces, and they fade on their own timer.
 * <p>
 * Timing is counted in server ticks here; what a tick means on screen is in the pack's
 * intercom_text_vertex.glsl. Main thread only (Bukkit scheduler - not Folia).
 */
public final class BannerScheduler {

    private enum Phase { ENTER, HOLD, EXIT, GAP }

    /** Ticks the bar stays after the slide-out should be over: lets a client whose clock is a little behind finish. */
    private static final int EXIT_SLACK_TICKS = 2;
    /** An action bar message fades after about three seconds; it is repeated at this pace while the banner shows. */
    private static final int ACTION_BAR_REPEAT_TICKS = 40;

    private final IntercomPlugin plugin;
    private final PostDim dim = new PostDim();

    private Announcement current;
    private Runnable onDone;
    private Settings settings;
    private Phase phase;
    private int ticks, holdTicks;
    /** Players who see the banner -> their boss bar. */
    private final Map<UUID, BossBar> viewers = new HashMap<>();
    /** Players who get the action bar fallback. */
    private final Set<UUID> actionBarViewers = new HashSet<>();
    private Component actionBar;
    private BukkitTask task;

    public BannerScheduler(IntercomPlugin plugin) { this.plugin = plugin; }

    public boolean busy() { return current != null; }

    /**
     * Starts showing {@code announcement} to everybody online now. Players who join later do not see it.
     *
     * @param onDone run once, after the banner is gone and the gap has passed (not run by {@link #stop})
     */
    public void play(Announcement announcement, Runnable onDone) {
        if (busy()) throw new IllegalStateException("an announcement is already playing");
        this.current = announcement;
        this.onDone = onDone;
        this.settings = plugin.settings(); // one snapshot per announcement: a reload never changes it half-way
        this.phase = Phase.ENTER;
        this.ticks = 0;
        this.holdTicks = settings.timing().holdTicks(announcement.text().length());

        Messages messages = plugin.messages();
        Settings.Sounds sounds = settings.sounds();
        Component chat = messages.component("chat-format", Messages.tag("message", announcement.chat()), Messages.tag("sender", announcement.chatSender()));
        actionBar = messages.component("actionbar-format", Messages.tag("message", announcement.chat()), Messages.tag("sender", announcement.chatSender()));
        Settings.SoundSpec open = announcement.openSound() == null ? sounds.open() : new Settings.SoundSpec(announcement.openSound());
        Settings.SoundSpec fallbackOpen = sounds.fallbackOpen();
        boolean banner = plugin.delivery().bannerReady();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (banner) {
                // BLUE: the bar graphic the pack blanks (no vanilla mob has a blue bar). Progress 0: nothing to fill.
                BossBar bar = BossBar.bossBar(name(player, BannerText.PHASE_ENTER, 0), 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
                viewers.put(player.getUniqueId(), bar);
                player.showBossBar(bar);
                // a client without the pack cannot play a pack sound: it gets the vanilla one
                play(player, plugin.delivery().packLoaded(player) ? open : fallbackOpen);
            }
            // unreadable without the pack's font: these players need the words some other way too
            boolean readable = banner && plugin.delivery().packLoaded(player);
            if (!readable) {
                if (settings.fallback().actionBar() && actionBar != null) {
                    actionBarViewers.add(player.getUniqueId());
                    player.sendActionBar(actionBar);
                }
                if (!banner) play(player, fallbackOpen);
            }
            // no banner at all (the pack could not be built): chat is the only way the announcement is heard
            if (chat != null && (settings.announcement().chatCopy() || !banner || (!readable && settings.fallback().chat()))) player.sendMessage(chat);
        }
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Removes the banner from everyone at once. Does not run {@code onDone}. */
    public void stop() {
        if (task != null) task.cancel();
        task = null;
        for (Map.Entry<UUID, BossBar> viewer : viewers.entrySet()) {
            Player player = Bukkit.getPlayer(viewer.getKey());
            if (player != null) player.hideBossBar(viewer.getValue());
        }
        viewers.clear();
        actionBarViewers.clear();
        dim.clearAll();
        current = null;
        onDone = null;
    }

    /** A player left: their bar and dim are gone with the connection. */
    public void forget(Player player) {
        viewers.remove(player.getUniqueId());
        actionBarViewers.remove(player.getUniqueId());
        dim.forget(player.getUniqueId());
    }

    private void tick() {
        ticks++;
        Settings.Timing timing = settings.timing();
        switch (phase) {
            case ENTER -> {
                if (ticks >= timing.enterTicks()) {
                    enter(Phase.HOLD);
                    rename(BannerText.PHASE_HOLD, 0);
                    dimAll(1.0);
                } else {
                    double t = ticks / (double) timing.enterTicks();
                    double left = 1 - t;
                    step(1 - left * left * left); // ease-out cubic, the same curve as the shader's
                    dimAll(t);
                }
            }
            case HOLD -> {
                if (ticks % ACTION_BAR_REPEAT_TICKS == 0) repeatActionBar();
                if (ticks >= holdTicks) {
                    enter(Phase.EXIT);
                    if (settings.timing().driver() == Settings.Driver.CLIENT_CLOCK) rename(BannerText.PHASE_EXIT, 0);
                    else step(1.0);
                    for (UUID id : viewers.keySet()) {
                        Player player = Bukkit.getPlayer(id);
                        if (player != null) play(player, settings.sounds().close());
                    }
                }
            }
            case EXIT -> {
                double t = Math.min(1.0, ticks / (double) timing.exitTicks());
                step(1 - t * t * t); // ease-in cubic
                dimAll(1 - t);
                if (ticks >= timing.exitTicks() + EXIT_SLACK_TICKS) {
                    for (Map.Entry<UUID, BossBar> viewer : viewers.entrySet()) {
                        Player player = Bukkit.getPlayer(viewer.getKey());
                        if (player != null) player.hideBossBar(viewer.getValue());
                    }
                    viewers.clear();
                    actionBarViewers.clear();
                    dim.clearAll();
                    enter(Phase.GAP);
                }
            }
            case GAP -> {
                if (ticks >= settings.announcement().gapTicks()) {
                    task.cancel();
                    task = null;
                    current = null;
                    Runnable done = onDone;
                    onDone = null;
                    if (done != null) done.run(); // may start the next announcement
                }
            }
        }
    }

    private void enter(Phase next) {
        phase = next;
        ticks = 0;
    }

    /** The banner's boss bar name for one player. ENTER/EXIT carry that player's world clock (client-clock driver). */
    private Component name(Player player, int bannerPhase, int step) {
        boolean clock = settings.timing().driver() == Settings.Driver.CLIENT_CLOCK;
        int encodedPhase = bannerPhase;
        long time = 0;
        if (bannerPhase == BannerText.PHASE_ENTER || bannerPhase == BannerText.PHASE_EXIT) {
            if (clock) {
                // the clock the client animates against is the game time of the world the player is in
                time = player.getWorld().getGameTime();
            } else {
                encodedPhase = BannerText.PHASE_STEP;
                time = step;
            }
        }
        return BannerText.build(plugin.fontWidths(), current.lines(), current.senderLine(), encodedPhase, time);
    }

    /** New phase: every viewer's bar gets the name for it. */
    private void rename(int bannerPhase, int step) {
        for (Iterator<Map.Entry<UUID, BossBar>> it = viewers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, BossBar> viewer = it.next();
            Player player = Bukkit.getPlayer(viewer.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            viewer.getValue().name(name(player, bannerPhase, step));
        }
    }

    /** server-ticks driver: sends how much of the banner is shown (0..1). The client-clock driver needs nothing per tick. */
    private void step(double shown) {
        if (settings.timing().driver() != Settings.Driver.SERVER_TICKS) return;
        int value = (int) Math.round(Math.max(0, Math.min(1, shown)) * BannerText.TIME_MASK);
        // PHASE_ENTER here only selects "animated"; name() turns it into STEP with this value
        rename(BannerText.PHASE_ENTER, value);
    }

    /** @param amount 0..1 how far the dim is faded in */
    private void dimAll(double amount) {
        Settings.PostEffect post = settings.postEffect();
        if (!post.enabled()) return;
        int level = (int) Math.ceil(Math.max(0, Math.min(1, amount)) * post.levels());
        for (UUID id : viewers.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) dim.set(player, level);
        }
    }

    private void repeatActionBar() {
        if (actionBar == null) return;
        for (UUID id : actionBarViewers) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.sendActionBar(actionBar);
        }
    }

    private static void play(Player player, Settings.SoundSpec spec) {
        // Emitter.self(): the sound follows the listener, so it is equally loud wherever the player stands
        if (spec.sound() != null) player.playSound(spec.sound(), Sound.Emitter.self());
    }
}
