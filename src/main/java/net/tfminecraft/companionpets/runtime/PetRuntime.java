package net.tfminecraft.companionpets.runtime;

import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
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
    private final CompanionConfig config;
    private final PetStore store;
    private final Sessions sessions;
    private final Bodies bodies;
    private final PetVisual visual;
    private final NamespacedKey petKey;
    private final NamespacedKey toyKey;
    private final Random random = new Random();

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
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    public CompanionConfig config() {
        return config;
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
        Location location = player.getLocation().clone();
        Vector direction = location.getDirection();
        direction.setY(0);
        if (direction.lengthSquared() < 1.0E-4) {
            direction = new Vector(0, 0, 1);
        }
        location.add(direction.normalize().multiply(1.4));
        return location;
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
