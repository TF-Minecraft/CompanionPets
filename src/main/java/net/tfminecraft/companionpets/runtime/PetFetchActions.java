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
import org.bukkit.entity.Wolf;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import net.tfminecraft.companionpets.behavior.Locomotion;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.item.HandItems;
import net.tfminecraft.companionpets.item.ItemRef;
import net.tfminecraft.companionpets.item.HeldItem;
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
    private final Map<UUID, Boolean> crouchedCats = new HashMap<>();
    private final Map<UUID, Route> routes = new HashMap<>();
    private final Map<UUID, FetchJob> throwsInProgress = new HashMap<>();
    private final Map<UUID, Location> lastLocations = new HashMap<>();
    private final Map<UUID, Long> expiresAt = new HashMap<>();

    PetFetchActions(PetRuntime runtime, PetActions actions) {
        this.runtime = runtime;
        this.actions = actions;
    }

    void throwToy(Player player, ItemStack hand, HeldItem held) {
        var accepting = runtime.config().toyTypes(held);
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
        actions.anticipation().threw(player);
        long now = System.currentTimeMillis();
        for (Pet pet : runtime.store().active()) {
            PetTypeDef type = runtime.config().type(pet.typeId());
            Entity body = runtime.entity(pet);
            if (type == null || !accepting.contains(type) || !(body instanceof Mob mob)
                    || !body.getWorld().equals(player.getWorld())
                    || !actions.anticipation().attentive(pet, player, now)
                    || !canChase(pet)) continue;
            FetchJob previous = pet.fetch();
            ItemRef toy = type.toy(held);
            boolean favorite = toy != null && toy.key().equals(pet.favoriteToy());
            double switchChance = favorite ? 0.65 : previous != null && previous.favorite(pet.id()) ? 0.1 : 0.35;
            if (previous != null && (pet.id().equals(previous.carrierId()) || runtime.random().nextDouble() >= switchChance)) continue;
            // Leaving a race never returns or removes the toy that other pets chase.
            releaseFetch(pet, false);
            actions.clearInteractions(pet);
            runtime.visual().cancelAction(body);
            job.favorite(pet.id(), favorite);
            pet.fetch(job);
            job.join(pet.id());
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
        return runtime.behaves(pet, net.tfminecraft.companionpets.config.PetBehavior.FETCH)
                && !pet.stored() && !pet.dead() && pet.illness() != Illness.SICK && pet.illness() != Illness.WEAKENED
                && pet.need(Need.HEALTH) > 0 && pet.need(Need.ENERGY) >= 25 && pet.need(Need.HUNGER) >= 25
                && !training(pet)
                && pet.activity() != Activity.SLEEPING && pet.order() == PetOrder.FOLLOW && !pet.staying()
                && System.currentTimeMillis() >= pet.forcedSitUntilMillis();
    }

    void navigate(Pet pet, Mob mob) {
        if (mob instanceof Wolf wolf) net.tfminecraft.companionpets.integration.WolfShake.defer(wolf);
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
        step(pet, mob, System.currentTimeMillis());
    }

    void step(Pet pet, Mob mob, long now) {
        FetchJob job = pet.fetch();
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
        double speed = movementSpeed(pet);
        PetFx.sit(mob, false);
        PetFx.lie(mob, false);
        if (job.phase() == FetchPhase.AIR) {
            Entity projectile = job.projectileId() == null ? null : Bukkit.getEntity(job.projectileId());
            if (projectile == null) return;
            if (!mob.getWorld().equals(projectile.getWorld())) { actions.releaseFetch(pet, owner, true); return; }
            moveTo(pet, mob, projectile.getLocation(), speed, now);
            return;
        }
        if (job.phase() == FetchPhase.GROUND) {
            Entity item = job.itemId() == null ? null : Bukkit.getEntity(job.itemId());
            if (item == null) {
                actions.releaseFetch(pet, owner, true);
                return;
            }
            if (!mob.getWorld().equals(item.getWorld())) { actions.releaseFetch(pet, owner, true); return; }
            if (runtime.behaves(pet, net.tfminecraft.companionpets.config.PetBehavior.CAT_PLAY)
                    && (mob.getLocation().distanceSquared(item.getLocation()) <= 4 || job.stalking(pet.id()) || job.pouncing(pet.id()))) {
                if (competing(pet, item.getLocation())) {
                    if (job.pouncing(pet.id())) {
                        FetchJob.Stalk stalk = job.stalk(pet.id(), now);
                        if (now - stalk.pounceAt < 250 || !mob.isOnGround() && now - stalk.pounceAt < 1000) {
                            runtime.visual().cancelAction(mob);
                            return;
                        }
                    }
                    job.stopStalk(pet.id(), now); restoreCat(pet);
                    runtime.visual().cancelAction(mob);
                } else {
                    FetchJob.Stalk stalk = job.stalk(pet.id(), now);
                    if (!stalk.finished) {
                        PetFx.look(mob, item.getLocation());
                        if (now < stalk.until) {
                            mob.getPathfinder().stopPathfinding();
                            var type = runtime.config().type(pet.typeId());
                            if (mob instanceof org.bukkit.entity.Cat cat && (!type.appearance().modeled()
                                    || runtime.capabilities(type).animations().containsKey(net.tfminecraft.companionpets.visual.PetAnimation.CROUCH))) {
                                crouchedCats.putIfAbsent(pet.id(), cat.isSneaking()); cat.setSneaking(true);
                            }
                            return;
                        }
                        restoreCat(pet);
                        if (stalk.pounceAt < 0) {
                            stalk.pounceAt = now; mob.getPathfinder().stopPathfinding();
                            Vector direction = item.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0);
                            if (direction.lengthSquared() > .001) direction.normalize().multiply(Math.min(.45, mob.getLocation().distance(item.getLocation()) / 6));
                            mob.setVelocity(direction.setY(.3));
                            var type = runtime.config().type(pet.typeId());
                            var jump = runtime.capabilities(type).animations().get(net.tfminecraft.companionpets.visual.PetAnimation.JUMP);
                            if (jump != null) runtime.visual().playClip(mob, type, jump.name(), .5);
                            return;
                        }
                        if (now - stalk.pounceAt < 250 || !mob.isOnGround() && now - stalk.pounceAt < 1000) return;
                        stalk.finished = true;
                        runtime.visual().cancelAction(mob);
                    }
                }
            }
            restoreCat(pet);
            if (claim(pet, now)) {
                mob.getPathfinder().stopPathfinding();
            } else {
                moveTo(pet, mob, item.getLocation(), speed, now);
            }
            return;
        }
        if (!mob.getWorld().equals(owner.getWorld())) {
            actions.releaseFetch(pet, owner, false);
            return;
        }
        if (!pet.id().equals(job.carrierId())) {
            Location destination = carrierDestination(pet, job, owner);
            if (destination == null) { actions.releaseFetch(pet, owner, false); return; }
            if (mob.getLocation().distanceSquared(destination) > 1) moveTo(pet, mob, destination, speed, now);
            else mob.getPathfinder().stopPathfinding();
            Pet carrier = runtime.store().get(job.carrierId());
            PetFx.look(mob, runtime.entity(carrier));
            return;
        }
        if (mob.getLocation().distance(owner.getLocation()) < 2.2) {
            runtime.recordCare(owner, pet, 3, now);
            returned(pet, owner);
            double mood = runtime.config().care().playMoodGain() * (favorite ? runtime.config().care().favoriteMoodMultiplier() : 1.0);
            pet.need(Need.MOOD, pet.need(Need.MOOD) + mood);
            pet.need(Need.ENERGY, pet.need(Need.ENERGY) - runtime.config().care().playEnergyCost());
            PetFx.hearts(mob, favorite ? 6 : 3);
            mob.getPathfinder().stopPathfinding();
        } else {
            moveTo(pet, mob, owner.getLocation(), speed, now);
        }
    }

    private void moveTo(Pet pet, Mob body, Location target, double speed, long now) {
        Route previous = routes.get(pet.id());
        FetchJob job = pet.fetch();
        if (previous != null && previous.job == job && previous.phase == job.phase() && now < previous.refreshAt
                && previous.target.getWorld().equals(target.getWorld()) && previous.target.distanceSquared(target) < 16) return;
        // Keep pickup and delivery checks on every AI tick, but avoid recalculating
        // paths every tick. Phase changes and distant target jumps refresh immediately.
        body.getPathfinder().moveTo(target, speed);
        routes.put(pet.id(), new Route(job, job.phase(), target.clone(), now + 250));
    }
    private record Route(FetchJob job, FetchPhase phase, Location target, long refreshAt) { }

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
        return claim(pet, System.currentTimeMillis());
    }

    boolean claim(Pet pet, long now) {
        FetchJob job = pet.fetch();
        Entity item = job == null || job.itemId() == null ? null : Bukkit.getEntity(job.itemId());
        Entity body = runtime.entity(pet);
        if (item != null && body != null && runtime.behaves(pet, net.tfminecraft.companionpets.config.PetBehavior.CAT_PLAY)
                && !job.stalkFinished(pet.id()) && !competing(pet, item.getLocation())) return false;
        if (item == null || body == null || !canChase(pet) || !body.getWorld().equals(item.getWorld())
                || body.getLocation().distance(item.getLocation()) >= 1.7 || !job.claim(pet.id())) return false;
        item.remove();
        job.itemId(null);
        expiresAt.put(job.id(), now + 120_000L);
        // Keep the race participants in this job: they follow the winner until
        // delivery, and finish together when the one physical toy is returned.
        for (Pet other : chasers(job)) if (other != pet && runtime.entity(other) instanceof Mob mob) step(other, mob);
        return true;
    }

    private boolean competing(Pet pet, Location toy) {
        Entity body = runtime.entity(pet);
        if (body == null || !body.getWorld().equals(toy.getWorld())) return false;
        double ownDistance = body.getLocation().distanceSquared(toy);
        for (Pet other : chasers(pet.fetch())) {
            Entity competitor = runtime.entity(other);
            if (other == pet || competitor == null || !canChase(other) || !competitor.getWorld().equals(toy.getWorld())) continue;
            double distance = competitor.getLocation().distanceSquared(toy);
            if (distance < 16 || distance < ownDistance) return true;
        }
        return false;
    }

    Location destination(Pet pet) {
        FetchJob job = pet.fetch();
        if (job == null) return null;
        if (job.phase() == FetchPhase.CARRY && !pet.id().equals(job.carrierId()))
            return carrierDestination(pet, job, Bukkit.getPlayer(job.throwerId()));
        Entity target = switch (job.phase()) {
            case AIR -> entity(job.projectileId());
            case GROUND -> entity(job.itemId());
            case CARRY -> Bukkit.getPlayer(job.throwerId());
        };
        return target == null ? null : target.getLocation();
    }

    UUID destinationId(Pet pet) {
        FetchJob job = pet.fetch();
        return switch (job.phase()) {
            case AIR -> job.projectileId();
            case GROUND -> job.itemId();
            case CARRY -> {
                if (pet.id().equals(job.carrierId())) yield job.throwerId();
                Entity carrier = runtime.entity(runtime.store().get(job.carrierId()));
                yield carrier == null ? null : carrier.getUniqueId();
            }
        };
    }

    private Location carrierDestination(Pet pet, FetchJob job, Player thrower) {
        Pet carrier = runtime.store().get(job.carrierId());
        Entity body = runtime.entity(carrier);
        Entity follower = runtime.entity(pet);
        if (body == null || follower == null || !body.getWorld().equals(follower.getWorld())) return null;
        Location at = body.getLocation();
        Vector forward = thrower != null && thrower.getWorld().equals(body.getWorld())
                ? thrower.getLocation().toVector().subtract(at.toVector()).setY(0) : at.getDirection().setY(0);
        if (forward.lengthSquared() < 0.001) forward = new Vector(0, 0, 1);
        forward.normalize();
        int slot = 0;
        for (Pet other : chasers(job))
            if (!other.id().equals(job.carrierId()) && other.id().compareTo(pet.id()) < 0) slot++;
        double side = (slot % 2 == 0 ? -1 : 1) * (0.65 + 0.6 * (slot / 4));
        return at.clone().add(forward.clone().multiply(-1.5 - 0.8 * (slot / 2)))
                .add(new Vector(-forward.getZ(), 0, forward.getX()).multiply(side));
    }

    double movementSpeed(Pet pet) {
        FetchJob job = pet.fetch();
        double base = job.speed(pet.id(), Locomotion.speed(pet.illness(), pet.bond(),
                pet.need(Need.CLEANLINESS), false));
        return base * runtime.config().play().fetchSpeedMultiplier();
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
        routes.keySet().removeIf(id -> runtime.store().get(id) == null || runtime.store().get(id).fetch() == null);
        for (FetchJob job : List.copyOf(throwsInProgress.values())) {
            for (Pet pet : chasers(job)) {
                if (runtime.entity(pet) == null || !canChase(pet)
                        || !pet.ownerId().equals(job.throwerId()) && !sharedFetchAllowed(pet, job)) releaseFetch(pet, false);
            }
            if (!throwsInProgress.containsKey(job.id())) continue;
            Entity target = job.phase() == FetchPhase.AIR ? entity(job.projectileId()) : entity(job.itemId());
            if (job.phase() == FetchPhase.CARRY) {
                Pet carryingPet = runtime.store().get(job.carrierId());
                Entity carrier = runtime.entity(carryingPet);
                if (carrier != null) {
                    lastLocations.put(job.id(), carrier.getLocation().clone());
                    if (now >= expiresAt.get(job.id())) releaseFetch(carryingPet, true);
                }
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

    private boolean sharedFetchAllowed(Pet pet, FetchJob job) {
        Player thrower = Bukkit.getPlayer(job.throwerId());
        return thrower != null && actions.anticipation().strangerAllowed(pet, thrower);
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
        List<Pet> result = new java.util.ArrayList<>();
        for (UUID id : job.chasers()) {
            Pet pet = runtime.store().get(id);
            if (pet != null && !pet.stored() && !pet.dead() && pet.fetch() == job) result.add(pet);
        }
        return result;
    }

    private void detach(Pet pet) {
        routes.remove(pet.id());
        restoreCat(pet);
        pet.fetch(null);
        if (pet.activity() == Activity.PLAYING) pet.activity(Activity.NONE);
        if (runtime.entity(pet) instanceof Mob mob) {
            mob.getPathfinder().stopPathfinding();
            if (mob instanceof Wolf wolf) net.tfminecraft.companionpets.integration.WolfShake.restore(wolf);
        }
    }

    private void restoreCat(Pet pet) {
        Boolean previous = crouchedCats.remove(pet.id());
        if (previous != null && runtime.entity(pet) instanceof org.bukkit.entity.Cat cat) cat.setSneaking(previous);
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
        var followers = job.phase() == FetchPhase.CARRY ? chasers(job).stream()
                .filter(p -> !p.id().equals(job.carrierId())).toList() : List.<Pet>of();
        var speeds = new HashMap<UUID, Double>();
        for (Pet follower : followers) speeds.put(follower.id(), movementSpeed(follower));
        forget(job);
        for (Pet follower : followers) {
            Player owner = Bukkit.getPlayer(follower.ownerId());
            Entity body = runtime.entity(follower);
            if (canChase(follower) && owner != null && owner.isOnline() && body != null && body.getWorld().equals(owner.getWorld()))
                actions.roaming().returnFromFetch(follower, owner, speeds.get(follower.id()));
        }
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
