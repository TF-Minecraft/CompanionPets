package net.tfminecraft.companionpets.runtime;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import net.tfminecraft.companionpets.behavior.GreetingMood;
import net.tfminecraft.companionpets.behavior.Locomotion;
import net.tfminecraft.companionpets.behavior.PetMeetingMood;
import net.tfminecraft.companionpets.config.PetBehavior;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.integration.PetMotion;
import net.tfminecraft.companionpets.pet.*;

/** Short meetings, with independent reactions and rewards for completed positive interactions. */
final class PetSocial {
    private final PetRuntime runtime;
    private final PetRoaming roaming;
    private final Map<Pair, Encounter> active = new HashMap<>();
    private final Map<UUID, Pair> meetingByPet = new HashMap<>();
    private final Map<Pair, Long> nextAllowed = new HashMap<>();
    private final Map<Pair, Visit> visits = new HashMap<>();
    private final Map<Pair, ArrayDeque<Gain>> gains = new HashMap<>();

    PetSocial(PetRuntime runtime, PetRoaming roaming) { this.runtime = runtime; this.roaming = roaming; }

    void tick(long now) {
        var settings = runtime.config().social();
        if (!settings.enabled()) { clear(); return; }
        nextAllowed.entrySet().removeIf(e -> e.getValue() < now - 300_000);
        gains.values().forEach(queue -> queue.removeIf(g -> g.at <= now - 60_000));
        gains.values().removeIf(ArrayDeque::isEmpty);
        refreshVisits(now);
        for (var entry : List.copyOf(active.entrySet()))
            if (active.get(entry.getKey()) == entry.getValue()) advance(entry.getKey(), entry.getValue(), now);
        for (Pet pet : runtime.store().all()) {
            if (!available(pet) || engaged(pet)) continue;
            Mob body = body(pet);
            if (body == null) continue;
            for (Entity entity : body.getNearbyEntities(settings.encounterRadius(), 3, settings.encounterRadius())) {
                Pet other = runtime.byEntity(entity);
                if (other == null || pet.id().compareTo(other.id()) >= 0 || !available(other) || engaged(other)
                        || !(entity instanceof Mob otherBody)) continue;
                double distance = body.getLocation().distanceSquared(otherBody.getLocation());
                if (distance > square(settings.encounterRadius()) || !ownersNearby(pet, body, other, otherBody)
                        || !body.hasLineOfSight(otherBody)) continue;
                Pair pair = Pair.of(pet, other);
                Visit visit = visits.computeIfAbsent(pair, ignored -> new Visit());
                visit.seenAt = now;
                if (!visit.greeted && distance <= square(settings.greetingRadius())
                        && both(pet, other, PetBehavior.SOCIAL_GREETING)) {
                    if (begin(pair, pet, other, body, otherBody, now, "greeting", true)) {
                        visit.greeted = true;
                        break;
                    }
                } else if ((visit.greeted || !both(pet, other, PetBehavior.SOCIAL_GREETING))
                        && now >= nextAllowed.getOrDefault(pair, 0L) && ownersStationary(pet, other, now)
                        && begin(pair, pet, other, body, otherBody, now, null, true)) break;
            }
        }
    }

    private void refreshVisits(long now) {
        for (var entry : List.copyOf(visits.entrySet())) {
            Visit visit = entry.getValue();
            Mob a = body(runtime.store().get(entry.getKey().first));
            Mob b = body(runtime.store().get(entry.getKey().second));
            boolean near = a != null && b != null && a.getWorld().equals(b.getWorld())
                    && a.getLocation().distanceSquared(b.getLocation()) <= square(runtime.config().social().greetingRadius() + 2);
            if (near) { visit.separatedAt = 0; visit.seenAt = now; }
            else {
                if (visit.separatedAt == 0) visit.separatedAt = now;
                if (now - visit.separatedAt >= runtime.config().social().separationSeconds() * 1000) visit.greeted = false;
                if (now - visit.seenAt > 300_000) visits.remove(entry.getKey());
            }
        }
    }

    boolean calm(Player player, Pet pet) {
        if (!pet.ownerId().equals(player.getUniqueId()) && !player.hasPermission("companionpets.test")) return false;
        for (var entry : List.copyOf(active.entrySet())) {
            Encounter e = entry.getValue();
            if (e.phase != Phase.BARK || e.a != pet && e.b != pet) continue;
            if (body(e.a) != null) PetFx.hearts(body(e.a), 2);
            if (body(e.b) != null) PetFx.hearts(body(e.b), 2);
            end(entry.getKey(), System.currentTimeMillis(), runtime.config().social().calmCooldownSeconds());
            return true;
        }
        return false;
    }
    String status(Pet pet) {
        for (Encounter e : active.values())
            if ((e.a == pet || e.b == pet) && e.phase == Phase.BARK) return "Barking - right-click repeatedly to calm";
        return null;
    }
    void cancel(Pet pet) {
        Pair pair = meetingByPet.get(pet.id());
        if (pair != null) end(pair, System.currentTimeMillis(), runtime.config().social().encounterCooldownSeconds());
    }
    void clear() {
        for (Pair pair : List.copyOf(active.keySet())) end(pair, System.currentTimeMillis(), 0);
        meetingByPet.clear(); nextAllowed.clear(); visits.clear(); gains.clear();
    }

    boolean trigger(Player owner, Pet pet, String kind) { return trigger(owner, pet, kind, System.currentTimeMillis()); }
    boolean trigger(Player owner, Pet pet, String kind, long now) {
        Mob body = body(pet);
        if (!runtime.config().social().enabled() || body == null || !available(pet)
                || !pet.ownerId().equals(owner.getUniqueId())) return false;
        Pet other = null;
        Mob otherBody = null;
        double best = square("greeting".equals(kind) ? runtime.config().social().greetingRadius()
                : runtime.config().social().encounterRadius());
        for (Entity candidate : body.getNearbyEntities(Math.sqrt(best), 3, Math.sqrt(best))) {
            Pet found = runtime.byEntity(candidate);
            if (found == null || found == pet || !available(found) || !(candidate instanceof Mob mob)
                    || !body.hasLineOfSight(mob) || !ownersNearby(pet, body, found, mob)) continue;
            double distance = body.getLocation().distanceSquared(mob.getLocation());
            if (distance <= best) { other = found; otherBody = mob; best = distance; }
        }
        if (other == null) return false;
        cancel(pet); cancel(other);
        Pair pair = Pair.of(pet, other);
        boolean started = begin(pair, pet, other, body, otherBody, now, kind, false);
        if (started) {
            Visit visit = visits.computeIfAbsent(pair, ignored -> new Visit());
            visit.seenAt = now; visit.greeted = true;
        }
        return started;
    }

    private boolean begin(Pair pair, Pet a, Pet b, Mob bodyA, Mob bodyB, long now, String forced, boolean automatic) {
        var settings = runtime.config().social();
        PetMeetingMood moodA = mood(a, b), moodB = mood(b, a);
        boolean territorial = a.personality() == PetPersonality.TERRITORIAL && b.personality() == PetPersonality.TERRITORIAL;
        String choice = forced;
        if (choice == null) {
            double barkChance = (a.ownerId().equals(b.ownerId()) ? settings.sameOwnerBarkChance()
                    : settings.territorialBarkChance()) * (1 - 0.8 * friendship(a, b) / 100);
            if (territorial && friendship(a, b) < RelationshipMemory.FAMILIAR_AT && both(a, b, PetBehavior.SOCIAL_PROTEST)
                    && bodyA.getLocation().distanceSquared(bodyB.getLocation()) <= square(settings.barkRadius())
                    && runtime.random().nextDouble() * 100 < barkChance) choice = "bark";
            else {
                double chaseChance = settings.chaseChance() * (a.personality() == PetPersonality.PLAYFUL
                        || b.personality() == PetPersonality.PLAYFUL ? 1.25 : 0.65) * (1 + friendship(a, b) / 200);
                if (both(a, b, PetBehavior.SOCIAL_CHASE) && moodA.acceptsPlay() && moodB.acceptsPlay()
                        && runtime.random().nextDouble() * 100 < chaseChance) choice = "chase";
                else if (moodA.reaction() != PetMeetingMood.Reaction.GUARD && moodB.reaction() != PetMeetingMood.Reaction.GUARD
                        && moodA.reaction() != PetMeetingMood.Reaction.OBSERVE && moodB.reaction() != PetMeetingMood.Reaction.OBSERVE)
                    choice = "sniff";
                else choice = "observe";
            }
        }
        Phase phase = switch (choice) { case "greeting" -> Phase.GREET; case "sniff" -> Phase.SNIFF; case "observe" -> Phase.OBSERVE;
            case "chase" -> Phase.CHASE; case "bark" -> Phase.BARK; default -> null; };
        if (phase == null || !both(a, b, switch (phase) {
            case GREET -> PetBehavior.SOCIAL_GREETING; case SNIFF, OBSERVE -> PetBehavior.SOCIAL_SNIFF;
            case CHASE -> PetBehavior.SOCIAL_CHASE; case BARK -> PetBehavior.SOCIAL_PROTEST;
        }) || phase == Phase.CHASE && (!moodA.acceptsPlay() || !moodB.acceptsPlay())
                || phase == Phase.BARK && (!territorial || friendship(a, b) >= RelationshipMemory.FAMILIAR_AT
                        || bodyA.getLocation().distanceSquared(bodyB.getLocation()) > square(settings.barkRadius()))) return false;
        Encounter e = new Encounter(a, b, phase, now, automatic, moodA, moodB);
        active.put(pair, e);
        meetingByPet.put(a.id(), pair); meetingByPet.put(b.id(), pair);
        roaming.cancel(a); roaming.cancel(b);
        for (Mob body : List.of(bodyA, bodyB)) {
            PetFx.sit(body, false); PetFx.lie(body, false); body.setAware(true);
            if (body instanceof org.bukkit.entity.Wolf wolf) net.tfminecraft.companionpets.integration.WolfShake.defer(wolf);
        }
        PetMotion.stop(bodyA); PetMotion.stop(bodyB);
        SocialNavigationGoal.ensure(runtime, a, bodyA, this);
        SocialNavigationGoal.ensure(runtime, b, bodyB, this);
        if (phase == Phase.GREET) {
            receive(a, bodyA, bodyB, moodA, e); receive(b, bodyB, bodyA, moodB, e);
        } else if (phase == Phase.BARK) bark(e, bodyA, bodyB);
        return true;
    }

    void advance(Pet pet, long now) {
        Pair pair = meetingByPet.get(pet.id());
        Encounter encounter = pair == null ? null : active.get(pair);
        if (encounter != null) advance(pair, encounter, now);
    }
    private void advance(Pair pair, Encounter e, long now) {
        var settings = runtime.config().social();
        Mob a = body(e.a), b = body(e.b);
        if (a == null || b == null || !available(e.a) || !available(e.b) || !a.getWorld().equals(b.getWorld())
                || !ownersNearby(e, a, b) || e.automatic && e.phase != Phase.GREET && !ownersStationary(e.a, e.b, now)) {
            end(pair, now, settings.encounterCooldownSeconds()); return;
        }
        double distance = a.getLocation().distanceSquared(b.getLocation());
        if (distance > square(settings.encounterRadius()) || !a.hasLineOfSight(b)) {
            end(pair, now, settings.encounterCooldownSeconds()); return;
        }
        long age = now - e.startedAt;
        PetFx.look(a, b); PetFx.look(b, a);
        if (e.phase == Phase.GREET || e.phase == Phase.SNIFF || e.phase == Phase.OBSERVE) {
            double space = e.phase == Phase.SNIFF ? 1.2 : Math.max(e.moodA.personalSpace(), e.moodB.personalSpace());
            space = Math.max(space, (a.getWidth() + b.getWidth()) * 0.5 + 0.25);
            if (distance <= square(space + 0.5)) {
                if (e.closeSince == 0) e.closeSince = now;
                PetMotion.stop(a); PetMotion.stop(b);
                if (e.phase == Phase.GREET && age >= 500) {
                    if (!e.hoppedA) e.hoppedA = hop(e.a, a, e.moodA);
                    if (!e.hoppedB) e.hoppedB = hop(e.b, b, e.moodB);
                }
                if (now >= e.nextFxAt && e.positive) {
                    PetFx.particle(a, Particle.HAPPY_VILLAGER, 1); PetFx.particle(b, Particle.HAPPY_VILLAGER, 1);
                    e.nextFxAt = now + 2000;
                }
            } else {
                e.closeSince = 0;
                if (now >= e.nextMoveAt) {
                    if (e.phase == Phase.SNIFF || e.moodA.approaches()) approach(a, b, space, e.moodA.intensity());
                    if (e.phase == Phase.SNIFF || e.moodB.approaches()) approach(b, a, space, e.moodB.intensity());
                    e.nextMoveAt = now + 350;
                }
            }
            long duration = e.phase == Phase.GREET ? Math.round(settings.greetingSeconds() * 1000)
                    : e.phase == Phase.OBSERVE ? 4000 : 5000;
            if (age >= duration) {
                if (e.positive && e.closeSince > 0 && now - e.closeSince >= 1000)
                    rememberFriends(pair, e, now, e.phase == Phase.SNIFF ? settings.sniffFriendshipGain() : settings.greetingFriendshipGain());
                end(pair, now, settings.encounterCooldownSeconds());
            }
        } else if (e.phase == Phase.CHASE) {
            double moved = a.getLocation().distanceSquared(e.startA) + b.getLocation().distanceSquared(e.startB);
            if (moved >= 1 && distance < square(5) && e.playedSince == 0) e.playedSince = now;
            if (now >= e.nextMoveAt) {
                // One runner chooses an irregular destination; its partner follows with personal space.
                Mob runner = e.runnerA ? a : b, follower = e.runnerA ? b : a;
                double angle = runtime.random().nextDouble() * Math.PI * 2;
                double radius = 1.2 + runtime.random().nextDouble() * 1.3;
                Location center = a.getLocation().add(b.getLocation()).multiply(0.5);
                navigate(runner, center.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius), 1.05);
                approach(follower, runner, Math.max(1.1, (a.getWidth() + b.getWidth()) * 0.5 + 0.25), 0.8);
                e.nextMoveAt = now + 1200 + runtime.random().nextInt(600);
                if (runtime.random().nextDouble() < 0.3) e.runnerA = !e.runnerA;
            }
            if (age >= 6000) {
                if (e.playedSince > 0 && now - e.playedSince >= 1500)
                    rememberFriends(pair, e, now, settings.chaseFriendshipGain());
                end(pair, now, settings.encounterCooldownSeconds());
            }
        } else if (distance > square(settings.barkRadius()) || age >= settings.barkSeconds() * 1000)
            end(pair, now, settings.encounterCooldownSeconds());
    }

    private void receive(Pet pet, Mob body, Mob partner, PetMeetingMood mood, Encounter e) {
        if (runtime.behaves(pet, PetBehavior.SOCIAL_TAIL_WAG) && mood.intensity() > 0.1) {
            pet.socialTailHz(1 + 3 * mood.intensity());
            runtime.visual().wagTail(body, pet.socialTailHz());
        }
        if (mood.reaction() == PetMeetingMood.Reaction.GUARD
                && body.getLocation().distanceSquared(partner.getLocation()) < square(1.4)
                && runtime.behaves(pet, PetBehavior.SOCIAL_PROTEST)) { growl(body); e.positive = false; }
        else if (mood.intensity() >= 0.2 && mood.reaction() != PetMeetingMood.Reaction.GUARD
                && runtime.behaves(pet, PetBehavior.SOCIAL_VOCALIZING)) {
            if (runtime.voice().play(body, net.tfminecraft.companionpets.config.PetSounds.Event.SOCIAL,
                    1, (float) (.95 + mood.intensity() * .15))) {
                runtime.visual().play(body, runtime.config().type(pet.typeId()), "SPEAK");
            }
        }
    }
    private boolean hop(Pet pet, Mob body, PetMeetingMood mood) {
        if (!mood.hops() || !runtime.behaves(pet, PetBehavior.SOCIAL_JUMPS)) return true;
        Location feet = body.getLocation();
        if (!body.isOnGround() || !feet.clone().subtract(0, 0.1, 0).getBlock().getType().isSolid()
                || !feet.clone().add(0, 1, 0).getBlock().isPassable()
                || !feet.clone().add(0, 2, 0).getBlock().isPassable()) return false;
        body.setVelocity(new Vector(0, 0.23 + 0.07 * mood.intensity(), 0));
        pet.need(Need.ENERGY, pet.need(Need.ENERGY) - 0.15);
        return true;
    }
    private void approach(Mob body, Mob partner, double space, double enthusiasm) {
        Vector offset = partner.getLocation().toVector().subtract(body.getLocation().toVector()).setY(0);
        double distance = offset.length();
        if (distance <= space + 0.25) { body.getPathfinder().stopPathfinding(); return; }
        // Paper rounds paths to block nodes. A half-distance target can stay inside
        // the current block forever. Both pets stop as soon as personal space is reached.
        offset.multiply((distance - space) / distance);
        navigate(body, body.getLocation().add(offset), 0.65 + 0.35 * enthusiasm);
    }
    private void navigate(Mob body, Location target, double speed) {
        Location ground = PetGreetings.safeGround(target);
        Pet pet = runtime.byEntity(body);
        if (ground == null || pet != null && !PetSpacing.free(runtime, pet, ground)) {
            body.getPathfinder().stopPathfinding(); return;
        }
        var path = body.getPathfinder().findPath(ground);
        if (path != null && path.canReachFinalPoint()) body.getPathfinder().moveTo(path, speed);
        else body.getPathfinder().stopPathfinding();
    }
    private void bark(Encounter e, Mob a, Mob b) {
        PetFx.look(a, b); PetFx.look(b, a);
        growl(a); growl(b);
        PetFx.particle(a, Particle.ANGRY_VILLAGER, 2); PetFx.particle(b, Particle.ANGRY_VILLAGER, 2);
    }
    private void growl(Mob body) {
        Pet pet = runtime.byEntity(body);
        if (pet != null) runtime.visual().play(body, runtime.config().type(pet.typeId()), "SPEAK");
        runtime.voice().play(body, net.tfminecraft.companionpets.config.PetSounds.Event.PROTEST);
    }

    private boolean ownersNearby(Pet a, Mob bodyA, Pet b, Mob bodyB) {
        return ownersNearby(Bukkit.getPlayer(a.ownerId()), bodyA, Bukkit.getPlayer(b.ownerId()), bodyB);
    }
    private boolean ownersNearby(Encounter e, Mob a, Mob b) {
        return e.a.ownerId().equals(e.ownerIdA) && e.b.ownerId().equals(e.ownerIdB)
                && ownersNearby(e.ownerA, a, e.ownerB, b);
    }
    private boolean ownersNearby(Player a, Mob bodyA, Player b, Mob bodyB) {
        double radius = square(runtime.config().social().ownerRadius());
        boolean aNear = near(a, bodyA, radius), bNear = near(b, bodyB, radius);
        return aNear && bNear || aNear && b == null && near(a, bodyB, radius)
                || bNear && a == null && near(b, bodyA, radius);
    }
    private boolean ownersStationary(Pet a, Pet b, long now) {
        Player first = Bukkit.getPlayer(a.ownerId()), second = Bukkit.getPlayer(b.ownerId());
        return (first == null || roaming.ownerStationary(first, now)) && (second == null || roaming.ownerStationary(second, now));
    }
    private static boolean near(Player owner, Mob body, double radius) {
        return owner != null && owner.isOnline() && owner.getWorld().equals(body.getWorld())
                && owner.getLocation().distanceSquared(body.getLocation()) <= radius;
    }
    private boolean available(Pet pet) {
        if (pet == null || runtime.store().get(pet.id()) != pet) return false;
        Mob body = body(pet);
        var training = runtime.sessions().training(pet.ownerId());
        return (runtime.behaves(pet, PetBehavior.SOCIAL_GREETING) || runtime.behaves(pet, PetBehavior.SOCIAL_SNIFF)
                || runtime.behaves(pet, PetBehavior.SOCIAL_CHASE) || runtime.behaves(pet, PetBehavior.SOCIAL_PROTEST))
                && body != null && body.isValid() && !body.isDead() && body.getTarget() == null && !body.isInWater()
                && !runtime.visual().holdsMovement(body) && !pet.stored() && !pet.dead()
                && pet.activity() == Activity.NONE && pet.fetch() == null && pet.carriedToy() == null
                && (training == null || !training.petId().equals(pet.id()))
                && GreetingMood.of(pet, pet.bond()).mobile()
                && Locomotion.choose(pet.illness(), pet.need(Need.HEALTH), pet.need(Need.ENERGY), pet.need(Need.HUNGER),
                        pet.activity(), false, System.currentTimeMillis() < pet.forcedSitUntilMillis(), pet.order(), pet.staying()) == Locomotion.Mode.FOLLOW;
    }
    boolean engaged(Pet pet) {
        return meetingByPet.containsKey(pet.id());
    }
    private boolean both(Pet a, Pet b, PetBehavior behavior) { return runtime.behaves(a, behavior) && runtime.behaves(b, behavior); }
    private PetMeetingMood mood(Pet a, Pet b) { return PetMeetingMood.of(a, both(a, b, PetBehavior.PET_FRIENDSHIPS) ? a.friends().trust(b.id()) : 0); }
    double friendship(Pet a, Pet b) { return both(a, b, PetBehavior.PET_FRIENDSHIPS) ? Math.min(a.friends().trust(b.id()), b.friends().trust(a.id())) : 0; }
    private void rememberFriends(Pair pair, Encounter e, long now, double requested) {
        if (!both(e.a, e.b, PetBehavior.PET_FRIENDSHIPS)) return;
        var queue = gains.computeIfAbsent(pair, ignored -> new ArrayDeque<>());
        queue.removeIf(g -> g.at <= now - 60_000);
        double used = queue.stream().mapToDouble(Gain::amount).sum();
        double gain = Math.min(requested, runtime.config().social().maxFriendshipGainPerMinute() - used);
        if (gain <= 0) return;
        e.a.friends().reinforce(e.b.id(), gain, now, 0);
        e.b.friends().reinforce(e.a.id(), gain, now, 0);
        queue.addLast(new Gain(now, gain));
    }
    private Mob body(Pet pet) {
        Entity entity = pet == null ? null : runtime.entity(pet);
        return entity instanceof Mob mob && mob.isValid() && !mob.isDead() ? mob : null;
    }
    private void end(Pair pair, long now, double seconds) {
        Encounter e = active.remove(pair);
        if (e != null) for (Pet pet : List.of(e.a, e.b)) {
            meetingByPet.remove(pet.id(), pair);
            pet.socialTailHz(0);
            Mob body = body(pet);
            if (body != null) { PetMotion.stop(body); PetFx.stopLooking(body); runtime.visual().wagTail(body, 0); }
        }
        nextAllowed.put(pair, now + Math.round(seconds * 1000));
    }
    private static double square(double value) { return value * value; }
    private enum Phase { GREET, SNIFF, OBSERVE, CHASE, BARK }
    private static final class Visit { long seenAt, separatedAt; boolean greeted; }
    private record Gain(long at, double amount) { }
    private final class Encounter {
        final Pet a, b;
        final Phase phase;
        final long startedAt;
        final boolean automatic;
        final PetMeetingMood moodA, moodB;
        final Player ownerA, ownerB;
        final UUID ownerIdA, ownerIdB;
        final Location startA, startB;
        long nextFxAt, nextMoveAt, closeSince, playedSince;
        boolean hoppedA, hoppedB, positive = true, runnerA = true;
        Encounter(Pet a, Pet b, Phase phase, long now, boolean automatic, PetMeetingMood moodA, PetMeetingMood moodB) {
            this.a = a; this.b = b; this.phase = phase; startedAt = now; this.automatic = automatic;
            this.moodA = moodA; this.moodB = moodB;
            ownerA = Bukkit.getPlayer(a.ownerId()); ownerB = Bukkit.getPlayer(b.ownerId());
            ownerIdA = a.ownerId(); ownerIdB = b.ownerId();
            startA = body(a).getLocation(); startB = body(b).getLocation(); nextFxAt = now + 1500;
        }
    }
    private record Pair(UUID first, UUID second) {
        static Pair of(Pet a, Pet b) { return a.id().compareTo(b.id()) <= 0 ? new Pair(a.id(), b.id()) : new Pair(b.id(), a.id()); }
    }
}
