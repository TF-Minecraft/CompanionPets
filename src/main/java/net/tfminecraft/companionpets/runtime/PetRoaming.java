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
import net.tfminecraft.companionpets.pet.PetOrder;
import net.tfminecraft.companionpets.integration.PetMotion;
import net.tfminecraft.companionpets.visual.PetAnimation;

/** Small trips and changing attention around an owner who is standing still. */
final class PetRoaming {
    private final PetRuntime runtime;
    private final java.util.function.BiConsumer<Entity, Boolean> sleep;
    private final Map<UUID, OwnerMotion> owners = new HashMap<>();
    private final Map<UUID, Plan> plans = new HashMap<>();
    private final Map<UUID, Attention> attention = new HashMap<>();

    PetRoaming(PetRuntime runtime, java.util.function.BiConsumer<Entity, Boolean> sleep) { this.runtime = runtime; this.sleep = sleep; }

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

    boolean ownerStationary(Player owner, long now) {
        OwnerMotion motion = owners.get(owner.getUniqueId());
        return motion != null && now - motion.lastMovedAt >= runtime.config().roaming().stationarySeconds() * 1000.0;
    }

    void attend(Pet pet, Player owner, long now) {
        attend(pet, owner, now, null);
    }

    PetOrder returnOrder(Pet pet) {
        Attention job = attention.get(pet.id());
        if (job != null && job.returnOrder != null) return job.returnOrder;
        if (pet.order() == PetOrder.LAY || pet.activity() == Activity.SLEEPING) return PetOrder.LAY;
        return pet.staying() ? PetOrder.STAY : pet.order();
    }

    boolean coming(Pet pet) {
        Attention job = attention.get(pet.id());
        return job != null && job.returnOrder != null;
    }

    void come(Pet pet, Player owner, long now, PetOrder returnOrder) {
        attend(pet, owner, now, returnOrder);
    }

    void returnFromFetch(Pet pet, Player owner, double speed) {
        attend(pet, owner, System.currentTimeMillis(), PetOrder.FOLLOW);
        attention.get(pet.id()).until = Long.MAX_VALUE;
        attention.get(pet.id()).speed = speed;
        if (runtime.entity(pet) instanceof Mob body) tickAttention(pet, body, System.currentTimeMillis());
    }

    Location destination(Pet pet) {
        Attention job = attention.get(pet.id());
        Player owner = job == null ? null : Bukkit.getPlayer(job.ownerId);
        return owner != null && owner.isOnline() ? owner.getLocation() : null;
    }

    boolean returningFromFetch(Pet pet) {
        Attention job = attention.get(pet.id());
        return job != null && job.until == Long.MAX_VALUE;
    }

    double movementSpeed(Pet pet) {
        Attention job = attention.get(pet.id());
        return job == null ? 1.25 : job.speed;
    }

    private void attend(Pet pet, Player owner, long now, PetOrder returnOrder) {
        plans.remove(pet.id());
        if (runtime.entity(pet) instanceof Mob body) {
            net.tfminecraft.companionpets.integration.PetMotion.stop(body);
            body.setAware(true);
            CallNavigationGoal.ensure(runtime, pet, body, () -> tickAttention(pet, body, System.currentTimeMillis()));
        }
        pet.activity(Activity.ATTENDING);
        attention.put(pet.id(), new Attention(owner.getUniqueId(),
                now + 30_000L, returnOrder));
    }

    boolean tickAttention(Pet pet, Mob body, long now) {
        Attention job = attention.get(pet.id());
        if (job == null) return false;
        if (pet.activity() != Activity.ATTENDING || pet.order() != net.tfminecraft.companionpets.pet.PetOrder.FOLLOW || pet.staying()) {
            cancelAttention(pet);
            return false;
        }
        Player owner = Bukkit.getPlayer(job.ownerId);
        if (owner == null || !owner.isOnline() || now >= (job.waitUntil == 0 ? job.until : job.waitUntil)
                || !owner.getWorld().equals(body.getWorld())) {
            cancelAttention(pet);
            if (job.returnOrder != null) restorePosture(pet, body, job.returnOrder);
            return job.returnOrder != null;
        }
        PetFx.sit(body, false);
        PetFx.lie(body, false);
        if (job.waitUntil == 0 && body.getLocation().distanceSquared(owner.getLocation()) > 4.0) {
            body.getPathfinder().moveTo(owner.getLocation(), job.speed);
        } else {
            if (job.returnOrder != null) {
                attention.remove(pet.id());
                restorePosture(pet, body, job.returnOrder);
                PetFx.happy(body, false);
                return true;
            }
            if (job.waitUntil == 0) job.waitUntil = now + Math.round(runtime.config().roaming().nameAttentionSeconds() * 1000.0);
            net.tfminecraft.companionpets.integration.PetMotion.hold(body);
            PetFx.look(body, owner);
            if (!job.greeted) {
                PetFx.happy(body, false);
                PetFx.hearts(body, 2);
                job.greeted = true;
            }
        }
        return true;
    }

    private void restorePosture(Pet pet, Mob body, PetOrder order) {
        pet.order(order); pet.staying(order == PetOrder.STAY);
        pet.activity(order == PetOrder.LAY ? Activity.SLEEPING : Activity.NONE);
        PetFx.lie(body, order == PetOrder.LAY);
        if (order != PetOrder.LAY) PetFx.sit(body, order == PetOrder.SIT);
        sleep.accept(body, order == PetOrder.LAY);
        if (order == PetOrder.FOLLOW) { PetMotion.stop(body); body.setAware(true); }
        else PetMotion.hold(body);
        var type = runtime.config().type(pet.typeId());
        if (type != null) runtime.visual().update(body, type,
                order == PetOrder.LAY ? PetAnimation.SLEEP : order == PetOrder.SIT ? PetAnimation.SIT : PetAnimation.IDLE);
        runtime.store().save();
    }

    boolean step(Pet pet, Mob body, Player owner, double speed, long now) {
        RoamSettings settings = runtime.config().roaming();
        if (!settings.enabled() || owner == null || !owner.isOnline() || !owner.getWorld().equals(body.getWorld())
                || body.getTarget() != null
                || body.getLocation().distanceSquared(owner.getLocation()) > square(settings.radius() + 2)) {
            plans.remove(pet.id());
            return false;
        }
        if (!ownerStationary(owner, now)) {
            plans.remove(pet.id());
            return false;
        }
        Plan plan = plans.get(pet.id());
        if (plan == null || now >= plan.until || plan.targetId != null && Bukkit.getEntity(plan.targetId) == null) {
            plan = choose(pet, body, owner, now, settings);
            plans.put(pet.id(), plan);
        }
        Entity targetEntity = plan.targetId == null ? null : Bukkit.getEntity(plan.targetId);
        Location target = plan.point;
        if (target == null || !target.getWorld().equals(body.getWorld())
                || targetEntity != null && !targetEntity.getWorld().equals(body.getWorld())) {
            plans.remove(pet.id());
            return false;
        }
        double stopDistance = targetEntity == null ? 0.8 : 2.0;
        if (body.getLocation().distanceSquared(target) > square(stopDistance)) {
            plan.arrived = false;
            if (now >= plan.nextMoveAt) {
                body.getPathfinder().moveTo(target, speed * 0.9);
                plan.nextMoveAt = now + 1_500L;
            }
        } else {
            if (!plan.arrived) {
                body.getPathfinder().stopPathfinding();
                plan.arrived = true;
            }
            if (targetEntity != null && body.getLocation().distanceSquared(targetEntity.getLocation()) <= 16.0) {
                PetFx.look(body, targetEntity);
                if (!plan.greeted && targetEntity instanceof Player) {
                    PetFx.happy(body, false);
                    PetFx.particle(body, org.bukkit.Particle.HAPPY_VILLAGER, 2);
                    plan.greeted = true;
                }
            } else if (runtime.random().nextInt(5) == 0) {
                PetFx.look(body, owner);
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
                    && body.getLocation().distanceSquared(player.getLocation()) <= square(settings.playerRadius())
                    && owner.getLocation().distanceSquared(player.getLocation()) <= square(settings.radius())) {
                players.add(entity);
            } else if (runtime.byEntity(entity) instanceof Pet other && other != pet
                    && !other.stored() && !other.dead()
                    && body.getLocation().distanceSquared(entity.getLocation()) <= square(settings.petRadius())
                    && owner.getLocation().distanceSquared(entity.getLocation()) <= square(settings.radius())) {
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
        return new Plan(target == null ? null : target.getUniqueId(),
                target == null ? point : target.getLocation(), until);
    }

    void cancel(Pet pet) { plans.remove(pet.id()); cancelAttention(pet); }
    void cancelWithPosture(Pet pet) {
        Attention job = attention.get(pet.id());
        cancel(pet);
        if (job != null && job.returnOrder != null && runtime.entity(pet) instanceof Mob body)
            restorePosture(pet, body, job.returnOrder);
    }
    void cancelPlan(Pet pet) { plans.remove(pet.id()); }

    private void cancelAttention(Pet pet) {
        attention.remove(pet.id());
        if (pet.activity() == Activity.ATTENDING) pet.activity(Activity.NONE);
    }

    void clear() {
        for (var entry : java.util.List.copyOf(attention.entrySet())) {
            var pet = runtime.store().get(entry.getKey());
            if (pet != null && entry.getValue().returnOrder != null && runtime.entity(pet) instanceof Mob body)
                restorePosture(pet, body, entry.getValue().returnOrder);
            else if (pet != null && pet.activity() == Activity.ATTENDING) pet.activity(Activity.NONE);
        }
        plans.clear(); attention.clear(); owners.clear();
    }

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
        private boolean arrived;
        private long nextMoveAt;
        private Plan(UUID targetId, Location point, long until) {
            this.targetId = targetId; this.point = point; this.until = until;
        }
    }

    private static final class Attention {
        private double speed = 1.25;
        private final UUID ownerId;
        private long until;
        private final PetOrder returnOrder;
        private long waitUntil;
        private boolean greeted;
        private Attention(UUID ownerId, long until, PetOrder returnOrder) { this.ownerId = ownerId; this.until = until; this.returnOrder = returnOrder; }
    }
}
