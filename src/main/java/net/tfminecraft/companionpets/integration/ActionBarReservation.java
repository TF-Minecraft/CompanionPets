package net.tfminecraft.companionpets.integration;

import java.lang.reflect.Method;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Holds MythicLib's shared action bar while a pet notice is on screen, so plugins
 * that refresh it constantly (MMOCore's health and mana bar) wait instead of
 * overwriting the notice. No MythicLib classes are linked when it is absent.
 */
public final class ActionBarReservation {
    /** MythicLib NORMAL; MMOCore's default stats bar uses LOWEST (10). */
    private static final int PRIORITY = 30;
    private record Access(Method data, Method actionBar, Method hide) { }
    private static Access access;
    private static boolean resolved;

    private ActionBarReservation() { }

    public static void reserve(Player player, long ticks) {
        Access found = access();
        if (found == null) return;
        try {
            found.hide.invoke(found.actionBar.invoke(found.data.invoke(null, player)), PRIORITY, ticks);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A player without MythicLib data simply shows the notice without a reservation.
        }
    }

    private static Access access() {
        if (resolved) return access;
        resolved = true;
        var plugin = Bukkit.getPluginManager().getPlugin("MythicLib");
        if (plugin == null || !plugin.isEnabled()) return null;
        try {
            Class<?> data = Class.forName("io.lumine.mythic.lib.api.player.MMOPlayerData", true,
                    plugin.getClass().getClassLoader());
            Method actionBar = data.getMethod("getActionBar");
            access = new Access(data.getMethod("get", OfflinePlayer.class), actionBar,
                    actionBar.getReturnType().getMethod("hide", int.class, long.class));
        } catch (ReflectiveOperationException | LinkageError ex) {
            Bukkit.getLogger().warning("[CompanionPets] MythicLib action bar API unavailable; pet notices may be overwritten");
        }
        return access;
    }
}
