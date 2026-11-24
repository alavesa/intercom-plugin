package fi.alavesa.intercom.pack;

import fi.alavesa.intercom.IntercomPlugin;
import fi.alavesa.intercom.config.Messages;
import fi.alavesa.intercom.config.Settings;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hosts and sends the generated pack as an ADDITIONAL server resource pack (replace = false), so the server's
 * own pack and other plugins' packs keep working - and keeps track of which players' clients have it
 * ({@link PlayerResourcePackStatusEvent}). That knowledge is for the status read-out and for choosing a sound a
 * client can play; it never decides who is shown the banner or the dialog - everybody is.
 * <p>
 * resourcepack.delivery:
 * <ul>
 *   <li>auto (default): same-port, or http when the Minecraft port cannot be hooked</li>
 *   <li>same-port: served on the Minecraft port itself (nothing to open in a firewall; not behind a proxy)</li>
 *   <li>http: served by the built-in HTTP server on resourcepack.http.port (players must be able to reach it)</li>
 *   <li>url: you upload plugins/Intercom/Intercom-pack.zip yourself; resourcepack.url points at it</li>
 *   <li>none: you merged the pack into your server pack; nothing is sent, and a player has the pack once their
 *       client reports any server pack as loaded</li>
 * </ul>
 * Main thread only.
 */
public final class PackDelivery implements Listener {

    /** Fixed id: clients and the status event identify our pack by it across restarts. */
    public static final UUID PACK_ID = UUID.nameUUIDFromBytes("intercom:pack".getBytes(StandardCharsets.UTF_8));

    /**
     * How often our pack is sent again to stay above one other pack that loaded after it, per player and session.
     * Counted per other pack: with many plugins sending packs one total ran out, and the last pack to load - often
     * SCP079's - then stayed above ours and the banner was not drawn.
     */
    private static final int MAX_REPUSHES = 3;

    private final IntercomPlugin plugin;
    private final Logger log;
    private final Map<UUID, PlayerResourcePackStatusEvent.Status> status = new HashMap<>();
    private final Map<UUID, Map<UUID, Integer>> repushes = new HashMap<>();
    private final Set<UUID> sent = new HashSet<>();
    private final Map<UUID, Set<UUID>> pushedAfterOurs = new HashMap<>();
    /** delivery none: players whose client reported some server pack as loaded. */
    private final Set<UUID> anyPackLoaded = new HashSet<>();
    private PackHttpServer http;
    private SamePortHttp samePort;
    private Settings.Delivery effective = Settings.Delivery.NONE;
    private String servedSha1 = "";
    private volatile boolean downloadCheckOk;

    public PackDelivery(IntercomPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    private Settings.Pack cfg() { return plugin.settings().pack(); }

    /** The way the pack reaches players right now (never AUTO). */
    public Settings.Delivery delivery() { return effective; }

    /**
     * (Re)starts hosting for the pack that was just built, and sends it to everybody online when its bytes
     * changed. Call after every build and settings reload.
     */
    public void apply() {
        stop();
        Settings.Pack cfg = cfg();
        String sha1 = plugin.packSha1();
        File zip = plugin.getDataFolder().toPath().resolve(PackBuilder.ZIP_NAME).toFile();
        effective = cfg.delivery();
        if (sha1.isEmpty()) {
            effective = Settings.Delivery.NONE;
            return; // no pack was written; the build already said why
        }
        if (cfg.delivery() == Settings.Delivery.SAME_PORT || cfg.delivery() == Settings.Delivery.AUTO) {
            SamePortHttp h = new SamePortHttp(Bukkit.getServer(), "intercom", path -> zip);
            String problem = h.install();
            if (problem == null) {
                samePort = h;
                effective = Settings.Delivery.SAME_PORT;
                selfTest(sha1);
            } else {
                log.warning("Could not serve the pack on the Minecraft port (" + problem + ")"
                    + (cfg.delivery() == Settings.Delivery.AUTO ? "; using the HTTP server on port " + cfg.httpPort() : ""));
                effective = Settings.Delivery.HTTP;
            }
        }
        if (effective == Settings.Delivery.HTTP) {
            try {
                http = new PackHttpServer(cfg.httpBind(), cfg.httpPort());
                http.serve(Files.readAllBytes(zip.toPath()), sha1);
            } catch (IOException e) {
                log.log(Level.SEVERE, "Could not start the pack HTTP server on " + cfg.httpBind() + ":" + cfg.httpPort()
                    + " (port in use? set resourcepack.http.port or resourcepack.delivery: url)", e);
            }
        }
        if (effective == Settings.Delivery.URL) {
            if (cfg.url().isBlank()) log.warning("resourcepack.delivery is url but resourcepack.url is empty: the pack is not sent");
            else if (!cfg.sha1().isEmpty() && !cfg.sha1().equals(sha1)) {
                log.warning("resourcepack.sha1 in config.yml differs from the pack this plugin built (" + sha1 + "). Upload plugins/Intercom/"
                    + PackBuilder.ZIP_NAME + " again, or players get a banner that does not fit the plugin's settings.");
            }
        }
        if (!sha1.equals(servedSha1)) {
            boolean first = servedSha1.isEmpty();
            servedSha1 = sha1;
            // at start-up nobody is online, and joins send the pack themselves
            if (!first) for (Player player : Bukkit.getOnlinePlayers()) send(player);
        }
    }

    /**
     * Downloads our own pack once through the Minecraft port, the way a client would, and logs the answer. A 404 or
     * a refused connection here is what players' clients get too (another plugin answering downloads on the port,
     * or a proxy in front of the server) - the console then says so instead of the banner silently missing.
     */
    private void selfTest(String sha1) {
        // a few seconds after start-up: the other plugins have installed their own handlers by then
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            String url = "http://127.0.0.1:" + Bukkit.getPort() + "/intercom/" + sha1 + ".zip";
            try {
                HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                int code = c.getResponseCode();
                long length = c.getContentLengthLong();
                c.disconnect();
                if (code == 200) {
                    // the pack is rebuilt whenever a sibling plugin's pack changes: say "OK" once, problems every time
                    if (!downloadCheckOk) log.info("Pack download check: OK (" + length + " bytes through the Minecraft port)");
                    downloadCheckOk = true;
                }
                else log.warning("Pack download check: " + url + " answered HTTP " + code + ". Players' clients get the same answer, so they"
                    + " do not load the Intercom pack and see the banner in plain text. Usually another plugin is answering downloads on"
                    + " the Minecraft port: update it, or set resourcepack.delivery: http (own port) or url.");
            } catch (IOException e) {
                log.warning("Pack download check: " + url + " failed (" + e.getMessage() + "). Set resourcepack.delivery: http or url.");
            }
        }, 100L);
    }

    public void stop() {
        if (http != null) http.stop();
        http = null;
        if (samePort != null) samePort.uninstall();
        samePort = null;
    }

    // ------------------------------------------------------------------ who has the pack

    /** Has this player's client loaded the pack (sounds, dialog textures)? */
    public boolean packLoaded(Player player) {
        UUID id = player.getUniqueId();
        if (effective == Settings.Delivery.NONE) return cfg().delivery() == Settings.Delivery.NONE && anyPackLoaded.contains(id);
        return status.get(id) == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;
    }

    /**
     * Can the drawn banner be shown? True whenever the pack carries the banner shader. Players whose client has not
     * loaded the pack get the plain version ({@code PlainBanner}) instead, either way.
     */
    public boolean bannerReady() {
        return plugin.bannerShaderShipped();
    }

    // ------------------------------------------------------------------ sending

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        forget(player);
        // after the other plugins' packs (they send at join): the pack pushed last is the one on top
        int delay = cfg().sendDelayTicks();
        if (delay == 0) send(player);
        else Bukkit.getScheduler().runTaskLater(plugin, () -> send(player), delay);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer());
    }

    private void forget(Player player) {
        UUID id = player.getUniqueId();
        status.remove(id);
        sent.remove(id);
        repushes.remove(id);
        pushedAfterOurs.remove(id);
        anyPackLoaded.remove(id);
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        PlayerResourcePackStatusEvent.Status st = event.getStatus();
        if (st == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) anyPackLoaded.add(id);
        if (PACK_ID.equals(event.getID())) {
            // DISCARDED is about the copy an earlier push left behind (we push again to stay on top)
            if (st == PlayerResourcePackStatusEvent.Status.DISCARDED) return;
            status.put(id, st);
            if (st == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD || st == PlayerResourcePackStatusEvent.Status.FAILED_RELOAD
                || st == PlayerResourcePackStatusEvent.Status.INVALID_URL) {
                log.warning(player.getName() + " could not load the resource pack (" + st + ") from " + uri(player)
                    + ". They see the banner and the dialog without their textures. If the address answers 404, another plugin is answering"
                    + " downloads on the Minecraft port; behind a proxy set resourcepack.delivery: http or url.");
            } else if (st == PlayerResourcePackStatusEvent.Status.DECLINED) {
                log.info(player.getName() + " declined the Intercom resource pack - they get the banner in Minecraft's own font");
            }
            return;
        }
        // Another pack was pushed after ours (ACCEPTED is the client's first answer to a push) and has now
        // loaded: it sits above ours, and if it carries a text shader without our lines, the banner does not
        // work. Send ours again - capped, so two plugins doing the same cannot ping-pong for ever.
        if (!plugin.bannerShaderShipped() || !sent.contains(id)) return;
        Set<UUID> newer = pushedAfterOurs.computeIfAbsent(id, k -> new HashSet<>());
        if (st == PlayerResourcePackStatusEvent.Status.ACCEPTED) {
            newer.add(event.getID());
        } else if (st == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED && newer.remove(event.getID())) {
            if (repushes.computeIfAbsent(id, k -> new HashMap<>()).merge(event.getID(), 1, Integer::sum) <= MAX_REPUSHES) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> send(player), 20L);
            }
        }
    }

    /** @return false when no pack can be sent (delivery none, no pack, no URL) */
    public boolean send(Player player) {
        if (effective == Settings.Delivery.NONE || servedSha1.isEmpty() || !player.isOnline()) return false;
        URI uri = uri(player);
        if (uri == null) return false;
        sent.add(player.getUniqueId());
        pushedAfterOurs.remove(player.getUniqueId());
        ResourcePackRequest.Builder request = ResourcePackRequest.resourcePackRequest()
            .packs(ResourcePackInfo.resourcePackInfo(PACK_ID, uri, hash()))
            .replace(false)
            .required(cfg().required());
        if (!cfg().prompt().isBlank()) request.prompt(Messages.parse(cfg().prompt()));
        player.sendResourcePacks(request.build());
        return true;
    }

    /** The SHA-1 the client is told: for a self-hosted zip the configured one, when set. */
    private String hash() {
        Settings.Pack cfg = cfg();
        return effective == Settings.Delivery.URL && cfg.sha1().matches("[0-9a-f]{40}") ? cfg.sha1() : servedSha1;
    }

    /** Where this player downloads the pack from, or null when there is nowhere. */
    public URI uri(Player player) {
        Settings.Pack cfg = cfg();
        try {
            if (effective == Settings.Delivery.URL) return cfg.url().isBlank() ? null : URI.create(cfg.url());
            String host = cfg.publicHost();
            InetSocketAddress virtualHost = player.getConnection().getVirtualHost();
            if (host.isBlank()) {
                // the host name the player typed to connect is the one that reaches this machine
                host = virtualHost == null ? "" : virtualHost.getHostString();
                int nul = host.indexOf('\0');
                if (nul >= 0) host = host.substring(0, nul);
                if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            }
            if (host.isBlank()) host = Bukkit.getIp().isBlank() ? "localhost" : Bukkit.getIp();
            if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]"; // IPv6 literal
            int port = cfg.httpPort();
            if (effective == Settings.Delivery.SAME_PORT) {
                port = Bukkit.getPort();
                if (cfg.publicHost().isBlank() && virtualHost != null && virtualHost.getPort() > 0) port = virtualHost.getPort();
            }
            return URI.create("http://" + host + ":" + port + "/intercom/" + servedSha1 + ".zip");
        } catch (IllegalArgumentException e) {
            log.warning("The pack address is not a valid URL (" + e.getMessage() + ") - check resourcepack.url / resourcepack.public-host");
            return null;
        }
    }
}
