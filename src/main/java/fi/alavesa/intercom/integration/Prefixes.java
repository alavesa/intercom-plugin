package fi.alavesa.intercom.integration;

import net.luckperms.api.LuckPerms;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Everything that touches the LuckPerms API. This class is only loaded when LuckPerms is installed (see
 * IntercomPlugin), so the plugin runs without it.
 */
public final class Prefixes {

    private final LuckPerms api;

    public Prefixes() {
        api = Bukkit.getServicesManager().load(LuckPerms.class);
        if (api == null) throw new IllegalStateException("LuckPerms is installed but its API is not registered");
    }

    /** The player's LuckPerms prefix as it is stored (usually with {@code &} colour codes), or "" when they have none. */
    public String prefix(Player player) {
        String prefix = api.getPlayerAdapter(Player.class).getMetaData(player).getPrefix();
        return prefix == null ? "" : prefix;
    }
}
