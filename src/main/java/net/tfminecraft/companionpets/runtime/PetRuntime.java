package net.tfminecraft.companionpets.runtime;

import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.visual.PetVisual;

public final class PetRuntime {
    private final JavaPlugin plugin;
    private CompanionConfig config;
    private final PetStore store;
    private final Sessions sessions;
    private final Bodies bodies;
    private final PetVisual visual;
    private final NamespacedKey petKey;
    private final NamespacedKey toyKey;
    private final Random random = new Random();
    private final java.util.Set<UUID> suspendedFollowing = new java.util.HashSet<>();

    public PetRuntime(
            JavaPlugin plugin,
            CompanionConfig config,
            PetStore store,
            Sessions sessions,
            Bodies bodies,
            PetVisual visual,
            NamespacedKey petKey,
            NamespacedKey toyKey) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.sessions = sessions;
        this.bodies = bodies;
        this.visual = visual;
        this.petKey = petKey;
        this.toyKey = toyKey;
        // Loading a saved Follow order must not summon distant pets into a new session.
        for (Pet pet : store.all()) suspendedFollowing.add(pet.id());
        applyDefaultTricks();
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    public CompanionConfig config() {
        return config;
    }

    public void config(CompanionConfig config) {
        this.config = java.util.Objects.requireNonNull(config);
        applyDefaultTricks();
    }

    private void applyDefaultTricks() {
        boolean changed = false;
        for (Pet pet : store.all()) changed |= net.tfminecraft.companionpets.training.DefaultTricks.apply(config, pet);
        if (changed) store.save();
    }

    public PetStore store() {
        return store;
    }

    public Sessions sessions() {
        return sessions;
    }

    public Bodies bodies() {
        return bodies;
    }

    public PetVisual visual() {
        return visual;
    }

    public NamespacedKey petKey() {
        return petKey;
    }

    public NamespacedKey toyKey() {
        return toyKey;
    }

    public Random random() {
        return random;
    }

    public boolean behaves(Pet pet, net.tfminecraft.companionpets.config.PetBehavior behavior) {
        var type = config.type(pet.typeId());
        return type != null && type.behaves(behavior);
    }

    public void recordCare(Player player, Pet pet, double gain, long now) {
        if (!pet.ownerId().equals(player.getUniqueId())
                && behaves(pet, net.tfminecraft.companionpets.config.PetBehavior.RECOGNIZE_CARERS))
            pet.carers().reinforce(player.getUniqueId(), gain, now, 60_000);
    }

    public void suspendFollowing(UUID ownerId) {
        for (Pet pet : store.of(ownerId)) suspendedFollowing.add(pet.id());
    }

    public void resumeFollowing(Pet pet) { suspendedFollowing.remove(pet.id()); }

    public boolean followingAllowed(Pet pet, Player owner) {
        if (owner == null || !owner.isOnline() || distance(owner, pet) == Double.POSITIVE_INFINITY) return false;
        if (suspendedFollowing.contains(pet.id())
                && distance(owner, pet) <= Math.min(config.ownerNearRadius(), config.followTeleportBlocks()))
            suspendedFollowing.remove(pet.id());
        return !suspendedFollowing.contains(pet.id());
    }

    public Entity entity(Pet pet) {
        if (pet == null || pet.entityId() == null) {
            return null;
        }
        Entity entity = Bukkit.getEntity(pet.entityId());
        if (entity == null || !entity.isValid() || entity.isDead()) {
            return null;
        }
        return entity;
    }

    public void remember(Pet pet, Entity entity) {
        if (entity == null || entity.getWorld() == null) {
            return;
        }
        Location location = entity.getLocation();
        pet.place(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), location.getYaw());
        pet.entityId(entity.getUniqueId());
    }

    public double distance(Player player, Pet pet) {
        if (player == null || pet == null || pet.worldName() == null || pet.worldName().isBlank()) {
            return Double.POSITIVE_INFINITY;
        }
        if (!player.getWorld().getName().equals(pet.worldName())) {
            return Double.POSITIVE_INFINITY;
        }
        Entity entity = entity(pet);
        Location there = entity == null
                ? new Location(player.getWorld(), pet.x(), pet.y(), pet.z())
                : entity.getLocation();
        return player.getLocation().distance(there);
    }

    public static Location beside(Player player) {
        Location feet = player.getLocation();
        Vector forward = feet.getDirection().clone();
        forward.setY(0);
        if (forward.lengthSquared() < 1.0E-4) {
            forward = new Vector(0, 0, 1);
        } else {
            forward.normalize();
        }
        Vector side = new Vector(-forward.getZ(), 0, forward.getX());
        Location[] spots = {
            offset(feet, forward, 1.6),
            offset(feet, forward, -1.6),
            offset(feet, side, 1.6),
            offset(feet, side, -1.6),
            feet.clone().add(0, 1, 0)
        };
        for (Location spot : spots) {
            spot.setYaw(feet.getYaw());
            spot.setPitch(0);
            if (open(spot)) {
                return spot;
            }
        }
        Location above = feet.clone().add(0, 1, 0);
        above.setPitch(0);
        return above;
    }

    private static Location offset(Location feet, Vector direction, double scale) {
        return feet.clone().add(direction.clone().multiply(scale));
    }

    private static boolean open(Location spot) {
        if (spot.getWorld() == null) {
            return false;
        }
        Block lower = spot.getBlock();
        Block upper = lower.getRelative(0, 1, 0);
        return lower.isPassable() && !lower.isLiquid() && upper.isPassable() && !upper.isLiquid();
    }

    public static Location inFront(Player player) {
        Location location = beside(player);
        location.setY(player.getLocation().getY());
        return location;
    }

    public Pet byEntity(Entity entity) {
        if (entity == null) {
            return null;
        }
        UUID id = bodies.readId(entity);
        if (id == null) {
            return store.byEntity(entity.getUniqueId());
        }
        return store.get(id);
    }
}
