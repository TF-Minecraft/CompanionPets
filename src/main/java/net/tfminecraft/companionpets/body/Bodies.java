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
    private final java.util.Map<UUID, Recovery> recovering = new java.util.HashMap<>();

    private static final class Recovery {
        final Location destination;
        boolean claimed;
        Recovery(Location destination) { this.destination = destination.clone(); }
    }

    public Bodies(JavaPlugin plugin, NamespacedKey petKey, PetVisual visual) {
        this.plugin = plugin;
        this.petKey = petKey;
        this.visual = visual;
    }

    public Entity spawn(Pet pet, PetTypeDef type, Location location, Player owner) {
        if (location == null || location.getWorld() == null || type == null || !PetBase.supported(type.entity())) {
            return null;
        }
        Entity entity;
        if (type.mythicMob() != null) {
            entity = MythicSpawn.spawn(type.mythicMob(), location, plugin.getLogger());
        } else {
            // The supported-body check above admits only WOLF and CAT.
            entity = location.getWorld().spawn(location, type.entity().getEntityClass());
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
        Location safe = PetPlacement.nearest(location, PetPlacement.bounds(entity), null);
        if (safe == null) { visual.removeBody(entity); return null; }
        if (!teleportToVerifiedSpace(entity, safe)) {
            visual.removeBody(entity);
            return null;
        }
        visual.play(entity, type, "SPAWN");
        return entity;
    }

    private boolean teleportToVerifiedSpace(Entity entity, Location safe) {
        // New pets have no remembered location yet, so posture rules must not cancel their first placement.
        recovering.put(entity.getUniqueId(), new Recovery(safe));
        try { return entity.teleport(safe) && PetPlacement.safe(entity.getLocation(), PetPlacement.bounds(entity)); }
        finally { recovering.remove(entity.getUniqueId()); }
    }

    /** True while initial placement moves the body to a verified clear space. */
    public boolean recovering(Entity entity) { return recovering.containsKey(entity.getUniqueId()); }

    /** Only one event targeting the verified destination may bypass posture restrictions. */
    public boolean claimRecoveryTeleport(Entity entity, Location destination) {
        Recovery recovery = recovering.get(entity.getUniqueId());
        if (recovery == null || recovery.claimed || !recovery.destination.equals(destination)) return false;
        recovery.claimed = true;
        return true;
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
        if (entity instanceof Mob mob) {
            mob.getPathfinder().setCanFloat(true);
            NativeCombatGuard.configure(plugin, mob, type.nativeCombat());
        }
    }

    private static boolean aware(Pet pet) {
        var mode = net.tfminecraft.companionpets.behavior.Locomotion.choose(pet.illness(), pet.need(net.tfminecraft.companionpets.pet.Need.HEALTH),
                pet.need(net.tfminecraft.companionpets.pet.Need.ENERGY), pet.need(net.tfminecraft.companionpets.pet.Need.HUNGER), pet.activity(),
                pet.fetch() != null, System.currentTimeMillis() < pet.forcedSitUntilMillis(), pet.order(), pet.staying());
        // A staying body waits frozen until the ticker gives it the posture goal, so it cannot wander first.
        return mode != net.tfminecraft.companionpets.behavior.Locomotion.Mode.STAY;
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
