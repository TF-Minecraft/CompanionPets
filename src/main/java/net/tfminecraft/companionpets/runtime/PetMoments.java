package net.tfminecraft.companionpets.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.companionpets.behavior.Locomotion;
import net.tfminecraft.companionpets.config.MomentSettings;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetOrder;
import net.tfminecraft.companionpets.pet.PetPersonality;
import net.tfminecraft.companionpets.session.TrainingSession;

/** Occasional interactions with no effect on a pet's needs or orders. */
final class PetMoments {
    private final PetRuntime runtime;
    private final Map<Pet, Long> nextAt = new WeakHashMap<>();
    private final Map<Pet, GiftJob> gifts = new WeakHashMap<>();
    private final Map<Pet, Long> nextBellyAt = new WeakHashMap<>();

    private boolean bellyReady(Pet pet, Mob body, Player owner) {
        return runtime.config().moments().enabled() && runtime.config().belly().enabled()
                && pet.illness() == Illness.NONE && net.tfminecraft.companionpets.care.DominantNeed.select(pet) == null
                && pet.need(Need.HUNGER) >= 60 && pet.need(Need.ENERGY) >= 40 && pet.need(Need.HEALTH) >= 70
                && pet.need(Need.MOOD) >= runtime.config().belly().minMood()
                && pet.activity() == Activity.NONE && pet.fetch() == null && !gifts.containsKey(pet)
                && body.isValid() && body.isOnGround() && !body.isInWater() && body.getTarget() == null
                && owner != null && owner.isOnline() && owner.getUniqueId().equals(pet.ownerId())
                && owner.getWorld().equals(body.getWorld()) && owner.getLocation().distanceSquared(body.getLocation()) <= 36
                && !runtime.sessions().resting(pet.id(), System.currentTimeMillis())
                && !(runtime.sessions().training(owner.getUniqueId()) instanceof TrainingSession s && s.petId().equals(pet.id()));
    }

    boolean petBelly(Pet pet, Mob body, Player owner, long now) {
        if (!bellyReady(pet, body, owner)) return false;
        if (runtime.visual().belly(body)) {
            if (runtime.visual().rubBelly(body)) {
                PetFx.hearts(body, 2);
                PetFx.bar(owner, "You scratch " + pet.name() + "'s belly");
            }
            return true;
        }
        if (now < nextBellyAt.getOrDefault(pet, 0L) || runtime.visual().holdsMovement(body)) return false;
        // Rate limit attempts as well as successes, so rapid clicks cannot force a moment.
        nextBellyAt.put(pet, now + Math.round(runtime.config().belly().cooldownSeconds() * 1000));
        if (runtime.random().nextDouble() * 100 >= runtime.config().belly().chance()) return false;
        return startBelly(pet, body, owner);
    }

    boolean triggerBelly(Pet pet, Mob body, Player owner) {
        if (!bellyReady(pet, body, owner)) return false;
        if (runtime.visual().belly(body)) return runtime.visual().rubBelly(body);
        return startBelly(pet, body, owner);
    }

    private boolean startBelly(Pet pet, Mob body, Player owner) {
        if (!runtime.visual().startBelly(body, runtime.config().type(pet.typeId()), Math.round(runtime.config().belly().idleSeconds() * 1000))) return false;
        PetFx.sit(body, false);
        body.getPathfinder().stopPathfinding();
        PetFx.bar(owner, pet.name() + " rolls onto its back. Right-click to scratch its belly");
        return true;
    }

    boolean tickBelly(Pet pet, Mob body, Player owner) {
        if (!runtime.visual().belly(body)) return false;
        if (!bellyReady(pet, body, owner)) {
            runtime.visual().cancelAction(body);
            return false;
        }
        body.getPathfinder().stopPathfinding();
        return true;
    }

    PetMoments(PetRuntime runtime) {
        this.runtime = runtime;
    }

    void tick(Pet pet, Mob body, Player owner, Locomotion.Mode mode, long now) {
        MomentSettings settings = runtime.config().moments();
        if (gifts.containsKey(pet)) {
            if (mode == Locomotion.Mode.FOLLOW && pet.activity() == Activity.NONE) {
                advanceGift(pet, body, owner, now);
            } else {
                cancel(pet);
            }
            return;
        }
        if (!settings.enabled()) {
            return;
        }
        boolean young = pet.bornAt() > 0L && now - pet.bornAt() < settings.juvenileHours() * 3_600_000.0;
        Long due = nextAt.get(pet);
        if (due == null) {
            schedule(pet, now, settings.initialDelayMinSeconds(), settings.initialDelayMaxSeconds());
            return;
        }
        if (now < due) {
            return;
        }
        if (owner == null || !owner.isOnline() || !body.getWorld().equals(owner.getWorld())
                || body.getLocation().distanceSquared(owner.getLocation()) > settings.ownerRadius() * settings.ownerRadius()
                || mode != Locomotion.Mode.FOLLOW || pet.activity() != Activity.NONE || pet.fetch() != null
                || runtime.sessions().training(owner.getUniqueId()) instanceof TrainingSession session
                        && session.petId().equals(pet.id())) {
            nextAt.put(pet, now + Math.round(settings.inactiveRetrySeconds() * 1000.0));
            return;
        }
        schedule(pet, now,
                young ? settings.juvenileIntervalMinSeconds() : settings.adultIntervalMinSeconds(),
                young ? settings.juvenileIntervalMaxSeconds() : settings.adultIntervalMaxSeconds());
        if (pet.illness() != Illness.NONE) {
            askForAffection(pet, body, owner);
            return;
        }
        if (pet.need(Need.MOOD) < settings.lowMoodThreshold()) {
            if (runtime.random().nextDouble() * 100.0 >= settings.lowMoodBarkChance() || !reactToStranger(pet, body, owner)) {
                askForAffection(pet, body, owner);
            }
            return;
        }
        double choice = runtime.random().nextDouble() * 100.0;
        double mischiefChance = (young ? settings.juvenileMischiefChance() : settings.adultMischiefChance())
                * switch (pet.personality()) {
                    case PLAYFUL -> 1.25;
                    case SHY -> 0.5;
                    case FRIENDLY -> 0.65;
                    case GRUMPY -> 0.8;
                    default -> 1.0;
                };
        double barkChance = (young ? settings.juvenileBarkChance() : settings.adultBarkChance())
                * switch (pet.personality()) {
                    case GRUMPY -> 1.6;
                    case TERRITORIAL -> 1.25;
                    case SHY -> 0.45;
                    case FRIENDLY -> 0.65;
                    default -> 1.0;
                };
        double digChance = (young ? settings.juvenileDigChance() : settings.adultDigChance())
                * switch (pet.personality()) {
                    case PLAYFUL -> 1.4;
                    case SHY -> 0.6;
                    case FRIENDLY, TERRITORIAL -> 0.8;
                    case GRUMPY -> 0.6;
                };
        if (choice < mischiefChance && pet.need(Need.ENERGY) >= settings.mischiefMinEnergy()
                && misbehave(pet, body, owner)) {
            return;
        }
        if (choice < mischiefChance + barkChance && reactToStranger(pet, body, owner)) {
            return;
        }
        if (choice < mischiefChance + barkChance + digChance && pet.need(Need.ENERGY) >= settings.digMinEnergy()
                && startGift(pet, body, owner, now)) {
            return;
        }
        askForAffection(pet, body, owner);
    }

    private void schedule(Pet pet, long now, double minSeconds, double maxSeconds) {
        double low = Math.min(minSeconds, maxSeconds);
        double high = Math.max(minSeconds, maxSeconds);
        double delay = low + runtime.random().nextDouble() * (high - low);
        nextAt.put(pet, now + Math.round(delay * 1000.0));
    }

    private void askForAffection(Pet pet, Mob body, Player owner) {
        PetFx.look(body, owner.getEyeLocation());
        PetFx.sad(body);
        MomentSettings settings = runtime.config().moments();
        PetFx.particle(body, settings.affectionParticle(), settings.affectionParticleCount());
        PetFx.bar(owner, pet.name() + " whimpers and looks at you, asking for a little attention");
    }

    boolean triggerAffection(Pet pet, Mob body, Player owner) {
        askForAffection(pet, body, owner);
        return true;
    }

    boolean triggerBark(Pet pet, Mob body, Player owner) {
        List<LivingEntity> nearby = nearbyLiving(body, owner);
        LivingEntity target = nearby.isEmpty() ? owner : nearby.get(runtime.random().nextInt(nearby.size()));
        barkAt(pet, body, owner, target);
        return true;
    }

    boolean triggerMischief(Pet pet, Mob body, Player owner) {
        return misbehave(pet, body, owner);
    }

    boolean triggerDig(Pet pet, Mob body, Player owner) {
        return startGift(pet, body, owner, System.currentTimeMillis());
    }

    void cancel(Pet pet) {
        runtime.visual().cancelAction(runtime.entity(pet));
        GiftJob gift = gifts.remove(pet);
        if (gift != null && gift.display != null && gift.display.isValid()) {
            gift.display.remove();
        }
        nextAt.remove(pet);
    }

    void clear() {
        for (Pet pet : List.copyOf(gifts.keySet())) {
            cancel(pet);
        }
        nextAt.clear();
        nextBellyAt.clear();
    }

    private boolean startGift(Pet pet, Mob body, Player owner, long now) {
        MomentSettings settings = runtime.config().moments();
        if (!settings.diggingEnabled() || gifts.containsKey(pet) || pet.fetch() != null
                || pet.carriedToy() != null || owner == null || !owner.isOnline()) {
            return false;
        }
        int total = settings.digLoot().values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) {
            return false;
        }
        int roll = runtime.random().nextInt(total);
        Material found = Material.STICK;
        for (Map.Entry<Material, Integer> entry : settings.digLoot().entrySet()) {
            roll -= entry.getValue();
            if (roll < 0) {
                found = entry.getKey();
                break;
            }
        }
        gifts.put(pet, new GiftJob(found, now + 2_000L, now + 5_000L, now + 25_000L));
        PetFx.look(body, body.getLocation().add(0, -0.5, 0));
        PetFx.particle(body, Particle.DUST_PLUME, 8);
        body.getWorld().playSound(body.getLocation(), Sound.BLOCK_GRAVEL_BREAK, 0.7f, 1.0f);
        PetFx.bar(owner, pet.name() + " starts digging for something");
        return true;
    }

    private void advanceGift(Pet pet, Mob body, Player owner, long now) {
        GiftJob gift = gifts.get(pet);
        if (gift == null) {
            return;
        }
        if (!body.isValid() || pet.order() != PetOrder.FOLLOW || pet.staying()
                || owner == null || !owner.isOnline() || !owner.getWorld().equals(body.getWorld())
                || now >= gift.expiresAt) {
            cancel(pet);
            return;
        }
        if (now >= gift.readyAt && !gift.shown) {
            gift.shown = true;
            Item display = body.getWorld().dropItem(body.getLocation(), new ItemStack(gift.material));
            display.setPickupDelay(Integer.MAX_VALUE);
            display.setGravity(false);
            display.setPersistent(false);
            if (body.addPassenger(display)) {
                gift.display = display;
            } else {
                display.remove();
            }
        }
        if (now < gift.deliverAt) {
            return;
        }
        if (body.getLocation().distanceSquared(owner.getLocation()) > 9.0) {
            body.getPathfinder().moveTo(owner.getLocation(), 1.1);
            return;
        }
        if (gift.display != null && gift.display.isValid()) {
            gift.display.remove();
        }
        body.getWorld().dropItem(PetRuntime.inFront(owner), new ItemStack(gift.material));
        PetFx.particle(body, Particle.HAPPY_VILLAGER, 5);
        PetFx.bar(owner, pet.name() + " brought you a " + gift.material.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
        gifts.remove(pet);
    }

    private static final class GiftJob {
        private final Material material;
        private final long readyAt;
        private final long deliverAt;
        private final long expiresAt;
        private boolean shown;
        private Item display;

        private GiftJob(Material material, long readyAt, long deliverAt, long expiresAt) {
            this.material = material;
            this.readyAt = readyAt;
            this.deliverAt = deliverAt;
            this.expiresAt = expiresAt;
        }
    }

    private boolean reactToStranger(Pet pet, Mob body, Player owner) {
        List<LivingEntity> nearby = nearbyLiving(body, owner);
        if (nearby.isEmpty()) {
            return false;
        }
        LivingEntity target = nearby.get(runtime.random().nextInt(nearby.size()));
        barkAt(pet, body, owner, target);
        return true;
    }

    private List<LivingEntity> nearbyLiving(Mob body, Player owner) {
        List<LivingEntity> nearby = new ArrayList<>();
        double radius = runtime.config().moments().targetRadius();
        for (Entity entity : body.getNearbyEntities(radius, Math.max(2.0, radius / 2.0), radius)) {
            if (entity instanceof LivingEntity living && entity != owner && body.hasLineOfSight(entity)) {
                nearby.add(living);
            }
        }
        return nearby;
    }

    private void barkAt(Pet pet, Mob body, Player owner, LivingEntity target) {
        runtime.visual().play(body, runtime.config().type(pet.typeId()), "SPEAK");
        PetFx.look(body, target.getEyeLocation());
        Sound sound = switch (body.getType()) {
            case WOLF -> Sound.ENTITY_WOLF_GROWL;
            case CAT -> Sound.ENTITY_CAT_HISS;
            case FOX -> Sound.ENTITY_FOX_AGGRO;
            default -> PetFx.ambientSound(body.getType());
        };
        body.getWorld().playSound(body.getLocation(), sound, 0.8f, 1.0f);
        if (body.getType() == org.bukkit.entity.EntityType.WOLF) {
            body.getWorld().playSound(body.getLocation(), Sound.ENTITY_WOLF_AMBIENT, 0.7f, 1.1f);
        }
        MomentSettings settings = runtime.config().moments();
        PetFx.particle(body, settings.barkParticle(), settings.barkParticleCount());
        PetFx.bar(owner, pet.name() + " suddenly protests at something nearby, then settles down");
    }

    private boolean misbehave(Pet pet, Mob body, Player owner) {
        MomentSettings settings = runtime.config().moments();
        if (!settings.plantBreakingEnabled()
                || settings.respectMobGriefing() && !Boolean.TRUE.equals(body.getWorld().getGameRuleValue(GameRule.MOB_GRIEFING))) {
            return false;
        }
        Block feet = body.getLocation().getBlock();
        List<Block> plants = new ArrayList<>();
        int radius = (int) Math.ceil(settings.plantSearchRadius());
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                for (int y = -1; y <= 1; y++) {
                    Block block = feet.getRelative(x, y, z);
                    if (settings.plants().contains(block.getType())) {
                        plants.add(block);
                    }
                }
            }
        }
        if (plants.isEmpty()) {
            return false;
        }
        Block block = plants.get(runtime.random().nextInt(plants.size()));
        EntityChangeBlockEvent change = new EntityChangeBlockEvent(body, block, Material.AIR.createBlockData());
        Bukkit.getPluginManager().callEvent(change);
        if (change.isCancelled() || !block.breakNaturally()) {
            return false;
        }
        PetFx.look(body, block.getLocation().add(0.5, 0.5, 0.5));
        PetFx.particle(body, settings.mischiefParticle(), settings.mischiefParticleCount());
        PetFx.bar(owner, pet.name() + " got mischievous and pulled up a plant");
        return true;
    }
}
