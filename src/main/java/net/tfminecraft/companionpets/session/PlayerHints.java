package net.tfminecraft.companionpets.session;

import java.util.Arrays;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class PlayerHints {
    private final NamespacedKey key;

    public PlayerHints(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "hints");
    }

    public boolean firstTime(Player player, String hint) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        String seen = data.getOrDefault(key, PersistentDataType.STRING, "");
        if (!seen.isEmpty() && Arrays.asList(seen.split(",")).contains(hint)) {
            return false;
        }
        data.set(key, PersistentDataType.STRING, seen.isEmpty() ? hint : seen + "," + hint);
        return true;
    }
}
