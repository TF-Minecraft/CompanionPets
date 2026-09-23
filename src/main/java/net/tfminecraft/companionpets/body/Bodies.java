package net.tfminecraft.companionpets.body;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.Component;

import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.integration.MythicSpawn;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.visual.PetVisual;

public final class Bodies {
    private final JavaPlugin plugin;
    private final NamespacedKey petKey;
    private final PetVisual visual;

    public Bodies(JavaPlugin plugin, NamespacedKey petKey, PetVisual visual) {
        this.plugin = plugin;
        this.petKey = petKey;
        this.visual = visual;
    }

    public Entity spawn(Pet pet, PetTypeDef type, Location location, Player owner) {
        if (location.getWorld() == null || type == null) {
            return null;
        }
        Entity entity;
        boolean mythic = type.mythicMob() != null;
        if (mythic) {
            entity = MythicSpawn.spawn(type.mythicMob(), location, plugin.getLogger());
        } else if (type.entity() != null && type.entity().isAlive() && type.entity().getEntityClass() != null) {
            entity = location.getWorld().spawn(location, type.entity().getEntityClass());
        } else {
            return null;
        }
        if (entity == null) {
            return null;
        }
        prepare(entity, pet, type, owner, !mythic);
        return entity;
    }

    public void reattach(Entity entity, Pet pet, PetTypeDef type) {
        if (!(entity instanceof LivingEntity)) {
            return;
        }
        tag(entity, pet.id());
        name(entity, pet.name());
        visual.apply(entity, type);
    }

    public UUID readId(Entity entity) {
        String raw = entity.getPersistentDataContainer().get(petKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void prepare(Entity entity, Pet pet, PetTypeDef type, Player owner, boolean clearVanillaGoals) {
        tag(entity, pet.id());
        name(entity, pet.name());
        if (!entity.isPersistent()) {
            entity.setPersistent(true);
        }
        if (entity instanceof Mob mob) {
            mob.setRemoveWhenFarAway(false);
            if (clearVanillaGoals) {
                Bukkit.getMobGoals().removeAllGoals(mob);
            }
        }
        if (entity instanceof Ageable ageable) {
            ageable.setAdult();
        }
        if (entity instanceof Tameable tameable) {
            tameable.setTamed(true);
            if (owner != null) {
                tameable.setOwner(owner);
            }
        }
        visual.apply(entity, type);
    }

    private void tag(Entity entity, UUID petId) {
        entity.getPersistentDataContainer().set(petKey, PersistentDataType.STRING, petId.toString());
    }

    public void name(Entity entity, String name) {
        entity.customName(Component.text(name));
        entity.setCustomNameVisible(true);
    }
}
