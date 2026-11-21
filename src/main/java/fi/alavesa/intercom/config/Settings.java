package fi.alavesa.intercom.config;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * config.yml, read once into immutable records: every value is validated and clamped here, so the rest of the
 * plugin never sees a number that makes no sense. A bad value is replaced by a sane one and reported once.
 */
public record Settings(Announcement announcement, Timing timing, Banner banner, PostEffect postEffect, Sounds sounds,
                       FallbackMode fallback, DialogUi dialog, Microphone microphone, Logging logging, Pack pack) {

    public static final String NAMESPACE = "intercom";

    /** Four double-size lines plus the sender line are the tallest banner the marker can describe (see BannerText). */
    public static final int MAX_LINES = 4;
    /** config.yml of an older layout is set aside and written anew (see IntercomPlugin). */
    public static final int CONFIG_VERSION = 2;
    /** The client-clock driver compares time modulo 64 ticks and treats 48+ as "not started" (shader). */
    public static final int MAX_SLIDE_TICKS = 40;

    public enum Driver { CLIENT_CLOCK, SERVER_TICKS }

    public enum Delivery { AUTO, SAME_PORT, HTTP, URL, NONE }

    public enum FallbackMode {
        CHAT, ACTIONBAR, BOTH, NONE;

        public boolean chat() { return this == CHAT || this == BOTH; }

        public boolean actionBar() { return this == ACTIONBAR || this == BOTH; }
    }

    public record Announcement(int maxCharacters, int maxLines, int maxCharsPerLine, int cooldownSeconds, int queueSize,
                               int gapTicks, boolean showSender, String senderFormat, boolean chatCopy) { }

    public record Timing(Driver driver, int enterTicks, int exitTicks, int holdBaseTicks, double holdTicksPerCharacter, int holdMaxTicks) {

        public int holdTicks(int characters) {
            return (int) Math.min(holdMaxTicks, Math.round(holdBaseTicks + characters * holdTicksPerCharacter));
        }
    }

    public record Banner(int width, int topOffset, int paddingX, double backgroundOpacity) {

        /** The widest a line of text may be, GUI pixels. */
        public int textWidth() { return width - 2 * paddingX; }
    }

    public record PostEffect(boolean enabled, int levels, double dim, double vignette, double desaturate) { }

    /** @param sound null = no sound */
    public record SoundSpec(Sound sound) { }

    public record Sounds(SoundSpec open, SoundSpec close, SoundSpec confirm, SoundSpec fallbackOpen) { }

    public record DialogUi(boolean fancy, int inputHeight, int inputMaxLines) { }

    public record Microphone(boolean enabled, double scale, double viewRange) { }

    public record Logging(boolean console, boolean file) { }

    /** @param shaderZips other plugins' pack zips whose text shader is merged, "name -> path relative to plugins/", most complete first */
    public record Pack(int formatMin, int formatMax, String description, Delivery delivery, String publicHost, String httpBind, int httpPort,
                       String url, String sha1, boolean required, String prompt, int sendDelayTicks, Map<String, String> shaderZips,
                       boolean globalButtonSkin) { }

    public static Settings read(Configuration c, Logger log) {
        Announcement announcement = new Announcement(
            clamp(c.getInt("announcement.max-characters", 120), 1, 256),
            clamp(c.getInt("announcement.max-lines", 3), 1, MAX_LINES),
            Math.max(0, c.getInt("announcement.max-chars-per-line", 48)),
            Math.max(0, c.getInt("announcement.cooldown-seconds", 300)),
            Math.max(0, c.getInt("announcement.queue-size", 5)),
            Math.max(0, c.getInt("announcement.gap-ticks", 10)),
            c.getBoolean("announcement.show-sender", true),
            c.getString("announcement.sender-format", "&8- {prefix}&7{name}"),
            c.getBoolean("announcement.chat-copy", false));

        Timing timing = new Timing(
            enumOf(Driver.class, c.getString("timing.driver", "client-clock"), Driver.CLIENT_CLOCK, "timing.driver", log),
            clamp(c.getInt("timing.enter-ticks", 10), 2, MAX_SLIDE_TICKS),
            clamp(c.getInt("timing.exit-ticks", 10), 2, MAX_SLIDE_TICKS),
            Math.max(1, c.getInt("timing.hold-base-ticks", 60)),
            Math.max(0, c.getDouble("timing.hold-ticks-per-character", 1.5)),
            Math.max(1, c.getInt("timing.hold-max-ticks", 300)));

        int width = clamp(c.getInt("banner.width", 280), 120, 312);
        width -= width % 2; // an even width keeps the box edges on whole pixels around the screen centre
        Banner banner = new Banner(
            width,
            clamp(c.getInt("banner.top-offset", 0), 0, 64),
            clamp(c.getInt("banner.padding-x", 10), 2, 40),
            clamp(c.getDouble("banner.background-opacity", 0.88), 0, 1));

        PostEffect postEffect = new PostEffect(
            c.getBoolean("post-effect.enabled", true),
            clamp(c.getInt("post-effect.levels", 6), 1, 16),
            clamp(c.getDouble("post-effect.dim", 0.22), 0, 1),
            clamp(c.getDouble("post-effect.vignette", 0.30), 0, 1),
            clamp(c.getDouble("post-effect.desaturate", 0.20), 0, 1));

        Sounds sounds = new Sounds(sound(c, "sounds.open", log), sound(c, "sounds.close", log), sound(c, "sounds.confirm", log),
            sound(c, "sounds.fallback-open", log));

        FallbackMode fallback = enumOf(FallbackMode.class, c.getString("fallback.mode", "chat"), FallbackMode.CHAT, "fallback.mode", log);

        DialogUi dialog = new DialogUi(
            c.getBoolean("dialog.fancy", true),
            clamp(c.getInt("dialog.input-height", 48), 20, 200),
            clamp(c.getInt("dialog.input-max-lines", 4), 1, 16));

        Microphone microphone = new Microphone(
            c.getBoolean("microphone.enabled", true),
            clamp(c.getDouble("microphone.scale", 1.0), 0.1, 8),
            clamp(c.getDouble("microphone.view-range", 1.0), 0.05, 16));

        Logging logging = new Logging(c.getBoolean("logging.console", true), c.getBoolean("logging.file", true));

        int formatMin = Math.max(1, c.getInt("resourcepack.format.min", 97));
        Map<String, String> shaderZips = new LinkedHashMap<>();
        ConfigurationSection zips = c.getConfigurationSection("resourcepack.text-shader-sources");
        if (zips != null) for (String key : zips.getKeys(false)) if (!zips.getString(key, "").isBlank()) shaderZips.put(key, zips.getString(key));
        Pack pack = new Pack(
            formatMin,
            Math.max(formatMin, c.getInt("resourcepack.format.max", 97)),
            c.getString("resourcepack.description", "Intercom"),
            enumOf(Delivery.class, c.getString("resourcepack.delivery", "auto"), Delivery.AUTO, "resourcepack.delivery", log),
            c.getString("resourcepack.public-host", "").trim(),
            c.getString("resourcepack.http.bind", "0.0.0.0").trim(),
            clamp(c.getInt("resourcepack.http.port", 8078), 1, 65535),
            c.getString("resourcepack.url", "").trim(),
            c.getString("resourcepack.sha1", "").trim().toLowerCase(Locale.ROOT),
            c.getBoolean("resourcepack.required", false),
            c.getString("resourcepack.prompt", ""),
            Math.max(0, c.getInt("resourcepack.send-delay-ticks", 120)),
            shaderZips,
            c.getBoolean("resourcepack.global-button-skin", false));

        return new Settings(announcement, timing, banner, postEffect, sounds, fallback, dialog, microphone, logging, pack);
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String raw, E fallback, String path, Logger log) {
        try {
            return Enum.valueOf(type, String.valueOf(raw).trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            log.warning(path + " '" + raw + "' is not a known value - using " + fallback.name().toLowerCase(Locale.ROOT).replace('_', '-'));
            return fallback;
        }
    }

    @SuppressWarnings("PatternValidation") // the key is checked with Key.parseable first
    private static SoundSpec sound(Configuration c, String path, Logger log) {
        String key = c.getString(path + ".key", "").trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return new SoundSpec(null);
        if (!Key.parseable(key)) {
            log.warning(path + ".key '" + key + "' is not a sound id like \"intercom:chime_open\" - no sound is played");
            return new SoundSpec(null);
        }
        Sound.Source source = enumOf(Sound.Source.class, c.getString(path + ".category", "master"), Sound.Source.MASTER, path + ".category", log);
        return new SoundSpec(Sound.sound(Key.key(key), source,
            (float) clamp(c.getDouble(path + ".volume", 1), 0, 10), (float) clamp(c.getDouble(path + ".pitch", 1), 0.5, 2)));
    }
}
