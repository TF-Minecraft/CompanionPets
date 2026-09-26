package net.tfminecraft.companionpets.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import net.tfminecraft.companionpets.behavior.Locomotion;
import net.tfminecraft.companionpets.config.SocialSettings;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetPersonality;

/** Brief, harmless encounters. Only two territorial pets may bark at one another. */
final class PetSocial {
    private final PetRuntime runtime;
    private final Map<Pair, Encounter> active = new HashMap<>();
    private final Map<Pair, Long> nextAllowed = new HashMap<>();

    PetSocial(PetRuntime runtime) { this.runtime = runtime; }

    void tick(long now) {
        SocialSettings settings = runtime.config().social();
        if (!settings.enabled()) { clear(); return; }
        nextAllowed.entrySet().removeIf(entry -> entry.getValue() < now - 300_000L);
        for (Map.Entry<Pair, Encounter> entry : List.copyOf(active.entrySet())) {
            if (active.get(entry.getKey()) == entry.getValue()) advance(entry.getKey(), entry.getValue(), now);
        }
        for (Pet pet : runtime.store().all()) {
            if (!available(pet) || engaged(pet)) continue;
            Mob body = body(pet);
            if (body == null || body.getTarget() != null) continue;
            for (Entity entity : body.getNearbyEntities(settings.encounterRadius(), 3, settings.encounterRadius())) {
                Pet other = runtime.byEntity(entity);
                if (other == null || pet.id().compareTo(other.id()) >= 0 || !available(other) || engaged(other)
                        || !(entity instanceof Mob otherBody) || otherBody.getTarget() != null) continue;
                double distance = body.getLocation().distanceSquared(otherBody.getLocation());
                if (distance > square(settings.encounterRadius())
                        || (pet.personality() == PetPersonality.SHY || other.personality() == PetPersonality.SHY)
                                && distance > square(Math.min(4, settings.encounterRadius()))) continue;
                Pair pair = Pair.of(pet, other);
                if (now < nextAllowed.getOrDefault(pair, 0L) || !ownersNearby(pet, body, other, otherBody)) continue;
                if (begin(pair, pet, other, body, otherBody, now, null)) break;
            }
        }
    }

    boolean calm(Player player, Pet pet) {
        if (!pet.ownerId().equals(player.getUniqueId()) && !player.hasPermission("companionpets.test")) return false;
        for (Map.Entry<Pair, Encounter> entry : List.copyOf(active.entrySet())) {
            Encounter encounter = entry.getValue();
            if (encounter.phase != Phase.BARK || encounter.a != pet && encounter.b != pet) continue;
            Mob a = body(encounter.a);
            Mob b = body(encounter.b);
            if (a != null) PetFx.hearts(a, 2);
            if (b != null) PetFx.hearts(b, 2);
            end(entry.getKey(), System.currentTimeMillis(), runtime.config().social().calmCooldownSeconds());
            tellOwners(encounter, encounter.a.name() + " and " + encounter.b.name() + " settle down");
            return true;
        }
        return false;
    }

    String status(Pet pet) {
        for (Encounter encounter : active.values()) {
            if ((encounter.a == pet || encounter.b == pet) && encounter.phase == Phase.BARK) {
                return "Barking - right-click repeatedly to calm";
            }
        }
        return null;
    }

    void cancel(Pet pet) {
        for (Pair pair : List.copyOf(active.keySet())) {
            Encounter encounter = active.get(pair);
            if (encounter != null && (encounter.a == pet || encounter.b == pet)) {
                end(pair, System.currentTimeMillis(), runtime.config().social().encounterCooldownSeconds());
            }
        }
    }

    void clear() { active.clear(); nextAllowed.clear(); }

    boolean trigger(Player owner, Pet pet, String kind) {
        Mob body = body(pet);
        if (body == null || !pet.ownerId().equals(owner.getUniqueId())) return false;
        SocialSettings settings = runtime.config().social();
        Pet other = null;
        Mob otherBody = null;
        double best = square(settings.encounterRadius());
        for (Entity candidate : body.getNearbyEntities(settings.encounterRadius(), 3, settings.encounterRadius())) {
            Pet found = runtime.byEntity(candidate);
            if (found == null || found == pet || !available(found) || !(candidate instanceof Mob mob)) continue;
            double distance = body.getLocation().distanceSquared(mob.getLocation());
            if (distance < best) { other = found; otherBody = mob; best = distance; }
        }
        if (other == null || !ownersNearby(pet, body, other, otherBody)) return false;
        Pair pair = Pair.of(pet, other);
        if ("bark".equals(kind) && !(pet.personality() == PetPersonality.TERRITORIAL
                && other.personality() == PetPersonality.TERRITORIAL)) return false;
        cancel(pet);
        cancel(other);
        nextAllowed.remove(pair);
        return begin(pair, pet, other, body, otherBody, System.currentTimeMillis(), kind);
    }

    private boolean begin(Pair pair, Pet a, Pet b, Mob bodyA, Mob bodyB, long now, String forced) {
        SocialSettings settings = runtime.config().social();
        boolean territorial = a.personality() == PetPersonality.TERRITORIAL
                && b.personality() == PetPersonality.TERRITORIAL;
        String choice = forced;
        if (choice == null) {
            double barkChance = a.ownerId().equals(b.ownerId())
                    ? settings.sameOwnerBarkChance() : settings.territorialBarkChance();
            if (territorial && bodyA.getLocation().distanceSquared(bodyB.getLocation()) <= square(settings.barkRadius())
                    && runtime.random().nextDouble() * 100 < barkChance) choice = "bark";
            else {
                double chase = settings.chaseChance() * (a.personality() == PetPersonality.PLAYFUL
                        || b.personality() == PetPersonality.PLAYFUL ? 1.25 : 0.65);
                if (a.personality() == PetPersonality.SHY || b.personality() == PetPersonality.SHY) chase *= 0.4;
                choice = runtime.random().nextDouble() * 100 < chase ? "chase" : "sniff";
            }
        }
        if ("bark".equals(choice) && (!territorial
                || bodyA.getLocation().distanceSquared(bodyB.getLocation()) > square(settings.barkRadius()))) return false;
        Phase phase = "bark".equals(choice) ? Phase.BARK : "chase".equals(choice) ? Phase.CHASE : Phase.SNIFF;
        Encounter encounter = new Encounter(a, b, phase, now);
        active.put(pair, encounter);
        if (phase == Phase.BARK) bark(encounter, bodyA, bodyB);
        else tellOwners(encounter, a.name() + " and " + b.name()
                + (phase == Phase.CHASE ? " start playing chase" : " approach to sniff each other"));
        return true;
    }

    private void advance(Pair pair, Encounter encounter, long now) {
        SocialSettings settings = runtime.config().social();
        Mob a = body(encounter.a);
        Mob b = body(encounter.b);
        if (a == null || b == null || a.getTarget() != null || b.getTarget() != null
                || !a.getWorld().equals(b.getWorld()) || !available(encounter.a)
                || !available(encounter.b) || !ownersNearby(encounter.a, a, encounter.b, b)) {
            end(pair, now, settings.encounterCooldownSeconds()); return;
        }
        double distance = a.getLocation().distanceSquared(b.getLocation());
        if (distance > square(settings.encounterRadius())) {
            end(pair, now, settings.encounterCooldownSeconds()); return;
        }
        long age = now - encounter.startedAt;
        if (encounter.phase == Phase.SNIFF) {
            if (distance > 2.25) {
                a.getPathfinder().moveTo(b.getLocation(), 1.0);
                b.getPathfinder().moveTo(a.getLocation(), 1.0);
            } else {
                a.getPathfinder().stopPathfinding();
                b.getPathfinder().stopPathfinding();
                PetFx.look(a, b.getEyeLocation());
                PetFx.look(b, a.getEyeLocation());
                if (now >= encounter.nextFxAt) {
                    PetFx.particle(a, Particle.HAPPY_VILLAGER, 2);
                    PetFx.particle(b, Particle.HAPPY_VILLAGER, 2);
                    encounter.nextFxAt = now + 1_500L;
                }
            }
            if (age >= 5_000L) end(pair, now, settings.encounterCooldownSeconds());
        } else if (encounter.phase == Phase.CHASE) {
            double angle = now / 650.0;
            a.getPathfinder().moveTo(b.getLocation().clone().add(Math.cos(angle) * 1.5, 0, Math.sin(angle) * 1.5), 1.15);
            b.getPathfinder().moveTo(a.getLocation().clone().add(Math.cos(angle + Math.PI) * 1.5, 0,
                    Math.sin(angle + Math.PI) * 1.5), 1.1);
            if (now >= encounter.nextFxAt) {
                PetFx.particle(a, Particle.HAPPY_VILLAGER, 2);
                PetFx.particle(b, Particle.HAPPY_VILLAGER, 2);
                encounter.nextFxAt = now + 2_000L;
            }
            if (age >= 6_000L) end(pair, now, settings.encounterCooldownSeconds());
        } else {
            if (distance > square(settings.barkRadius()) || age >= settings.barkSeconds() * 1000.0) {
                end(pair, now, settings.encounterCooldownSeconds()); return;
            }
            if (now >= encounter.nextFxAt) {
                bark(encounter, a, b);
                encounter.nextFxAt = now + 1_500L;
            }
        }
    }

    private static void bark(Encounter encounter, Mob a, Mob b) {
        a.getPathfinder().stopPathfinding();
        b.getPathfinder().stopPathfinding();
        PetFx.look(a, b.getEyeLocation());
        PetFx.look(b, a.getEyeLocation());
        growl(a);
        growl(b);
        PetFx.particle(a, Particle.ANGRY_VILLAGER, 2);
        PetFx.particle(b, Particle.ANGRY_VILLAGER, 2);
        tellOwners(encounter, encounter.a.name() + " and " + encounter.b.name()
                + " bark at each other. Right-click your pet repeatedly to calm them");
    }

    private static void growl(Mob body) {
        Sound sound = switch (body.getType()) {
            case WOLF -> Sound.ENTITY_WOLF_GROWL;
            case CAT -> Sound.ENTITY_CAT_HISS;
            case FOX -> Sound.ENTITY_FOX_AGGRO;
            default -> PetFx.ambientSound(body.getType());
        };
        body.getWorld().playSound(body.getLocation(), sound, 0.75f, 0.9f);
    }

    private boolean ownersNearby(Pet a, Mob bodyA, Pet b, Mob bodyB) {
        double radius = square(runtime.config().social().ownerRadius());
        Player ownerA = Bukkit.getPlayer(a.ownerId());
        Player ownerB = Bukkit.getPlayer(b.ownerId());
        boolean aNear = near(ownerA, bodyA, radius);
        boolean bNear = near(ownerB, bodyB, radius);
        return aNear && bNear || aNear && ownerB == null && near(ownerA, bodyB, radius)
                || bNear && ownerA == null && near(ownerB, bodyA, radius);
    }

    private static boolean near(Player player, Mob body, double radiusSquared) {
        return player != null && player.isOnline() && player.getWorld().equals(body.getWorld())
                && player.getLocation().distanceSquared(body.getLocation()) <= radiusSquared;
    }

    private static boolean available(Pet pet) {
        return pet != null && !pet.stored() && !pet.dead() && pet.activity() == Activity.NONE && pet.fetch() == null
                && Locomotion.choose(pet.illness(), pet.need(Need.HEALTH), pet.need(Need.ENERGY),
                        pet.need(Need.HUNGER), pet.activity(), false,
                        System.currentTimeMillis() < pet.forcedSitUntilMillis(), pet.order(), pet.staying())
                        == Locomotion.Mode.FOLLOW;
    }

    boolean engaged(Pet pet) {
        for (Encounter encounter : active.values()) if (encounter.a == pet || encounter.b == pet) return true;
        return false;
    }

    private Mob body(Pet pet) {
        Entity entity = runtime.entity(pet);
        return entity instanceof Mob mob ? mob : null;
    }

    private void end(Pair pair, long now, double cooldownSeconds) {
        Encounter encounter = active.remove(pair);
        if (encounter != null) {
            Mob a = body(encounter.a);
            Mob b = body(encounter.b);
            if (a != null) a.getPathfinder().stopPathfinding();
            if (b != null) b.getPathfinder().stopPathfinding();
        }
        nextAllowed.put(pair, now + Math.round(cooldownSeconds * 1000.0));
    }

    private static void tellOwners(Encounter encounter, String message) {
        Player first = Bukkit.getPlayer(encounter.a.ownerId());
        Player second = Bukkit.getPlayer(encounter.b.ownerId());
        if (first != null && first.isOnline()) PetFx.bar(first, message);
        if (second != null && second != first && second.isOnline()) PetFx.bar(second, message);
    }

    private static double square(double value) { return value * value; }

    private enum Phase { SNIFF, CHASE, BARK }

    private static final class Encounter {
        private final Pet a;
        private final Pet b;
        private final Phase phase;
        private final long startedAt;
        private long nextFxAt;
        private Encounter(Pet a, Pet b, Phase phase, long startedAt) {
            this.a = a; this.b = b; this.phase = phase; this.startedAt = startedAt;
            this.nextFxAt = startedAt + 1_500L;
        }
    }

    private record Pair(UUID first, UUID second) {
        private static Pair of(Pet a, Pet b) {
            return a.id().compareTo(b.id()) <= 0 ? new Pair(a.id(), b.id()) : new Pair(b.id(), a.id());
        }
    }
}
