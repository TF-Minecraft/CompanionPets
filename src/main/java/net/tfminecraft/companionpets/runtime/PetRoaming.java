package net.tfminecraft.companionpets.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetOrder;
import net.tfminecraft.companionpets.integration.PetMotion;
import net.tfminecraft.companionpets.visual.PetAnimation;

/** Explicit calls and fetch returns; idle movement belongs to native AI. */
final class PetRoaming {
    private final PetRuntime runtime;
    private final java.util.function.BiConsumer<Entity, Boolean> sleep;
    private final Map<UUID, OwnerMotion> owners = new HashMap<>();
    private final Map<UUID, Attention> attention = new HashMap<>();
    private final Map<UUID, UUID> listeners = new HashMap<>();

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

    /** A pet that does not come when called still turns to whoever said its name, whatever its pose. */
    void listen(Pet pet, Player player, long now) {
        listeners.put(pet.id(), player.getUniqueId());
        pet.listeningUntilMillis(now + Math.round(runtime.config().roaming().nameAttentionSeconds() * 1000.0));
        if (runtime.entity(pet) instanceof Mob body) PetFx.look(body, player);
    }

    void tickListening(Pet pet, Mob body, long now) {
        UUID listener = listeners.get(pet.id());
        if (listener == null) return;
        Player player = Bukkit.getPlayer(listener);
        if (now >= pet.listeningUntilMillis() || player == null || !player.getWorld().equals(body.getWorld())) {
            listeners.remove(pet.id());
            pet.listeningUntilMillis(0);
            return;
        }
        PetFx.look(body, player);
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
        attention.get(pet.id()).until = System.currentTimeMillis() + 120_000L;
        attention.get(pet.id()).fetchReturn = true;
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
        return job != null && job.fetchReturn;
    }

    double movementSpeed(Pet pet) {
        Attention job = attention.get(pet.id());
        return job == null ? 1.25 : job.speed;
    }

    private void attend(Pet pet, Player owner, long now, PetOrder returnOrder) {
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
                runtime.voice().happy(body, false);
                return true;
            }
            if (job.waitUntil == 0) job.waitUntil = now + Math.round(runtime.config().roaming().nameAttentionSeconds() * 1000.0);
            // The call goal owns MOVE, so the pet waits with native AI awake and its head on the owner.
            PetMotion.settle(body);
            PetFx.look(body, owner);
            if (!job.greeted) {
                runtime.voice().happy(body, false);
                PetFx.hearts(body, 2);
                job.greeted = true;
            }
        }
        return true;
    }

    private void restorePosture(Pet pet, Mob body, PetOrder order) {
        pet.order(order); pet.staying(order == PetOrder.STAY);
        pet.activity(Activity.NONE);
        // The saved order must survive cleanup while its body is in an unloaded chunk.
        if (body != null) {
            PetFx.lie(body, order == PetOrder.LAY);
            if (order != PetOrder.LAY) PetFx.sit(body, order == PetOrder.SIT);
            sleep.accept(body, false);
            if (order == PetOrder.FOLLOW) { PetMotion.stop(body); body.setAware(true); }
            else PostureNavigationGoal.hold(runtime, pet, body);
            var type = runtime.config().type(pet.typeId());
            if (type != null) runtime.visual().update(body, type,
                    order == PetOrder.LAY ? PetAnimation.LIE : order == PetOrder.SIT ? PetAnimation.SIT : PetAnimation.IDLE);
        }
        runtime.store().requestSave();
    }

    void cancel(Pet pet) { cancelAttention(pet); }
    void cancelWithPosture(Pet pet) {
        Attention job = attention.get(pet.id());
        cancel(pet);
        if (job != null && job.returnOrder != null)
            restorePosture(pet, runtime.entity(pet) instanceof Mob body ? body : null, job.returnOrder);
    }

    private void cancelAttention(Pet pet) {
        attention.remove(pet.id());
        if (pet.activity() == Activity.ATTENDING) pet.activity(Activity.NONE);
    }

    void clear() {
        for (var entry : java.util.List.copyOf(attention.entrySet())) {
            var pet = runtime.store().get(entry.getKey());
            if (pet != null && entry.getValue().returnOrder != null)
                restorePosture(pet, runtime.entity(pet) instanceof Mob body ? body : null, entry.getValue().returnOrder);
            else if (pet != null && pet.activity() == Activity.ATTENDING) pet.activity(Activity.NONE);
        }
        for (UUID id : listeners.keySet()) {
            var pet = runtime.store().get(id);
            if (pet != null) pet.listeningUntilMillis(0);
        }
        attention.clear(); owners.clear(); listeners.clear();
    }

    private static double horizontalSpeed(Player player) {
        return player.getVelocity().getX() * player.getVelocity().getX()
                + player.getVelocity().getZ() * player.getVelocity().getZ();
    }


    private static final class OwnerMotion {
        private Location location;
        private long lastMovedAt;
        private OwnerMotion(Location location, long now) { this.location = location; this.lastMovedAt = now; }
    }

    private static final class Attention {
        private boolean fetchReturn;
        private double speed = 1.25;
        private final UUID ownerId;
        private long until;
        private final PetOrder returnOrder;
        private long waitUntil;
        private boolean greeted;
        private Attention(UUID ownerId, long until, PetOrder returnOrder) { this.ownerId = ownerId; this.until = until; this.returnOrder = returnOrder; }
    }
}
