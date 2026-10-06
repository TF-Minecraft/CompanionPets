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
import net.tfminecraft.companionpets.config.PetBase;
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
        if (location.getWorld() == null || type == null || !PetBase.supported(type.entity())) {
            return null;
        }
        Entity entity;
        if (type.mythicMob() != null) {
            entity = MythicSpawn.spawn(type.mythicMob(), location, plugin.getLogger());
        } else if (type.entity() != null && type.entity().isAlive() && type.entity().getEntityClass() != null) {
            entity = location.getWorld().spawn(location, type.entity().getEntityClass());
        } else {
            return null;
        }
        if (entity == null) {
            return null;
        }
        if (!compatible(entity, type)) {
            plugin.getLogger().warning("Pet " + type.id() + ": spawned body must be " + type.entity()
                    + " with native owner-follow AI; found " + entity.getType());
            entity.remove();
            return null;
        }
        if (type.mythicMob() != null && !Bukkit.getMobGoals().hasGoal((Tameable) entity,
                com.destroystokyo.paper.entity.ai.VanillaGoal.FOLLOW_OWNER)) {
            plugin.getLogger().warning("Pet " + type.id() + ": MythicMobs body is missing the native FollowOwner goal");
            entity.remove();
            return null;
        }
        prepare(entity, pet, type, owner);
        visual.play(entity, type, "SPAWN");
        return entity;
    }

    public void reattach(Entity entity, Pet pet, PetTypeDef type) {
        if (!compatible(entity, type)) {
            return;
        }
        configure(entity, type);
        tag(entity, pet.id());
        name(entity, pet.name());
        if (entity instanceof Tameable tameable) {
            tameable.setTamed(true);
            tameable.setOwner(Bukkit.getOfflinePlayer(pet.ownerId()));
        }
        visual.apply(entity, type);
        keepPersistent(entity);
        if (entity instanceof Mob mob) mob.setAware(aware(pet) || net.tfminecraft.companionpets.behavior.WaterEscape.needed(mob));
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

    private void prepare(Entity entity, Pet pet, PetTypeDef type, Player owner) {
        configure(entity, type);
        tag(entity, pet.id());
        name(entity, pet.name());
        if (entity instanceof Ageable ageable) {
            ageable.setAdult();
        }
        if (entity instanceof Tameable tameable) {
            tameable.setTamed(true);
            tameable.setOwner(owner != null ? owner : Bukkit.getOfflinePlayer(pet.ownerId()));
        }
        visual.apply(entity, type);
        keepPersistent(entity);
        if (entity instanceof Mob mob) mob.setAware(aware(pet) || net.tfminecraft.companionpets.behavior.WaterEscape.needed(mob));
    }

    public boolean compatible(Entity entity, PetTypeDef type) {
        return entity instanceof Mob && entity instanceof Tameable && type != null
                && PetBase.supported(type.entity()) && entity.getType() == type.entity();
    }

    public void configure(Entity entity, PetTypeDef type) {
        entity.setSilent(!type.sounds().nativeSounds());
        if (entity instanceof Mob mob) NativeCombatGuard.configure(plugin, mob, type.nativeCombat());
    }

    private static boolean aware(Pet pet) {
        var mode = net.tfminecraft.companionpets.behavior.Locomotion.choose(pet.illness(), pet.need(net.tfminecraft.companionpets.pet.Need.HEALTH),
                pet.need(net.tfminecraft.companionpets.pet.Need.ENERGY), pet.need(net.tfminecraft.companionpets.pet.Need.HUNGER), pet.activity(),
                pet.fetch() != null, System.currentTimeMillis() < pet.forcedSitUntilMillis(), pet.order(), pet.staying());
        return (mode == net.tfminecraft.companionpets.behavior.Locomotion.Mode.FOLLOW
                || mode == net.tfminecraft.companionpets.behavior.Locomotion.Mode.PLAY || mode == net.tfminecraft.companionpets.behavior.Locomotion.Mode.FETCH);
    }

    private static void keepPersistent(Entity entity) {
        entity.setPersistent(true);
        if (entity instanceof Mob mob) mob.setRemoveWhenFarAway(false);
    }

    private void tag(Entity entity, UUID petId) {
        entity.getPersistentDataContainer().set(petKey, PersistentDataType.STRING, petId.toString());
    }

    public void name(Entity entity, String name) {
        entity.customName(Component.text(name));
        entity.setCustomNameVisible(true);
    }
}
