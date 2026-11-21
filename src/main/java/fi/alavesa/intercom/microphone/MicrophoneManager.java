package fi.alavesa.intercom.microphone;

import fi.alavesa.intercom.IntercomPlugin;
import fi.alavesa.intercom.announce.AnnouncementManager;
import fi.alavesa.intercom.bbmodel.ModelGeometry;
import fi.alavesa.intercom.config.Messages;
import fi.alavesa.intercom.config.Settings;
import fi.alavesa.intercom.util.Affine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The microphone "block": right-click it to compose an announcement.
 * <p>
 * It is not a block but two persistent entities at one position - an item display showing the pack model
 * {@code intercom:microphone} (converted from models/microphone.bbmodel) and an Interaction as its hitbox.
 * Both carry a marker in their persistent data, so nothing has to be stored in a file: the world is the
 * record, and a microphone survives restarts, chunk unloads and world copies. The facing is stored on the
 * display so the transformation can be rebuilt when the model file changes.
 * <p>
 * Placing (with the microphone item) and removing (sneak + right-click) need {@code intercom.admin}; using a
 * microphone needs {@code intercom.microphone}, which every player has by default.
 * Main thread only.
 */
public final class MicrophoneManager implements Listener {

    public static final String PERMISSION_USE = "intercom.microphone";

    /** How far from the microphone a player may be when the dialog comes back (they opened it standing next to it). */
    private static final double SUBMIT_RANGE = 8;
    /** A dialog opened at a microphone is good for this long. */
    private static final long SESSION_MILLIS = 5 * 60 * 1000L;

    private record Session(UUID world, double x, double y, double z, long opened) { }

    private final IntercomPlugin plugin;
    private final NamespacedKey markerKey, yawKey, itemKey;
    /** Players who opened the dialog at a microphone -> where and when. */
    private final Map<UUID, Session> sessions = new HashMap<>();

    public MicrophoneManager(IntercomPlugin plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "microphone");
        this.yawKey = new NamespacedKey(plugin, "microphone_yaw");
        this.itemKey = new NamespacedKey(plugin, "microphone_item");
    }

    // ------------------------------------------------------------------ the item

    public ItemStack createItem() {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        Component name = plugin.messages().component("microphone.item-name");
        if (name != null) meta.customName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        meta.setItemModel(new NamespacedKey(Settings.NAMESPACE, MicrophoneModel.ID));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    private boolean isItem(ItemStack stack) {
        return stack != null && stack.hasItemMeta() && stack.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    private boolean isMicrophone(Entity entity) {
        return entity.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ placing and removing

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND || !isItem(event.getItem())) return;
        event.setCancelled(true); // never use the paper item for anything else
        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        if (!player.hasPermission(AnnouncementManager.PERMISSION_ADMIN)) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        Block target = clicked.getRelative(event.getBlockFace());
        Location at = target.getLocation().add(0.5, 0, 0.5);
        // the model's front (the talk button) faces the player who placed it, in steps of 45 degrees
        float yaw = Math.round((player.getLocation().getYaw() + 180) / 45f) * 45f;
        spawn(at, yaw);
        if (player.getGameMode() != GameMode.CREATIVE) event.getItem().subtract();
        plugin.messages().send(player, "microphone.placed");
    }

    /**
     * Puts a microphone at {@code at} (the bottom centre of where it stands).
     *
     * @param yaw the direction its front (the talk button) faces, Minecraft yaw
     */
    public void spawn(Location at, float yaw) {
        World world = at.getWorld();
        Settings.Microphone cfg = plugin.settings().microphone();
        ModelGeometry geometry = plugin.microphoneGeometry();
        world.spawn(at, ItemDisplay.class, display -> {
            display.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
            display.getPersistentDataContainer().set(yawKey, PersistentDataType.FLOAT, yaw);
            display.setPersistent(true);
            display.setInvulnerable(true);
            display.setShadowRadius(0);
            display.setShadowStrength(0);
            apply(display, yaw, cfg, geometry);
        });
        world.spawn(at, Interaction.class, hitbox -> {
            hitbox.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
            hitbox.setResponsive(true); // the client swings its arm on click, like on a block
            hitbox.setPersistent(true);
            hitbox.setInvulnerable(true);
            apply(hitbox, cfg, geometry);
        });
    }

    /**
     * Model space is Blockbench's: pixels, front towards north (-z). The item model's elements are
     * {@code 8 + fit * (p - c)} (see {@link ModelGeometry}); with the display context NONE the client draws them at
     * {@code (g - 8) / 16} and - for item displays - turned by 180 degrees about y. A microphone facing yaw
     * {@code f} therefore gets left rotation {@code Ry(-f)} (the client's half turn completes it to
     * {@code Ry(180 - f)}), scale {@code scale / fit} and translation {@code Ry(180 - f) * (c - origin) * scale / 16}.
     */
    private static void apply(ItemDisplay display, float yaw, Settings.Microphone cfg, ModelGeometry g) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.setItemModel(new NamespacedKey(Settings.NAMESPACE, MicrophoneModel.ID));
        stack.setItemMeta(meta);
        display.setItemStack(stack);
        // NONE: the model's own display transforms (meant for hands and inventories) are not applied
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        display.setViewRange((float) cfg.viewRange());
        display.setInterpolationDuration(0);
        display.setTeleportDuration(0);
        if (g == null) return; // the model could not be read: the display stays where it is until it can
        double k = cfg.scale() / 16;
        double[] t = Affine.rotationY(180 - yaw).applyDirection(new double[] {g.fitCenter[0] - g.origin[0], g.fitCenter[1] - g.origin[1], g.fitCenter[2] - g.origin[2]});
        display.setTransformation(new Transformation(
            new Vector3f((float) (t[0] * k), (float) (t[1] * k), (float) (t[2] * k)),
            new Quaternionf().rotationY((float) Math.toRadians(-yaw)),
            new Vector3f((float) (cfg.scale() / g.fitScale)),
            new Quaternionf()));
    }

    private static void apply(Interaction hitbox, Settings.Microphone cfg, ModelGeometry g) {
        float width = 0.6f, height = 1f;
        if (g != null) {
            double k = cfg.scale() / 16;
            // An Interaction is an upright square prism centred on the entity; take the larger horizontal
            // half-extent so that it covers the model whichever way it faces.
            double reach = 0;
            for (int axis = 0; axis <= 2; axis += 2) {
                reach = Math.max(reach, Math.abs(g.bounds.min()[axis] - g.origin[axis]));
                reach = Math.max(reach, Math.abs(g.bounds.max()[axis] - g.origin[axis]));
            }
            width = (float) Math.max(0.4, Math.min(4, reach * 2 * k));
            height = (float) Math.max(0.4, Math.min(4, (g.bounds.max()[1] - g.origin[1]) * k));
        }
        hitbox.setInteractionWidth(width);
        hitbox.setInteractionHeight(height);
    }

    /** After the model file or the settings changed: every loaded microphone gets the new shape. */
    public void refreshAll() {
        for (World world : Bukkit.getWorlds()) refresh(world.getEntities());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        refresh(event.getEntities());
    }

    private void refresh(Iterable<Entity> entities) {
        Settings.Microphone cfg = plugin.settings().microphone();
        ModelGeometry geometry = plugin.microphoneGeometry();
        for (Entity entity : entities) {
            if (!isMicrophone(entity)) continue;
            if (entity instanceof ItemDisplay display) {
                Float yaw = display.getPersistentDataContainer().get(yawKey, PersistentDataType.FLOAT);
                apply(display, yaw == null ? 0 : yaw, cfg, geometry);
            } else if (entity instanceof Interaction hitbox) {
                apply(hitbox, cfg, geometry);
            }
        }
    }

    // ------------------------------------------------------------------ using

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUse(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction hitbox) || !isMicrophone(hitbox)) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return; // the client sends one event per hand
        Player player = event.getPlayer();
        Messages messages = plugin.messages();

        if (player.isSneaking() && player.hasPermission(AnnouncementManager.PERMISSION_ADMIN)) {
            // the display and the hitbox share one position
            for (Entity near : hitbox.getNearbyEntities(0.1, 0.1, 0.1)) if (near instanceof Display && isMicrophone(near)) near.remove();
            hitbox.remove();
            if (player.getGameMode() != GameMode.CREATIVE) player.getInventory().addItem(createItem()).values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
            messages.send(player, "microphone.removed");
            return;
        }
        if (!plugin.settings().microphone().enabled()) return;
        if (!player.hasPermission(PERMISSION_USE) && !AnnouncementManager.mayAnnounce(player)) {
            messages.send(player, "no-permission");
            return;
        }
        // tell them now, not after they have typed the whole announcement
        int wait = plugin.announcements().cooldownLeft(player);
        if (wait > 0) {
            messages.send(player, "cooldown", Messages.text("seconds", wait), Messages.text("time", AnnouncementManager.clock(wait)));
            return;
        }
        Location at = hitbox.getLocation();
        sessions.put(player.getUniqueId(), new Session(at.getWorld().getUID(), at.getX(), at.getY(), at.getZ(), System.currentTimeMillis()));
        plugin.dialog().show(player, "");
    }

    /**
     * Did this player open the dialog at a microphone, recently, and are they still standing near it? Checked
     * when the dialog comes back: the client's word that "this came from a microphone" is not taken.
     */
    public boolean atMicrophone(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || System.currentTimeMillis() - session.opened > SESSION_MILLIS) return false;
        if (!plugin.settings().microphone().enabled() || !(player.hasPermission(PERMISSION_USE) || AnnouncementManager.mayAnnounce(player))) return false;
        Location at = player.getLocation();
        if (!at.getWorld().getUID().equals(session.world)) return false;
        double dx = at.getX() - session.x, dy = at.getY() - session.y, dz = at.getZ() - session.z;
        return dx * dx + dy * dy + dz * dz <= SUBMIT_RANGE * SUBMIT_RANGE;
    }

    public void forget(Player player) { sessions.remove(player.getUniqueId()); }
}
