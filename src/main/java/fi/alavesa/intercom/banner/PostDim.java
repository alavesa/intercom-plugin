package fi.alavesa.intercom.banner;

import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The screen dim behind the banner: post effects intercom:dim_1 ... dim_N from the pack, applied per player
 * through Paper's {@code Player.postEffects()} - the API behind the vanilla 26.3 {@code /posteffect} command.
 * <p>
 * A post effect has no parameters the server could animate, so fading means switching between N effects of
 * rising strength. The switch is ONE list update (old level out, new level in), so no frame is drawn with
 * neither or both. Other plugins share the same list: only this class's own ids are ever touched.
 * Main thread only.
 */
public final class PostDim {

    /** The level each player has now (absent = none). */
    private final Map<UUID, Integer> levels = new HashMap<>();

    static Key key(int level) { return Key.key("intercom", "dim_" + level); }

    /** @param level 0 = no dim, 1..N = effect level */
    public void set(Player player, int level) {
        int old = levels.getOrDefault(player.getUniqueId(), 0);
        if (old == level) return;
        List<Key> effects = new ArrayList<>(player.postEffects().values());
        if (old > 0) effects.remove(key(old));
        if (level > 0) {
            effects.add(key(level));
            levels.put(player.getUniqueId(), level);
        } else {
            levels.remove(player.getUniqueId());
        }
        player.postEffects().set(effects);
    }

    public void forget(UUID player) { levels.remove(player); }

    /** Takes the dim away from everyone who has it. */
    public void clearAll() {
        for (UUID id : List.copyOf(levels.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) set(player, 0);
        }
        levels.clear();
    }
}
