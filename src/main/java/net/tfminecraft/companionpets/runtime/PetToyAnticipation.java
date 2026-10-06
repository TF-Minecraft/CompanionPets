package net.tfminecraft.companionpets.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import net.tfminecraft.companionpets.behavior.GreetingMood;
import net.tfminecraft.companionpets.behavior.Locomotion;
import net.tfminecraft.companionpets.config.PetBehavior;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.integration.PetMotion;
import net.tfminecraft.companionpets.item.ItemRef;
import net.tfminecraft.companionpets.pet.*;

/** A held toy takes priority until played with, put away or ignored long enough to become boring. */
final class PetToyAnticipation {
    private final PetRuntime runtime;
    private final PetActions actions;
    private final Map<UUID, Focus> active = new HashMap<>();
    private final Map<UUID, Interest> interests = new HashMap<>();
    private final Map<UUID, Long> nextReaction = new HashMap<>();
    private final Map<UUID, Long> nextOwnerVoice = new HashMap<>();
    PetToyAnticipation(PetRuntime runtime, PetActions actions) { this.runtime = runtime; this.actions = actions; }
    void tick(long now) {
        for (Pet pet : runtime.store().all()) {
            Player owner = Bukkit.getPlayer(pet.ownerId());
            ItemRef toy = owner != null && owner.isOnline() ? heldToy(pet, owner) : null;
            if (toy == null) { interests.remove(pet.id()); cancel(pet); continue; }
            if (!(runtime.entity(pet) instanceof Mob body) || !ready(pet, body, owner, now)) {
                cancel(pet); continue;
            }
            if (!interested(pet, owner, toy, now)) { cancel(pet); continue; }
            Focus focus = active.get(pet.id());
            if (focus == null || focus.body != body || focus.owner != owner || !focus.toy.equals(toy.key())) {
                actions.clearInteractions(pet);
                PetFx.stopLooking(body);
                PetMotion.stop(body);
                pet.activity(Activity.TOY_FOCUS);
                focus = new Focus(body, toy.key(), owner);
                active.put(pet.id(), focus);
                ToyNavigationGoal.ensure(runtime, pet, body, this);
            }
            advance(pet, now);
        }
        active.keySet().removeIf(id -> {
            if (runtime.store().get(id) != null) return false;
            release(active.get(id)); return true;
        });
        nextReaction.keySet().removeIf(id -> runtime.store().get(id) == null);
        interests.keySet().removeIf(id -> runtime.store().get(id) == null);
        nextOwnerVoice.keySet().removeIf(id -> runtime.store().of(id).isEmpty());
    }

    boolean active(Pet pet) { return active.containsKey(pet.id()); }

    void advance(Pet pet, long now) {
        Focus focus = active.get(pet.id());
        if (focus == null) return;
        Player owner = focus.owner;
        ItemRef toy = owner.isOnline() ? heldToy(pet, owner) : null;
        if (toy == null) interests.remove(pet.id());
        if (pet.activity() != Activity.TOY_FOCUS || !ready(pet, focus.body, owner, now)
                || toy == null || !focus.toy.equals(toy.key()) || runtime.entity(pet) != focus.body
                || !interested(pet, owner, toy, now)) {
            cancel(pet); return;
        }
        Mob body = focus.body;
        body.setAware(true);
        PetFx.sit(body, false); PetFx.lie(body, false);
        PetFx.look(body, owner.getEyeLocation().subtract(0, 0.55, 0));
        boolean favorite = toy.key().equals(pet.favoriteToy());
        if (favorite && !focus.favorite) focus.reactionPending = true;
        focus.favorite = favorite;
        if (!favorite) { focus.reactionPending = false; focus.reactionUntil = 0; }
        boolean approaching = followToy(pet, focus, owner, now);
        boolean dog = body.getType() == org.bukkit.entity.EntityType.WOLF;
        if (dog && !approaching && focus.nextWaitingJumpAt == 0)
            focus.nextWaitingJumpAt = now + (favorite ? 8000 : 1500) + runtime.random().nextInt(1000);
        if (favorite && focus.reactionPending && !approaching) {
            focus.reactionPending = false;
            if (now >= nextReaction.getOrDefault(pet.id(), 0L)) {
                focus.reactionUntil = now + 6000;
                focus.sounds = focus.jumps = 0;
                focus.nextSoundAt = now + 350 + runtime.random().nextInt(600);
                focus.nextJumpAt = now + 1000;
                nextReaction.put(pet.id(), now + 15_000);
            }
        }
        pet.toyExcitedUntilMillis(favorite ? focus.reactionUntil : 0);
        boolean reacting = favorite && now < focus.reactionUntil;
        var feeling = GreetingMood.of(pet, pet.bond());
        if (reacting && runtime.behaves(pet, PetBehavior.TOY_VOCALIZING) && focus.sounds < 2 && now >= focus.nextSoundAt
                && now >= nextOwnerVoice.getOrDefault(pet.ownerId(), 0L)) {
            runtime.voice().play(body, net.tfminecraft.companionpets.config.PetSounds.Event.TOY,
                    (float) (.75 + .25 * feeling.intensity()), (float) (1 + runtime.random().nextDouble() * .1));
            focus.sounds++;
            focus.nextSoundAt = now + 3000 + runtime.random().nextInt(1000);
            nextOwnerVoice.put(pet.ownerId(), now + 2800);
        }
        boolean reactionHop = reacting && focus.jumps < 2 && now >= focus.nextJumpAt;
        boolean waitingHop = dog && focus.nextWaitingJumpAt > 0 && now >= focus.nextWaitingJumpAt;
        if (!approaching && runtime.behaves(pet, PetBehavior.TOY_JUMPS) && (reactionHop || waitingHop)
                && pet.need(Need.ENERGY) >= 50 && pet.need(Need.HEALTH) >= 70 && safeJump(body)) {
            body.setVelocity(body.getVelocity().setY(dog ? 0.30 + 0.06 * feeling.intensity() : 0.24 + 0.05 * feeling.intensity()));
            pet.need(Need.ENERGY, pet.need(Need.ENERGY) - 0.2);
            if (reactionHop) focus.jumps++;
            focus.nextJumpAt = now + 2300;
            focus.nextWaitingJumpAt = now + (favorite ? 5500 : 8500) + runtime.random().nextInt(2500);
        }
    }

    private boolean followToy(Pet pet, Focus focus, Player owner, long now) {
        Mob body = focus.body;
        Location ownerAt = owner.getLocation();
        boolean ownerMoving = focus.ownerAt != null && horizontalDistance(focus.ownerAt, ownerAt) > 0.12;
        // Sample owner movement at the navigation cadence, rather than individual AI ticks.
        if (now >= focus.nextMoveAt) {
            focus.ownerMoving = ownerMoving;
            focus.ownerAt = ownerAt;
        }
        Location front = PetSpacing.toyFront(runtime, pet, owner);
        boolean approachingOwner = front == null || horizontalDistance(body.getLocation(), front) > 1
                || Math.abs(body.getLocation().getY() - front.getY()) > 1;
        Location target = front;
        boolean dog = body.getType() == org.bukkit.entity.EntityType.WOLF;
        if (dog && front != null && !approachingOwner && !focus.ownerMoving && now >= focus.nextWiggleAt) {
            focus.wiggleStartedAt = now;
            focus.wiggleUntil = now + 2600;
            focus.nextWiggleAt = now + (focus.favorite ? 6500 : 9500) + runtime.random().nextInt(2000);
            focus.wiggleSide = runtime.random().nextBoolean() ? 1 : -1;
        }
        boolean playful = !focus.ownerMoving && (dog ? now < focus.wiggleUntil : focus.favorite && now < focus.reactionUntil);
        if (playful && front != null) {
            int step = (int) ((now - (dog ? focus.wiggleStartedAt : focus.reactionUntil - 6000)) / (dog ? 850 : 1500));
            double offset = dog
                    ? (step == 0 ? 0.85 : step == 1 ? -0.75 : 0.15) * focus.wiggleSide * (focus.favorite ? 1 : 0.7)
                    : switch (step) { case 0 -> 0.65; case 1 -> -0.5; case 2 -> 0.3; default -> 0; };
            double approach = dog && step == 1 ? -0.45 : 0;
            double yaw = Math.toRadians(ownerAt.getYaw());
            Location fidget = PetGreetings.safeGround(front.clone().add(
                    Math.cos(yaw) * offset - Math.sin(yaw) * approach, 0,
                    Math.sin(yaw) * offset + Math.cos(yaw) * approach));
            if (fidget != null && PetSpacing.free(runtime, pet, fidget)) target = fidget;
        }
        boolean moving = target != null && (horizontalDistance(body.getLocation(), target) > (playful ? 0.3 : 0.65)
                || Math.abs(body.getLocation().getY() - target.getY()) > 1);
        if (!moving) {
            PetMotion.stop(body);
        } else if (now >= focus.nextMoveAt) {
            var path = body.getPathfinder().findPath(target);
            if (path != null && path.canReachFinalPoint())
                body.getPathfinder().moveTo(path, Locomotion.speed(pet.illness(), pet.bond(), pet.need(Need.CLEANLINESS), false));
            else PetMotion.stop(body);
        }
        if (now >= focus.nextMoveAt) focus.nextMoveAt = now + 250;
        return approachingOwner || focus.ownerMoving;
    }

    private static double horizontalDistance(Location a, Location b) { return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ()); }
    private boolean interested(Pet pet, Player owner, ItemRef toy, long now) {
        Location at = owner.getLocation();
        Interest interest = interests.get(pet.id());
        if (interest == null || !interest.toy.equals(toy.key()) || !interest.ownerAt.getWorld().equals(at.getWorld())) {
            interest = new Interest(toy.key(), at, now);
            interests.put(pet.id(), interest);
        } else if (interest.ownerAt.distanceSquared(at) >= 0.25) {
            interest.ownerAt = at;
            interest.lastStimulusAt = now;
        }
        double seconds = toy.key().equals(pet.favoriteToy()) ? runtime.config().play().favoriteToyAttentionSeconds()
                : runtime.config().play().toyAttentionSeconds();
        // Retain the timestamp after releasing focus so the next behavior tick
        // cannot immediately reacquire the same boring toy.
        return now - interest.lastStimulusAt < seconds * 1000;
    }

    void ownerThrew(Player owner) {
        for (Pet pet : runtime.store().of(owner.getUniqueId())) interests.remove(pet.id());
    }
    private boolean ready(Pet pet, Mob body, Player owner, long now) {
        return runtime.behaves(pet, PetBehavior.TOY_ANTICIPATION) && !pet.stored() && !pet.dead()
                && body.isValid() && !body.isDead() && pet.fetch() == null
                && pet.activity() != Activity.SLEEPING && pet.activity() != Activity.TRICK && pet.order() == PetOrder.FOLLOW
                && !pet.staying() && now >= pet.forcedSitUntilMillis() && GreetingMood.of(pet, pet.bond()).mobile()
                && !body.isInWater() && body.getTarget() == null && owner != null && owner.isOnline()
                && pet.ownerId().equals(owner.getUniqueId())
                && owner.getWorld().equals(body.getWorld())
                && runtime.distance(owner, pet) <= (active(pet) ? 16 : 6)
                && runtime.followingAllowed(pet, owner)
                && !runtime.sessions().resting(pet.id(), now)
                && !(runtime.sessions().training(pet.ownerId()) instanceof net.tfminecraft.companionpets.session.TrainingSession s
                        && s.petId().equals(pet.id()));
    }

    private ItemRef heldToy(Pet pet, Player owner) {
        PetTypeDef type = runtime.config().type(pet.typeId());
        if (type == null) return null;
        ItemRef main = type.toy(owner.getInventory().getItemInMainHand());
        ItemRef off = type.toy(owner.getInventory().getItemInOffHand());
        if (main != null && main.key().equals(pet.favoriteToy())) return main;
        if (off != null && off.key().equals(pet.favoriteToy())) return off;
        return main == null ? off : main;
    }

    private static boolean safeJump(Mob body) {
        Location feet = body.getLocation();
        return body.isOnGround() && feet.clone().subtract(0, 0.1, 0).getBlock().getType().isSolid()
                && feet.clone().add(0, 1, 0).getBlock().isPassable()
                && feet.clone().add(0, 2, 0).getBlock().isPassable();
    }

    void cancel(Pet pet) {
        Focus focus = active.remove(pet.id());
        pet.toyExcitedUntilMillis(0);
        if (focus == null) return;
        release(focus);
        if (pet.activity() == Activity.TOY_FOCUS) pet.activity(Activity.NONE);
    }
    private void release(Focus focus) {
        // The native goal becomes inactive when its focus entry is removed. Removing
        // it here can corrupt Paper's running-goal iterator and delete the body.
        PetFx.stopLooking(focus.body);
        runtime.visual().wagTail(focus.body, 0);
        PetMotion.stop(focus.body);
    }
    void clear() { for (Pet pet : List.copyOf(runtime.store().all())) cancel(pet); active.clear(); interests.clear(); nextReaction.clear(); nextOwnerVoice.clear(); }

    private static final class Interest {
        final String toy;
        Location ownerAt;
        long lastStimulusAt;
        Interest(String toy, Location ownerAt, long now) { this.toy = toy; this.ownerAt = ownerAt; lastStimulusAt = now; }
    }

    private static final class Focus {
        final Mob body;
        final String toy;
        final Player owner;
        long nextSoundAt, nextJumpAt, nextMoveAt, nextWaitingJumpAt;
        long wiggleStartedAt, wiggleUntil, nextWiggleAt;
        int wiggleSide;
        Location ownerAt;
        boolean ownerMoving;
        boolean favorite, reactionPending;
        long reactionUntil;
        int sounds, jumps;
        Focus(Mob body, String toy, Player owner) { this.body = body; this.toy = toy; this.owner = owner; }
    }
}
