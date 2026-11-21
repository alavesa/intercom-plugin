package fi.alavesa.intercom;

import fi.alavesa.intercom.announce.AnnouncementLog;
import fi.alavesa.intercom.announce.AnnouncementManager;
import fi.alavesa.intercom.banner.BannerScheduler;
import fi.alavesa.intercom.command.AnnounceCommand;
import fi.alavesa.intercom.config.Messages;
import fi.alavesa.intercom.config.Settings;
import fi.alavesa.intercom.dialog.ComposeDialog;
import fi.alavesa.intercom.pack.Bundle;
import fi.alavesa.intercom.pack.PackAssets;
import fi.alavesa.intercom.pack.PackBuilder;
import fi.alavesa.intercom.pack.PackDelivery;
import fi.alavesa.intercom.text.FontWidths;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Intercom: {@code /announce} opens a dialog, and what is sent there slides down from the top of every
 * player's screen as a banner drawn by the resource pack's text shader.
 * <p>
 * Everything runs on the server thread (Bukkit scheduler; the plugin is not Folia-aware) except the pack's
 * HTTP hosts and the announcement log file, which have their own threads and touch no Bukkit API.
 */
public final class IntercomPlugin extends JavaPlugin implements Listener {

    private static final String OVERRIDES_DIR = "pack-overrides";

    private Settings settings;
    private Messages messages;
    private FontWidths fontWidths;

    private BannerScheduler banners;
    private AnnouncementLog announcementLog;
    private AnnouncementManager announcements;
    private ComposeDialog dialog;
    private PackDelivery delivery;

    private String packSha1 = "";
    private boolean bannerShader;
    private String shaderDescription = "not built";
    private String sourceSignature = "";
    private BukkitTask sourceTask;

    @Override
    public void onEnable() {
        try (InputStream in = getResource("font/default_widths.tsv")) {
            if (in == null) throw new IOException("font/default_widths.tsv is missing from the jar");
            fontWidths = new FontWidths(in);
        } catch (IOException e) {
            throw new IllegalStateException("The font width table could not be read", e);
        }
        loadFiles();

        banners = new BannerScheduler(this);
        announcementLog = new AnnouncementLog(getDataFolder().toPath().resolve("announcements.log"), getLogger());
        announcements = new AnnouncementManager(this, banners, announcementLog);
        dialog = new ComposeDialog(this);
        delivery = new PackDelivery(this);

        buildPack();
        delivery.apply();

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(dialog, this);
        getServer().getPluginManager().registerEvents(delivery, this);
        new AnnounceCommand(this).register();

        // Other plugins build their packs at start (some after us) and on their own reloads: when one of the
        // zips the text shader was merged from changes, merge again.
        sourceTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!PackAssets.sourceSignature(getDataFolder().getParentFile(), settings.pack()).equals(sourceSignature)) {
                getLogger().info("Another plugin's pack changed - merging the text shader again");
                buildPack();
                delivery.apply();
            }
        }, 100L, 100L);

        getLogger().info("Pack delivery " + delivery.delivery() + ", text shader: " + shaderDescription);
    }

    @Override
    public void onDisable() {
        if (sourceTask != null) sourceTask.cancel();
        // takes the banner, its boss bars and the screen dim away from everyone, and drops the queue
        if (announcements != null) announcements.clear();
        if (delivery != null) delivery.stop();
        if (announcementLog != null) announcementLog.close();
    }

    /** /announce reload: stops what is on screen, re-reads the files, rebuilds the pack and re-sends it when it changed. */
    public void reload() {
        announcements.clear();
        loadFiles();
        buildPack();
        delivery.apply();
    }

    /** config.yml and messages.yml -> memory. */
    private void loadFiles() {
        saveDefaultConfig();
        reloadConfig();
        settings = Settings.read(getConfig(), getLogger());

        File messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) saveResource("messages.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(messagesFile);
        try (InputStream in = getResource("messages.yml")) {
            // the bundled file as defaults, so keys added by an update work without editing anything
            if (in != null) yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException ignored) {
            // the defaults are a convenience only
        }
        messages = new Messages(yaml);

        Path overrides = getDataFolder().toPath().resolve(OVERRIDES_DIR);
        if (!Files.exists(overrides)) {
            try {
                Files.createDirectories(overrides);
                Files.writeString(overrides.resolve("README.txt"), """
                    Files in this folder replace (or add to) the files of the Intercom resource pack.
                    Use the same path as in the pack, for example:

                      pack-overrides/assets/intercom/textures/font/button_send.png
                      pack-overrides/assets/intercom/sounds/chime_open.ogg

                    The current pack is in ../resourcepack/ - copy a file from there, change it, put it here,
                    then run /announce reload. Sizes and formats are in the plugin's README.
                    """);
            } catch (IOException e) {
                getLogger().warning("The pack-overrides folder could not be created (" + e.getMessage() + ")");
            }
        }
    }

    /**
     * Builds the resource pack from the files in the jar, config.yml, pack-overrides/ and the other plugins'
     * text shaders. A few kilobytes of work, done on the server thread: at start-up and on reload only.
     */
    private void buildPack() {
        File plugins = getDataFolder().getParentFile();
        sourceSignature = PackAssets.sourceSignature(plugins, settings.pack());
        try {
            PackAssets.Pack pack = PackAssets.collect(Bundle.fromJar(getFile()), plugins, getDataFolder().toPath().resolve(OVERRIDES_DIR), settings);
            pack.warnings().forEach(getLogger()::warning);
            PackBuilder.Written written = PackBuilder.write(getDataFolder().toPath(), pack.files());
            packSha1 = written.sha1();
            bannerShader = pack.bannerShader();
            shaderDescription = pack.shaderDescription();
        } catch (IOException | RuntimeException e) {
            getLogger().severe("The resource pack could not be built (" + e + ") - announcements use the fallback for everyone");
            packSha1 = "";
            bannerShader = false;
            shaderDescription = "none (pack not built)";
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        banners.forget(event.getPlayer());
        announcements.forget(event.getPlayer());
    }

    public Settings settings() { return settings; }

    public Messages messages() { return messages; }

    public FontWidths fontWidths() { return fontWidths; }

    public BannerScheduler banners() { return banners; }

    public AnnouncementManager announcements() { return announcements; }

    public ComposeDialog dialog() { return dialog; }

    public PackDelivery delivery() { return delivery; }

    /** SHA-1 of the pack zip, or "" when it could not be built. */
    public String packSha1() { return packSha1; }

    /** True when the pack carries the text shader the banner needs. */
    public boolean bannerShaderShipped() { return bannerShader; }

    public String shaderDescription() { return shaderDescription; }
}
