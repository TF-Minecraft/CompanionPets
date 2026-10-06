package net.tfminecraft.companionpets.fx;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class PetHolograms {
    private static final long FOLLOW_TICKS = 2L;
    private static final double ABOVE_NAME = 0.25;

    private final Plugin plugin;
    private final Map<UUID, Hologram> shown = new HashMap<>();

    public PetHolograms(Plugin plugin) {
        this.plugin = plugin;
    }

    public void sleep(Entity pet, boolean asleep) {
        if (pet == null || !pet.isValid()) {
            return;
        }
        UUID id = pet.getUniqueId();
        Hologram current = shown.get(id);
        if (!asleep) {
            if (current != null && current.held) {
                remove(id, current);
            }
            return;
        }
        if (current != null && current.held) {
            return;
        }
        show(pet, Component.text("Sleeping", NamedTextColor.GRAY), 0L, true);
    }

    public void show(Entity pet, Component text, long ticks) {
        show(pet, text, ticks, false);
    }

    private void show(Entity pet, Component text, long ticks, boolean held) {
        if (pet == null || !pet.isValid()) {
            return;
        }
        UUID id = pet.getUniqueId();
        remove(id);
        ArmorStand stand = pet.getWorld().spawn(above(pet), ArmorStand.class, as -> {
            as.customName(text);
            as.setCustomNameVisible(true);
            as.setVisible(false);
            as.setMarker(true);
            as.setGravity(false);
            as.setSmall(true);
            as.setInvulnerable(true);
            as.setSilent(true);
            as.setPersistent(false);
        });
        Hologram hologram = new Hologram(stand, held);
        shown.put(id, hologram);
        hologram.follow = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!pet.isValid() || !stand.isValid()) {
                remove(id, hologram);
                return;
            }
            stand.teleport(above(pet));
        }, FOLLOW_TICKS, FOLLOW_TICKS);
        if (!held) {
            hologram.expire = Bukkit.getScheduler().runTaskLater(plugin, () -> remove(id, hologram), ticks);
        }
    }

    public void clear() {
        for (UUID id : List.copyOf(shown.keySet())) {
            remove(id);
        }
    }

    private void remove(UUID id) {
        Hologram hologram = shown.get(id);
        if (hologram != null) {
            remove(id, hologram);
        }
    }

    private void remove(UUID id, Hologram hologram) {
        if (shown.get(id) == hologram) {
            shown.remove(id);
        }
        if (hologram.follow != null) {
            hologram.follow.cancel();
        }
        if (hologram.expire != null) {
            hologram.expire.cancel();
        }
        if (hologram.stand.isValid()) {
            hologram.stand.remove();
        }
    }

    private static Location above(Entity pet) {
        return pet.getLocation().add(0, pet.getHeight() + ABOVE_NAME, 0);
    }

    private static final class Hologram {
        private final ArmorStand stand;
        private final boolean held;
        private BukkitTask follow;
        private BukkitTask expire;

        private Hologram(ArmorStand stand, boolean held) {
            this.stand = stand;
            this.held = held;
        }
    }
}
