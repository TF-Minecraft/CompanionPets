package net.tfminecraft.companionpets.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import net.tfminecraft.companionpets.config.RoamSettings;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.Pet;

/** Small trips and changing attention around an owner who is standing still. */
final class PetRoaming {
    private final PetRuntime runtime;
    private final Map<UUID, OwnerMotion> owners = new HashMap<>();
    private final Map<UUID, Plan> plans = new HashMap<>();
    private final Map<UUID, Attention> attention = new HashMap<>();

    PetRoaming(PetRuntime runtime) { this.runtime = runtime; }

    void tickOwners(long now) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            OwnerMotion motion = owners.get(player.getUniqueId());
            Location location = player.getLocation();
            if (motion == null || !motion.location.getWorld().equals(location.getWorld())) {
                owners.put(player.getUniqueId(), new OwnerMotion(location, now));
                continue;
            }
            double dx = location.getX() - motion.location.getX();
            double dz = location.getZ() - motion.location.getZ();
            if (dx * dx + dz * dz > 0.04 || horizontalSpeed(player) > 0.01) motion.lastMovedAt = now;
            motion.location = location;
        }
        owners.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    void attend(Pet pet, Player owner, long now) {
        plans.remove(pet.id());
        pet.activity(Activity.ATTENDING);
        attention.put(pet.id(), new Attention(owner.getUniqueId(),
                now + Math.round(runtime.config().roaming().nameAttentionSeconds() * 1000.0)));
    }

    boolean tickAttention(Pet pet, Mob body, long now) {
        Attention job = attention.get(pet.id());
        if (job == null) return false;
        Player owner = Bukkit.getPlayer(job.ownerId);
        if (owner == null || !owner.isOnline() || now >= job.until || !owner.getWorld().equals(body.getWorld())) {
            cancelAttention(pet);
            return false;
        }
        PetFx.sit(body, false);
        PetFx.lie(body, false);
        if (body.getLocation().distanceSquared(owner.getLocation()) > 4.0) {
            body.getPathfinder().moveTo(owner.getLocation(), 1.25);
        } else {
            body.getPathfinder().stopPathfinding();
            PetFx.look(body, owner.getEyeLocation());
            if (!job.greeted) {
                PetFx.happy(body, false);
                PetFx.hearts(body, 2);
                job.greeted = true;
            }
        }
        return true;
    }

    boolean step(Pet pet, Mob body, Player owner, double speed, long now) {
        RoamSettings settings = runtime.config().roaming();
        if (!settings.enabled() || owner == null || !owner.isOnline() || !owner.getWorld().equals(body.getWorld())
                || body.getTarget() != null
                || body.getLocation().distanceSquared(owner.getLocation()) > square(settings.radius() + 2)) {
            plans.remove(pet.id());
            return false;
        }
        OwnerMotion motion = owners.get(owner.getUniqueId());
        if (motion == null || now - motion.lastMovedAt < settings.stationarySeconds() * 1000.0) {
            plans.remove(pet.id());
            return false;
        }
        Plan plan = plans.get(pet.id());
        if (plan == null || now >= plan.until || plan.targetId != null && Bukkit.getEntity(plan.targetId) == null) {
            plan = choose(pet, body, owner, now, settings);
            plans.put(pet.id(), plan);
        }
        Entity targetEntity = plan.targetId == null ? null : Bukkit.getEntity(plan.targetId);
        Location target = targetEntity == null ? plan.point : targetEntity.getLocation();
        if (target == null || !target.getWorld().equals(body.getWorld())) {
            plans.remove(pet.id());
            return false;
        }
        double stopDistance = targetEntity == null ? 0.8 : 2.0;
        if (body.getLocation().distanceSquared(target) > square(stopDistance)) {
            body.getPathfinder().moveTo(target, speed * 0.9);
        } else {
            body.getPathfinder().stopPathfinding();
            if (targetEntity != null) {
                PetFx.look(body, targetEntity.getLocation().add(0, 0.8, 0));
                if (!plan.greeted && targetEntity instanceof Player) {
                    PetFx.happy(body, false);
                    PetFx.particle(body, org.bukkit.Particle.HAPPY_VILLAGER, 2);
                    plan.greeted = true;
                }
            } else if (runtime.random().nextInt(5) == 0) {
                PetFx.look(body, owner.getEyeLocation());
            }
        }
        return true;
    }

    private Plan choose(Pet pet, Mob body, Player owner, long now, RoamSettings settings) {
        List<Entity> pets = new ArrayList<>();
        List<Entity> players = new ArrayList<>();
        for (Entity entity : body.getNearbyEntities(Math.max(settings.petRadius(), settings.playerRadius()), 3,
                Math.max(settings.petRadius(), settings.playerRadius()))) {
            if (entity.getWorld() != owner.getWorld() || entity == body) continue;
            if (entity instanceof Player player && player != owner
                    && body.getLocation().distanceSquared(player.getLocation()) <= square(settings.playerRadius())) {
                players.add(entity);
            } else if (runtime.byEntity(entity) instanceof Pet other && other != pet
                    && !other.stored() && !other.dead()
                    && body.getLocation().distanceSquared(entity.getLocation()) <= square(settings.petRadius())) {
                pets.add(entity);
            }
        }
        double choice = runtime.random().nextDouble();
        Entity target = choice < 0.45 && !pets.isEmpty() ? pets.get(runtime.random().nextInt(pets.size()))
                : choice < 0.75 && !players.isEmpty() ? players.get(runtime.random().nextInt(players.size()))
                : null;
        if (target == null && !pets.isEmpty() && runtime.random().nextBoolean()) {
            target = pets.get(runtime.random().nextInt(pets.size()));
        }
        double angle = runtime.random().nextDouble() * Math.PI * 2;
        double radius = 1.5 + runtime.random().nextDouble() * Math.max(0.5, settings.radius() - 1.5);
        Location point = owner.getLocation().clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
        double low = Math.min(settings.choiceMinSeconds(), settings.choiceMaxSeconds());
        double high = Math.max(settings.choiceMinSeconds(), settings.choiceMaxSeconds());
        long until = now + Math.round((low + runtime.random().nextDouble() * (high - low)) * 1000.0);
        return new Plan(target == null ? null : target.getUniqueId(), point, until);
    }

    void cancel(Pet pet) { plans.remove(pet.id()); cancelAttention(pet); }

    private void cancelAttention(Pet pet) {
        attention.remove(pet.id());
        if (pet.activity() == Activity.ATTENDING) pet.activity(Activity.NONE);
    }

    void clear() { plans.clear(); attention.clear(); owners.clear(); }

    private static double horizontalSpeed(Player player) {
        return player.getVelocity().getX() * player.getVelocity().getX()
                + player.getVelocity().getZ() * player.getVelocity().getZ();
    }

    private static double square(double value) { return value * value; }

    private static final class OwnerMotion {
        private Location location;
        private long lastMovedAt;
        private OwnerMotion(Location location, long now) { this.location = location; this.lastMovedAt = now; }
    }

    private static final class Plan {
        private final UUID targetId;
        private final Location point;
        private final long until;
        private boolean greeted;
        private Plan(UUID targetId, Location point, long until) {
            this.targetId = targetId; this.point = point; this.until = until;
        }
    }

    private static final class Attention {
        private final UUID ownerId;
        private final long until;
        private boolean greeted;
        private Attention(UUID ownerId, long until) { this.ownerId = ownerId; this.until = until; }
    }
}
