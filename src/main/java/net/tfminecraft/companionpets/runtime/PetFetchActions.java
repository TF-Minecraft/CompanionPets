package net.tfminecraft.companionpets.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import net.tfminecraft.companionpets.behavior.Locomotion;
import net.tfminecraft.companionpets.behavior.WaterEscape;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.item.HandItems;
import net.tfminecraft.companionpets.item.ItemRef;
import net.tfminecraft.companionpets.item.ToyItems;
import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetOrder;
import net.tfminecraft.companionpets.play.FetchJob;
import net.tfminecraft.companionpets.play.FetchPhase;
import net.tfminecraft.companionpets.play.ThrowSpeed;

final class PetFetchActions {
    private final PetRuntime runtime;
    private final PetActions actions;
    private final Map<UUID, FetchJob> throwsInProgress = new HashMap<>();
    private final Map<UUID, Location> lastLocations = new HashMap<>();
    private final Map<UUID, Long> expiresAt = new HashMap<>();

    PetFetchActions(PetRuntime runtime, PetActions actions) {
        this.runtime = runtime;
        this.actions = actions;
    }

    void throwToy(Player player, ItemStack hand) {
        ItemStack thrown = hand.clone();
        thrown.setAmount(1);
        FetchJob job = new FetchJob(ToyItems.encode(thrown), player.getUniqueId());
        if (!HandItems.consume(player, hand)) return;
        Location eye = player.getEyeLocation();
        Vector velocity = eye.getDirection().normalize().multiply(ThrowSpeed.speed(
                player.getLocation().getPitch(), player.isSneaking(),
                runtime.config().play().throwSpeedLow(), runtime.config().play().throwSpeedHigh()));
        Snowball ball;
        try {
            ball = player.getWorld().spawn(eye, Snowball.class, snowball -> {
                snowball.setShooter(player);
                snowball.setItem(thrown.clone());
                snowball.setVelocity(velocity);
                snowball.getPersistentDataContainer().set(runtime.toyKey(), PersistentDataType.STRING, job.id().toString());
            });
        } catch (RuntimeException ex) {
            if (player.getGameMode() != GameMode.CREATIVE) {
                player.getInventory().addItem(thrown).values().forEach(leftover ->
                        player.getWorld().dropItem(player.getLocation(), leftover));
            }
            return;
        }
        register(job, ball);
        for (Pet pet : runtime.store().all()) {
            PetTypeDef type = runtime.config().type(pet.typeId());
            Entity body = runtime.entity(pet);
            if (type == null || !type.acceptsToy(thrown) || !(body instanceof Mob mob)
                    || !body.getWorld().equals(player.getWorld())
                    || body.getLocation().distance(player.getLocation()) > runtime.config().ownerNearRadius()
                    || !canChase(pet)) continue;
            FetchJob previous = pet.fetch();
            if (previous != null && (previous.phase() == FetchPhase.CARRY || runtime.random().nextDouble() >= 0.35)) continue;
            // Leaving a race never returns or removes the toy that other pets chase.
            releaseFetch(pet, false);
            actions.clearInteractions(pet);
            runtime.visual().cancelAction(body);
            ItemRef toy = type.toy(thrown);
            job.favorite(pet.id(), toy != null && toy.key().equals(pet.favoriteToy()));
            pet.fetch(job);
            pet.activity(Activity.PLAYING);
            pet.playUntilMillis(0L);
            navigate(pet, mob);
        }
    }

    void register(FetchJob job, Snowball ball) {
        job.projectileId(ball.getUniqueId());
        throwsInProgress.put(job.id(), job);
        lastLocations.put(job.id(), ball.getLocation().clone());
        expiresAt.put(job.id(), System.currentTimeMillis() + 30_000L);
    }

    boolean canChase(Pet pet) {
        return !pet.stored() && !pet.dead() && pet.illness() != Illness.SICK && pet.illness() != Illness.WEAKENED
                && pet.need(Need.HEALTH) > 0 && pet.need(Need.ENERGY) >= 25 && pet.need(Need.HUNGER) >= 25
                && !training(pet)
                && pet.activity() != Activity.SLEEPING && pet.order() == PetOrder.FOLLOW && !pet.staying()
                && System.currentTimeMillis() >= pet.forcedSitUntilMillis();
    }

    void navigate(Pet pet, Mob mob) {
        // Release the physical pose before the native goal selector runs. Otherwise
        // a sitting goal can prevent the fetch callback that would make it stand.
        actions.markSleep(mob, false);
        PetFx.sit(mob, false);
        PetFx.lie(mob, false);
        mob.setAware(true);
        FetchNavigationGoal.ensure(runtime, pet, mob, () -> step(pet, mob));
        step(pet, mob);
    }

    void step(Pet pet, Mob mob) {
        if (WaterEscape.needed(mob)) return;
        FetchJob job = pet.fetch();
        if (job == null) {
            return;
        }
        Player owner = Bukkit.getPlayer(job.throwerId());
        if (owner == null || !owner.isOnline()) {
            actions.releaseFetch(pet, null, false);
            return;
        }
        if (!canChase(pet)) {
            actions.releaseFetch(pet, owner, true);
            return;
        }
        boolean favorite = job.favorite(pet.id());
        double speed = Locomotion.speed(pet.illness(), pet.bond(), pet.need(Need.CLEANLINESS), favorite);
        PetFx.sit(mob, false);
        PetFx.lie(mob, false);
        if (job.phase() == FetchPhase.AIR) {
            Entity projectile = job.projectileId() == null ? null : Bukkit.getEntity(job.projectileId());
            if (projectile == null) return;
            if (!mob.getWorld().equals(projectile.getWorld())) { actions.releaseFetch(pet, owner, true); return; }
            mob.getPathfinder().moveTo(projectile.getLocation(), speed);
            return;
        }
        if (job.phase() == FetchPhase.GROUND) {
            Entity item = job.itemId() == null ? null : Bukkit.getEntity(job.itemId());
            if (item == null) {
                actions.releaseFetch(pet, owner, true);
                return;
            }
            if (!mob.getWorld().equals(item.getWorld())) { actions.releaseFetch(pet, owner, true); return; }
            if (claim(pet)) {
                mob.getPathfinder().stopPathfinding();
            } else {
                mob.getPathfinder().moveTo(item.getLocation(), speed);
            }
            return;
        }
        if (!mob.getWorld().equals(owner.getWorld())) {
            actions.releaseFetch(pet, owner, false);
            return;
        }
        if (mob.getLocation().distance(owner.getLocation()) < 2.2) {
            returned(pet, owner);
            double mood = runtime.config().care().playMoodGain() * (favorite ? runtime.config().care().favoriteMoodMultiplier() : 1.0);
            pet.need(Need.MOOD, pet.need(Need.MOOD) + mood);
            pet.need(Need.ENERGY, pet.need(Need.ENERGY) - runtime.config().care().playEnergyCost());
            PetFx.hearts(mob, favorite ? 6 : 3);
            mob.getPathfinder().stopPathfinding();
        } else {
            mob.getPathfinder().moveTo(owner.getLocation(), speed);
        }
    }

    private boolean training(Pet pet) {
        var session = runtime.sessions().training(pet.ownerId());
        return session != null && session.petId().equals(pet.id());
    }

    void toyLanded(Snowball ball) {
        String tag = ball.getPersistentDataContainer().get(runtime.toyKey(), PersistentDataType.STRING);
        FetchJob job;
        try { job = throwsInProgress.get(UUID.fromString(tag)); }
        catch (IllegalArgumentException | NullPointerException ex) { ball.remove(); return; }
        Location at = ball.getLocation().clone();
        ball.remove();
        if (job == null || !ball.getUniqueId().equals(job.projectileId())) return;
        job.projectileId(null);
        lastLocations.put(job.id(), at);
        if (chasers(job).isEmpty()) {
            finish(job, at);
            return;
        }
        ItemStack stack = restoreToy(job.toy());
        if (stack == null) { forget(job); return; }
        Item item = at.getWorld().dropItem(at, stack);
        item.setPickupDelay(Integer.MAX_VALUE);
        item.getPersistentDataContainer().set(runtime.toyKey(), PersistentDataType.STRING, job.id().toString());
        job.itemId(item.getUniqueId());
        job.phase(FetchPhase.GROUND);
        job.missingSince(0);
        expiresAt.put(job.id(), System.currentTimeMillis() + 60_000L);
    }

    boolean claim(Pet pet) {
        FetchJob job = pet.fetch();
        Entity item = job == null || job.itemId() == null ? null : Bukkit.getEntity(job.itemId());
        Entity body = runtime.entity(pet);
        if (item == null || body == null || !canChase(pet) || !body.getWorld().equals(item.getWorld())
                || body.getLocation().distance(item.getLocation()) >= 1.7 || !job.claim(pet.id())) return false;
        item.remove();
        job.itemId(null);
        for (Pet other : chasers(job)) if (other != pet) detach(other);
        return true;
    }

    void releaseFetch(Pet pet, boolean toOwner) {
        FetchJob job = pet.fetch();
        if (job == null) return;
        detach(pet);
        if (pet.id().equals(job.carrierId())) {
            Player thrower = Bukkit.getPlayer(job.throwerId());
            if (thrower != null && thrower.isOnline() && toOwner) {
                finish(job, PetRuntime.inFront(thrower));
            } else if (job.throwerId().equals(pet.ownerId())) {
                forget(job);
                pet.carriedToy(job.toy());
            } else {
                Entity body = runtime.entity(pet);
                finish(job, body == null ? lastLocations.get(job.id()) : body.getLocation());
            }
        } else if (job.phase() == FetchPhase.GROUND && chasers(job).isEmpty()) {
            unprotect(job);
        }
    }

    void returned(Pet pet, Player thrower) {
        FetchJob job = pet.fetch();
        if (job != null && pet.id().equals(job.carrierId())) finish(job, PetRuntime.inFront(thrower));
    }

    void tick(long now) {
        for (FetchJob job : List.copyOf(throwsInProgress.values())) {
            for (Pet pet : chasers(job)) {
                if (runtime.entity(pet) == null || !canChase(pet)) releaseFetch(pet, false);
            }
            if (!throwsInProgress.containsKey(job.id())) continue;
            Entity target = job.phase() == FetchPhase.AIR ? entity(job.projectileId()) : entity(job.itemId());
            if (job.phase() == FetchPhase.CARRY) {
                Pet carryingPet = runtime.store().get(job.carrierId());
                Entity carrier = runtime.entity(carryingPet);
                if (carrier != null) lastLocations.put(job.id(), carrier.getLocation().clone());
                else finish(job, lastLocations.get(job.id()));
                continue;
            }
            if (target != null) {
                lastLocations.put(job.id(), target.getLocation().clone());
                job.missingSince(0);
            } else if (job.missingSince() == 0) job.missingSince(now);
            if (job.phase() == FetchPhase.GROUND) {
                if (target == null) forget(job);
                else if (chasers(job).isEmpty() || now >= expiresAt.get(job.id())) unprotect(job);
            } else if (now >= expiresAt.get(job.id()) || (job.missingSince() != 0 && now - job.missingSince() > 1_000L)) {
                finish(job, lastLocations.get(job.id()));
            }
        }
    }

    void stashLooseToys() {
        for (FetchJob job : List.copyOf(throwsInProgress.values())) {
            Pet carrier = job.carrierId() == null ? null : runtime.store().get(job.carrierId());
            if (carrier != null && carrier.ownerId().equals(job.throwerId())) {
                carrier.carriedToy(job.toy());
                forget(job);
            } else if (job.phase() == FetchPhase.GROUND) unprotect(job);
            else finish(job, lastLocations.get(job.id()));
        }
    }

    private List<Pet> chasers(FetchJob job) {
        return runtime.store().all().stream().filter(pet -> pet.fetch() == job).toList();
    }

    private void detach(Pet pet) {
        pet.fetch(null);
        if (pet.activity() == Activity.PLAYING) pet.activity(Activity.NONE);
        if (runtime.entity(pet) instanceof Mob mob) mob.getPathfinder().stopPathfinding();
    }

    private void unprotect(FetchJob job) {
        if (entity(job.itemId()) instanceof Item item) {
            item.getPersistentDataContainer().remove(runtime.toyKey());
            item.setPickupDelay(0);
        }
        job.itemId(null);
        forget(job);
    }

    private void finish(FetchJob job, Location at) {
        forget(job);
        if (at != null) dropPlain(at, job.toy());
    }

    private void forget(FetchJob job) {
        Entity projectile = entity(job.projectileId());
        Entity item = entity(job.itemId());
        if (projectile != null) projectile.remove();
        if (item != null) item.remove();
        for (Pet pet : chasers(job)) detach(pet);
        throwsInProgress.remove(job.id());
        lastLocations.remove(job.id());
        expiresAt.remove(job.id());
    }

    private Entity entity(UUID id) { return id == null ? null : Bukkit.getEntity(id); }

    void dropPlain(Location location, String toy) {
        if (location.getWorld() == null || toy == null) return;
        ItemStack stack = restoreToy(toy);
        if (stack != null) location.getWorld().dropItem(location, stack).setPickupDelay(0);
    }

    private ItemStack restoreToy(String saved) {
        try { return ToyItems.decode(saved); }
        catch (RuntimeException ex) {
            runtime.plugin().getLogger().warning("Could not restore saved toy: " + ex.getMessage());
            return null;
        }
    }
}
