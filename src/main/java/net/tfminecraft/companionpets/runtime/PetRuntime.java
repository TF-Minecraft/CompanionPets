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
    private final java.util.Map<UUID, Entity> loadedBodies = new java.util.HashMap<>();
    private final Sessions sessions;
    private final Bodies bodies;
    private final PetVisual visual;
    private final NamespacedKey petKey;
    private final NamespacedKey toyKey;
    private final Random random = new Random();
    private final PetVoice voice = new PetVoice(this);
    private final java.util.Map<String, net.tfminecraft.companionpets.visual.PetCapabilities> capabilities = new java.util.HashMap<>();
    private final java.util.Set<UUID> suspendedFollowing = new java.util.HashSet<>();
    private final java.util.Map<UUID, Location> lastGround = new java.util.HashMap<>();

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
        voice.clear();
        capabilities.clear();
        for (Pet pet : store.all()) {
            Entity entity = entity(pet);
            var type = config.type(pet.typeId());
            if (entity != null && type != null && entity.getType() == type.entity()) bodies.configure(entity, type);
        }
        applyDefaultTricks();
    }

    private void applyDefaultTricks() {
        boolean changed = false;
        for (Pet pet : store.all()) changed |= net.tfminecraft.companionpets.training.DefaultTricks.apply(config, pet);
        if (changed) store.requestSave();
    }

    public PetStore store() {
        return store;
    }

    public Sessions sessions() {
        return sessions;
    }

    public boolean trainingFocused(Pet pet, org.bukkit.entity.Mob body) {
        var session = sessions.training(pet.ownerId());
        return session != null && session.petId().equals(pet.id())
                && TrainingNavigationGoal.focused(this, pet, body, Bukkit.getPlayer(pet.ownerId()));
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

    public PetVoice voice() { return voice; }

    void rememberGround(Pet pet, org.bukkit.entity.Mob body) {
        if (body.isOnGround() && !body.isInWater() && net.tfminecraft.companionpets.behavior.WaterEscape.safe(body.getLocation()))
            lastGround.put(pet.id(), body.getLocation().getBlock().getLocation().add(0.5, 0, 0.5));
    }

    Location lastGround(Pet pet) {
        Location at = lastGround.get(pet.id());
        return at == null ? null : at.clone();
    }

    void forgetMissingGround() {
        lastGround.keySet().removeIf(id -> {
            Pet pet = store.get(id);
            return pet == null || pet.stored() || pet.dead() || entity(pet) == null;
        });
    }

    public boolean behaves(Pet pet, net.tfminecraft.companionpets.config.PetBehavior behavior) {
        var type = config.type(pet.typeId());
        return type != null && type.behaves(behavior) && !capabilities(type).disabledBehaviors().containsKey(behavior);
    }

    public net.tfminecraft.companionpets.visual.PetCapabilities capabilities(net.tfminecraft.companionpets.config.PetTypeDef type) {
        var cached = capabilities.get(type.id());
        if (cached != null) return cached;
        var result = net.tfminecraft.companionpets.visual.PetCapabilities.inspect(type, visual);
        // ModelEngine registers blueprints asynchronously; retry unavailable models on the next query.
        if (!type.appearance().modeled() || result.modelAvailable()) capabilities.put(type.id(), result);
        return result;
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
                && distance(owner, pet) <= Math.min(config.ownerNearRadius(), 12))
            suspendedFollowing.remove(pet.id());
        return !suspendedFollowing.contains(pet.id());
    }

    public Entity entity(Pet pet) {
        if (pet == null || pet.entityId() == null) {
            return null;
        }
        // Behaviour code asks for a body many times per pass. A loaded body keeps its Bukkit object
        // until it is removed, unloaded or changes world, all of which make it invalid.
        Entity cached = loadedBodies.get(pet.id());
        if (cached != null && cached.isValid() && !cached.isDead() && pet.entityId().equals(cached.getUniqueId())) return cached;
        Entity entity = Bukkit.getEntity(pet.entityId());
        if (entity == null || !entity.isValid() || entity.isDead()) {
            loadedBodies.remove(pet.id());
            return null;
        }
        loadedBodies.put(pet.id(), entity);
        return entity;
    }

    /** Drops cached bodies of pets that left the store. */
    void forgetBodies() {
        loadedBodies.keySet().removeIf(id -> store.get(id) == null);
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
        return net.tfminecraft.companionpets.body.PetPlacement.beside(player,
                net.tfminecraft.companionpets.body.PetPlacement.normal(1), null);
    }

    public static Location inFront(Player player) {
        Location at = beside(player);
        return at == null ? player.getLocation() : at;
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
