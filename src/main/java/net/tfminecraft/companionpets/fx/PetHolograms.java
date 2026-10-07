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
    /** Labels closer than this to their place stay put; a teleport is sent to every nearby player. */
    private static final double SETTLED_SQUARED = 1.0E-4;

    private final Plugin plugin;
    private final Map<UUID, Hologram> shown = new HashMap<>();
    /** One task follows every label, and only while any label is shown. */
    private BukkitTask follower;

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
        Hologram hologram = new Hologram(pet, stand, held);
        shown.put(id, hologram);
        if (follower == null) follower = Bukkit.getScheduler().runTaskTimer(plugin, this::follow, FOLLOW_TICKS, FOLLOW_TICKS);
        if (!held) {
            hologram.expire = Bukkit.getScheduler().runTaskLater(plugin, () -> remove(id, hologram), ticks);
        }
    }

    private void follow() {
        for (var entry : List.copyOf(shown.entrySet())) {
            Hologram hologram = entry.getValue();
            if (!hologram.pet.isValid() || !hologram.stand.isValid()) {
                remove(entry.getKey(), hologram);
                continue;
            }
            Location target = above(hologram.pet);
            Location at = hologram.stand.getLocation();
            // Sleeping pets lie still, so most labels need no update at all.
            if (!at.getWorld().equals(target.getWorld()) || at.distanceSquared(target) > SETTLED_SQUARED) hologram.stand.teleport(target);
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
        if (hologram.expire != null) {
            hologram.expire.cancel();
        }
        if (hologram.stand.isValid()) {
            hologram.stand.remove();
        }
        if (shown.isEmpty() && follower != null) {
            follower.cancel();
            follower = null;
        }
    }

    private static Location above(Entity pet) {
        return pet.getLocation().add(0, pet.getHeight() + ABOVE_NAME, 0);
    }

    private static final class Hologram {
        private final Entity pet;
        private final ArmorStand stand;
        private final boolean held;
        private BukkitTask expire;

        private Hologram(Entity pet, ArmorStand stand, boolean held) {
            this.pet = pet;
            this.stand = stand;
            this.held = held;
        }
    }
}
