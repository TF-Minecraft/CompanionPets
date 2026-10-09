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
import org.bukkit.util.Vector;
import net.tfminecraft.companionpets.integration.PetMotion;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.behavior.GreetingMood;
import net.tfminecraft.companionpets.config.PetBehavior;
import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetOrder;

/** Remember real proximity, including unloaded pets, and celebrate one reunion per absence. */
final class PetGreetings {
    private final PetRuntime runtime;
    private final PetActions actions;
    private final Map<UUID, Greeting> active = new HashMap<>();

    PetGreetings(PetRuntime runtime, PetActions actions) {
        this.runtime = runtime;
        this.actions = actions;
    }

    void tick(long now) {
        for (UUID id : List.copyOf(active.keySet())) {
            Pet pet = runtime.store().get(id);
            if (pet != null && (pet.dead() || pet.stored())) cancel(pet);
        }
        // Saved pets are stamped when they leave the Pet House, so they need no tick here.
        for (Pet pet : runtime.store().active()) {
            Player owner = Bukkit.getPlayer(pet.ownerId());
            boolean near = owner != null && owner.isOnline()
                    && runtime.distance(owner, pet) <= runtime.config().greeting().nearRadius();
            if (pet.lastOwnerNearbyMillis() == 0) {
                // Old saves have no presence history: start tracking without an invented absence.
                pet.lastOwnerNearbyMillis(now);
            } else if (near) {
                boolean due = now - pet.lastOwnerNearbyMillis() >= millis(runtime.config().greeting().absenceSeconds());
                if (due && runtime.entity(pet) == null) continue; // Wait for the real body to load.
                if (due && now - pet.lastGreetingMillis() >= millis(runtime.config().greeting().cooldownSeconds()))
                    trigger(pet, owner, now);
                // Consume this return even if the pet is busy, sick, or greetings are disabled.
                pet.lastOwnerNearbyMillis(now);
            }
            advance(pet, now);
            recognizeCarers(pet, now);
        }
    }

    boolean active(Pet pet) { return active.containsKey(pet.id()); }

    private void recognizeCarers(Pet pet, long now) {
        if (pet.stored() || pet.dead() || !runtime.behaves(pet, PetBehavior.RECOGNIZE_CARERS)
                || !(runtime.entity(pet) instanceof Mob body)) return;
        for (var entry : List.copyOf(pet.carers().entries().entrySet())) {
            Player person = Bukkit.getPlayer(entry.getKey());
            if (person == null || !person.isOnline() || !person.getWorld().equals(body.getWorld())
                    || person.getUniqueId().equals(pet.ownerId()) || runtime.distance(person, pet) > 6) continue;
            var memory = entry.getValue();
            if (!active(pet) && memory.trust() >= net.tfminecraft.companionpets.pet.RelationshipMemory.FAMILIAR_AT
                    && now - memory.nearbyAt() >= 60_000 && now - memory.greetedAt() >= 120_000
                    && trigger(pet, person, now)) pet.carers().greeted(person.getUniqueId(), now);
            pet.carers().nearby(person.getUniqueId(), now);
        }
    }

    void ownerDeparted(Player owner, long now) {
        for (Pet pet : runtime.store().of(owner.getUniqueId())) {
            if (!pet.stored() && runtime.distance(owner, pet) <= runtime.config().greeting().nearRadius())
                pet.lastOwnerNearbyMillis(now);
        }
        // Persist the last proximity even when the owner's chunk unloads immediately after quitting.
        runtime.store().requestSave();
    }

    boolean trigger(Pet pet, Player owner, long now) {
        if (!(runtime.entity(pet) instanceof Mob body) || !ready(pet, body, owner, now)) return false;
        boolean reunion = pet.ownerId().equals(owner.getUniqueId());
        GreetingMood feeling = GreetingMood.of(pet, reunion ? pet.bond() : pet.carers().trust(owner.getUniqueId()));
        boolean mobile = reunion && feeling.mobile() && (runtime.behaves(pet, PetBehavior.GREETING_CIRCLES)
                || runtime.behaves(pet, PetBehavior.GREETING_APPROACH));
        actions.clearInteractions(pet);
        PetFx.stopLooking(body);
        runtime.visual().cancelAction(body);
        if (mobile) {
            pet.order(PetOrder.FOLLOW); pet.staying(false); pet.forcedSitUntilMillis(0);
            pet.activity(Activity.GREETING);
            actions.markSleep(body, false); PetFx.sit(body, false); PetFx.lie(body, false);
            body.setAware(true);
        }
        body.getPathfinder().stopPathfinding();
        if (reunion) { pet.lastGreetingMillis(now); pet.lastOwnerNearbyMillis(now); }
        Location center = owner.getLocation();
        Location at = body.getLocation();
        double angle = Math.atan2(at.getZ() - center.getZ(), at.getX() - center.getX());
        // Opposite directions help several pets greet without following one line.
        int direction = (pet.id().getLeastSignificantBits() & 1L) == 0 ? 1 : -1;
        int slot = 0;
        while (occupiedSlot(owner.getUniqueId(), slot)) slot++;
        Greeting job = new Greeting(body, owner, pet, now + (mobile
                ? millis(runtime.config().greeting().durationSeconds() * (0.65 + 0.35 * feeling.intensity())) : 2600),
                angle + slot * Math.PI / 2, direction, mobile, reunion, slot);
        active.put(pet.id(), job);
        job.phase = runtime.behaves(pet, PetBehavior.GREETING_CIRCLES) && feeling.intensity() >= 0.3 ? Phase.CIRCLE : Phase.FRONT;
        job.phaseUntil = now + circleMillis();
        GreetingNavigationGoal.ensure(runtime, pet, body, this);
        runtime.store().requestSave();
        PetFx.bar(owner, pet.name() + (!reunion ? " recognizes you" : !mobile ? " notices you've returned" : " is happy to see you!"));
        advance(pet, now);
        return true;
    }

    private boolean ready(Pet pet, Mob body, Player owner, long now) {
        return runtime.behaves(pet, PetBehavior.GREETING) && runtime.config().moments().enabled() && runtime.config().greeting().enabled()
                && !pet.stored() && !pet.dead() && pet.fetch() == null
                && (pet.activity() == Activity.NONE || pet.activity() == Activity.SLEEPING)
                && body.isValid() && !body.isDead() && !body.isInWater() && body.getTarget() == null
                && owner != null && owner.isOnline() && (pet.ownerId().equals(owner.getUniqueId())
                        || runtime.behaves(pet, PetBehavior.RECOGNIZE_CARERS) && pet.carers().familiar(owner.getUniqueId()))
                && (pet.ownerId().equals(owner.getUniqueId()) || !runtime.visual().holdsMovement(body) && !actions.social().engaged(pet))
                && body.getWorld().equals(owner.getWorld())
                && runtime.distance(owner, pet) <= runtime.config().greeting().nearRadius()
                && !runtime.sessions().resting(pet.id(), now)
                && !(runtime.sessions().training(pet.ownerId()) instanceof net.tfminecraft.companionpets.session.TrainingSession s
                        && s.petId().equals(pet.id()));
    }

    void advance(Pet pet, long now) {
        Greeting greeting = active.get(pet.id());
        if (greeting == null) return;
        Mob body = greeting.body;
        Player owner = greeting.owner;
        if (now >= greeting.until || pet.stored() || pet.dead()
                || (greeting.mobile ? pet.activity() != Activity.GREETING || pet.order() != PetOrder.FOLLOW
                        || pet.staying() || now < pet.forcedSitUntilMillis() || !mood(pet, greeting).mobile()
                        : pet.activity() != greeting.previousActivity || pet.order() != greeting.previousOrder
                                || pet.staying() != greeting.previousStaying || pet.forcedSitUntilMillis() != greeting.previousForcedSit)
                || !owner.isOnline() || !pet.ownerId().equals(greeting.ownerId)
                || !body.isValid() || body.isDead() || runtime.entity(pet) != body
                || !owner.getWorld().equals(body.getWorld()) || body.getTarget() != null || body.isInWater()
                || !runtime.behaves(pet, PetBehavior.GREETING) || !runtime.config().moments().enabled() || !runtime.config().greeting().enabled()
                || runtime.sessions().training(pet.ownerId()) instanceof net.tfminecraft.companionpets.session.TrainingSession s
                        && s.petId().equals(pet.id())
                || body.getLocation().distanceSquared(owner.getLocation()) > square(runtime.config().greeting().nearRadius() * 2)) {
            cancel(pet);
            return;
        }
        if (now < greeting.nextStepAt) return;
        greeting.nextStepAt = now + 200;
        if (!greeting.mobile) {
            body.getPathfinder().stopPathfinding();
            if (pet.activity() != Activity.SLEEPING) {
                PetFx.holdLooking(body, runtime.visual()); PetFx.look(body, owner.getEyeLocation());
            }
            if (greeting.nextSoundAt == 0) {
                runtime.voice().play(body, pet.illness() != Illness.NONE
                        ? net.tfminecraft.companionpets.config.PetSounds.Event.SAD
                        : net.tfminecraft.companionpets.config.PetSounds.Event.GREETING, .4f, 1);
                if (pet.illness() == Illness.NONE && pet.need(Need.HEALTH) >= 50) PetFx.hearts(body, 1);
                greeting.nextSoundAt = Long.MAX_VALUE;
            }
            return;
        }
        double excitement = mood(pet, greeting).intensity();
        if (now >= greeting.nextSoundAt) {
            boolean cat = runtime.behaves(pet, PetBehavior.GREETING_MEOWS);
            runtime.voice().play(body, net.tfminecraft.companionpets.config.PetSounds.Event.GREETING,
                    (float) (0.5 + 0.5 * excitement), cat ? (float) (.95 + runtime.random().nextDouble() * .2) : 1);
            PetFx.hearts(body, 1);
            double interval = cat ? runtime.config().greeting().catSoundIntervalSeconds()
                    : runtime.config().greeting().soundIntervalSeconds();
            // Slightly uneven meows and short gaps keep the conversation from sounding like a metronome.
            greeting.nextSoundAt = now + millis((cat ? interval * (0.85 + runtime.random().nextDouble() * 0.3) : interval)
                    * (1.4 - 0.4 * excitement));
            if (cat && ++greeting.meows % 4 == 0) greeting.nextSoundAt += 400;
        }
        Location center = owner.getLocation();
        boolean approach = runtime.behaves(pet, PetBehavior.GREETING_APPROACH);
        boolean cat = runtime.behaves(pet, PetBehavior.GREETING_MEOWS);
        if (!runtime.behaves(pet, PetBehavior.GREETING_CIRCLES) && greeting.phase == Phase.CIRCLE) greeting.phase = Phase.FRONT;
        if (approach && now >= greeting.phaseUntil && runtime.behaves(pet, PetBehavior.GREETING_CIRCLES) && !cat) {
            greeting.phase = greeting.phase == Phase.CIRCLE ? Phase.FRONT : Phase.CIRCLE;
            greeting.phaseUntil = now + (greeting.phase == Phase.CIRCLE ? circleMillis() : 2400);
            greeting.target = null;
            greeting.circleSteps = 0;
        }
        if (greeting.phase != Phase.CIRCLE) {
            greetInFront(pet, greeting, center, now);
            return;
        }
        if (cat && now < greeting.pauseUntil) {
            body.getPathfinder().stopPathfinding();
            PetFx.look(body, owner.getEyeLocation());
            return;
        }
        if (greeting.target == null || body.getLocation().distanceSquared(greeting.target) < 0.6
                || now >= greeting.nextWaypointAt) {
            greeting.angle += greeting.direction * (0.55 + runtime.random().nextDouble() * 0.5);
            greeting.radiusFactor = 0.85 + runtime.random().nextDouble() * 0.3;
            greeting.nextWaypointAt = now + 1400 + runtime.random().nextInt(500);
            greeting.circleSteps++;
            if (approach && greeting.circleSteps > 8) greeting.phaseUntil = now;
            if (cat && greeting.circleSteps % 3 == 0) {
                greeting.pauseUntil = now + 600;
            }
        }
        // Rebuild around the owner's current position, so walking does not leave the circle behind.
        double radius = (cat ? runtime.config().greeting().catCircleRadius() : runtime.config().greeting().circleRadius())
                * greeting.radiusFactor + 0.55 * (greeting.slot / 4);
        Location target = center.clone().add(Math.cos(greeting.angle) * radius, 0, Math.sin(greeting.angle) * radius);
        Location ground = safeGround(target);
        if (ground == null || !free(pet, greeting, ground)) {
            body.getPathfinder().stopPathfinding();
            greeting.angle += greeting.direction * Math.PI / 4;
            return;
        }
        var path = PetMotion.findPath(body, ground);
        if (path == null || !path.canReachFinalPoint()) {
            body.getPathfinder().stopPathfinding();
            greeting.angle += greeting.direction * Math.PI / 4;
            return;
        }
        greeting.target = ground;
        if (cat && now < greeting.pauseUntil) {
            body.getPathfinder().stopPathfinding();
            PetFx.look(body, owner.getEyeLocation());
            return;
        }
        double speed = cat ? runtime.config().greeting().catSpeed()
                : runtime.config().greeting().speed() * (0.65 + 0.35 * excitement);
        if (!runtime.behaves(pet, PetBehavior.GREETING_TAIL_WAG)) speed = Math.min(1, speed);
        PetMotion.moveTo(body, path, speed);
    }

    private long circleMillis() {
        return Math.min(3500, Math.round(runtime.config().greeting().durationSeconds() * 400));
    }

    private void greetInFront(Pet pet, Greeting greeting, Location center, long now) {
        Mob body = greeting.body;
        Vector forward = center.getDirection().setY(0);
        if (forward.lengthSquared() < 0.001) forward = new Vector(0, 0, 1);
        forward.normalize();
        Vector side = new Vector(-forward.getZ(), 0, forward.getX());
        double separation = Math.max(1.2, body.getWidth() + 0.35);
        double lateral = greeting.slot == 0 ? 0 : ((greeting.slot + 1) / 2) * separation * (greeting.slot % 2 == 0 ? -1 : 1);
        Location front = safeGround(center.clone().add(forward.multiply(1.8)).add(side.multiply(lateral)));
        if (front == null || !free(pet, greeting, front)) { body.getPathfinder().stopPathfinding(); return; }
        greeting.target = front;
        double ownerDistance = horizontalDistance(body.getLocation(), center);
        if (greeting.phase == Phase.FRONT && horizontalDistance(body.getLocation(), front) < 0.85
                && Math.abs(body.getLocation().getY() - front.getY()) < 1) {
            greeting.phase = Phase.BOUNCE;
            greeting.phaseUntil = now + 1800;
            greeting.nextJumpAt = now;
            body.getPathfinder().stopPathfinding();
        }
        if (greeting.phase == Phase.BOUNCE && ownerDistance < 2.6) {
            body.getPathfinder().stopPathfinding();
            PetFx.look(body, greeting.owner.getEyeLocation());
            if (runtime.behaves(pet, PetBehavior.GREETING_JUMPS) && mood(pet, greeting).jumps()
                    && runtime.config().greeting().jumpEnabled() && now >= greeting.nextJumpAt
                    && body.isOnGround() && Math.abs(body.getLocation().getY() - front.getY()) < 1
                    && body.getLocation().clone().add(0, 1, 0).getBlock().isPassable()
                    && body.getLocation().clone().add(0, 2, 0).getBlock().isPassable()) {
                Vector offset = body.getLocation().toVector().subtract(center.toVector()).setY(0);
                if (offset.lengthSquared() > 0.001) offset.normalize().multiply(0.7);
                else offset.zero();
                Location landing = safeGround(center.clone().add(offset));
                if (landing == null || !free(pet, greeting, landing) || !safeHop(body.getLocation(), landing)) return;
                var path = PetMotion.findPath(body, landing);
                if (path == null || !path.canReachFinalPoint()) return;
                Vector toward = center.toVector().subtract(body.getLocation().toVector()).setY(0);
                double length = toward.length();
                if (length > 0.01) toward.multiply(Math.min(0.18, Math.max(0, (length - 0.7) / 8)) / length);
                else toward.zero();
                net.tfminecraft.companionpets.integration.PetMotion.stop(body);
                body.setVelocity(toward.setY(0.28 + 0.08 * mood(pet, greeting).intensity()));
                greeting.nextJumpAt = now + 900;
            }
            return;
        }
        var path = PetMotion.findPath(body, front);
        double speed = runtime.behaves(pet, PetBehavior.GREETING_MEOWS) ? runtime.config().greeting().catSpeed()
                : runtime.config().greeting().speed() * (0.65 + 0.35 * mood(pet, greeting).intensity());
        if (!runtime.behaves(pet, PetBehavior.GREETING_TAIL_WAG)) speed = Math.min(1, speed);
        if (path != null && path.canReachFinalPoint()) PetMotion.moveTo(body, path, speed);
        PetFx.look(body, greeting.owner.getEyeLocation());
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private static boolean safeHop(Location from, Location to) {
        for (int i = 1; i <= 3; i++) {
            Location sample = from.clone().add(to.toVector().subtract(from.toVector()).multiply(i / 3.0));
            Location floor = safeGround(sample);
            if (floor == null || Math.abs(floor.getY() - from.getY()) > 0.5
                    || !floor.clone().add(0, 1, 0).getBlock().isPassable()
                    || !floor.clone().add(0, 2, 0).getBlock().isPassable()) return false;
        }
        return true;
    }

    static Location safeGround(Location target) {
        if (!target.getWorld().isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4)) return null;
        for (int dy : new int[]{0, 1, -1, 2, -2}) {
            Location feet = target.clone();
            feet.setY(target.getBlockY() + dy);
            var floor = feet.clone().subtract(0, 1, 0).getBlock();
            if (floor.getType().isSolid() && !floor.isPassable()
                    && feet.getBlock().isPassable() && !feet.getBlock().isLiquid()
                    && feet.clone().add(0, 1, 0).getBlock().isPassable()
                    && !floor.getType().name().contains("MAGMA") && !floor.getType().name().contains("CAMPFIRE"))
                return feet;
        }
        return null;
    }

    void cancel(Pet pet) {
        Greeting greeting = active.remove(pet.id());
        if (greeting == null) return;
        runtime.visual().wagTail(greeting.body, 0);
        PetFx.releaseLooking(greeting.body);
        greeting.body.getPathfinder().stopPathfinding();
        if (greeting.mobile && pet.activity() == Activity.GREETING) pet.activity(Activity.NONE);
    }

    void clear() {
        for (Pet pet : List.copyOf(runtime.store().all())) cancel(pet);
    }

    private static long millis(double seconds) { return Math.round(seconds * 1000); }
    private static double square(double value) { return value * value; }
    private GreetingMood mood(Pet pet, Greeting job) {
        return GreetingMood.of(pet, job.reunion ? pet.bond() : pet.carers().trust(job.owner.getUniqueId()));
    }
    private boolean occupiedSlot(UUID recipient, int slot) {
        return active.values().stream().anyMatch(g -> g.mobile && g.owner.getUniqueId().equals(recipient) && g.slot == slot);
    }
    private boolean free(Pet pet, Greeting job, Location point) {
        if (!PetSpacing.free(runtime, pet, point)) return false;
        return active.values().stream().noneMatch(g -> g != job && g.mobile && g.target != null
                && g.target.getWorld().equals(point.getWorld()) && g.target.distanceSquared(point)
                        < square(Math.max(1, (job.body.getWidth() + g.body.getWidth()) * 0.5 + 0.25)));
    }
    private enum Phase { CIRCLE, FRONT, BOUNCE }

    private static final class Greeting {
        final Mob body;
        final Player owner;
        final long until;
        final int direction;
        final boolean mobile, reunion;
        final UUID ownerId;
        final int slot;
        final Activity previousActivity;
        final PetOrder previousOrder;
        final boolean previousStaying;
        final long previousForcedSit;
        double radiusFactor = 1;
        double angle;
        long nextStepAt;
        long nextSoundAt;
        long nextWaypointAt;
        Location target;
        Phase phase = Phase.CIRCLE;
        long phaseUntil;
        long nextJumpAt;
        int circleSteps;
        int meows;
        long pauseUntil;

        Greeting(Mob body, Player owner, Pet pet, long until, double angle, int direction, boolean mobile, boolean reunion, int slot) {
            this.body = body;
            this.owner = owner;
            this.until = until;
            this.angle = angle;
            this.direction = direction;
            this.mobile = mobile; this.reunion = reunion; this.slot = slot; ownerId = pet.ownerId();
            previousActivity = pet.activity(); previousOrder = pet.order(); previousStaying = pet.staying();
            previousForcedSit = pet.forcedSitUntilMillis();
        }
    }
}
