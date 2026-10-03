package net.tfminecraft.companionpets.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Snowball;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.SnowballMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.item.ToyItems;
import net.tfminecraft.companionpets.listen.PetListener;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.play.FetchPhase;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.visual.IdleVisual;

class PetFetchWorkflowTest {
    private ServerMock server;
    private PetRuntime runtime;
    private PetActions actions;
    private PetListener listener;
    private PlayerMock owner;
    private Pet pet;
    private WorldMock world;
    private ItemStack toy;
    private final java.util.Map<UUID, Location> navigationTargets = new java.util.HashMap<>();
    private final java.util.Map<UUID, Double> navigationSpeeds = new java.util.HashMap<>();

    @BeforeEach void setup() throws Exception {
        var goals = new java.util.HashMap<String, com.destroystokyo.paper.entity.ai.Goal<?>>();
        server = MockBukkit.mock(new ServerMock() {
            @Override public com.destroystokyo.paper.entity.ai.MobGoals getMobGoals() {
                return (com.destroystokyo.paper.entity.ai.MobGoals) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{com.destroystokyo.paper.entity.ai.MobGoals.class}, (proxy, method, args) -> {
                            var id = ((org.bukkit.entity.Mob) args[0]).getUniqueId();
                            return switch (method.getName()) {
                                case "getGoal" -> goals.get(id + ":" + args[1]);
                                case "addGoal" -> { goals.put(id + ":" + ((com.destroystokyo.paper.entity.ai.Goal<?>) args[2]).getKey(), (com.destroystokyo.paper.entity.ai.Goal<?>) args[2]); yield null; }
                                default -> throw new AssertionError("Unexpected goals call: " + method.getName());
                            };
                        });
            }
        }); var plugin = MockBukkit.createMockPlugin();
        world = new WorldMock() {
            @Override public void spawnParticle(org.bukkit.Particle particle, Location at, int count,
                    double x, double y, double z, double extra) { }
            @Override public BlockMock getBlockAt(int x, int y, int z) {
                return new BlockMock(new Location(this, x, y, z)) { @Override public boolean isPassable() { return true; } };
            }
        };
        server.addWorld(world); owner = server.addPlayer(); owner.teleport(new Location(world, 0, 64, 0));
        var yaml = new YamlConfiguration(); yaml.loadFromString("items: {toys: [STICK]}\npets: {wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG}}\n");
        var visual = new IdleVisual(); var key = new NamespacedKey(plugin, "pet");
        var store = new PetStore(plugin); assertTrue(store.load());
        runtime = new PetRuntime(plugin, CompanionConfig.load(plugin, yaml), store, new Sessions(), new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        actions = new PetActions(runtime); listener = new PetListener(runtime, actions);
        pet = outsidePet(owner.getUniqueId(), "Toby", 1);
        toy = new ItemStack(Material.STICK);
        var meta = toy.getItemMeta(); meta.setCustomModelData(4321);
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "test-toy-data"), PersistentDataType.STRING, "original"); toy.setItemMeta(meta);
    }

    @AfterEach void teardown() { MockBukkit.unmock(); }

    private Pet outsidePet(UUID ownerId, String name, double x) {
        Pet result = new Pet(UUID.randomUUID(), ownerId, "wolf", name, PetSex.MALE);
        runtime.store().add(result);
        var body = new FetchWolf(server, UUID.randomUUID(), navigationTargets, navigationSpeeds);
        server.registerEntity(body);
        body.teleport(new Location(world, x, 64, 0)); result.stored(false); runtime.remember(result, body);
        return result;
    }

    public static class FetchWolf extends WolfMock {
            boolean inWater;
            final NativeClock clock = new NativeClock();
            public FetchWolf(ServerMock server, UUID id, java.util.Map<UUID, Location> navigationTargets,
                    java.util.Map<UUID, Double> navigationSpeeds) {
                super(server, id);
                pathfinder =
                    (com.destroystokyo.paper.entity.Pathfinder) java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(), new Class<?>[]{com.destroystokyo.paper.entity.Pathfinder.class},
                            (proxy, method, args) -> switch (method.getName()) {
                                case "moveTo" -> {
                                    navigationTargets.put(getUniqueId(), ((Location) args[0]).clone());
                                    navigationSpeeds.put(getUniqueId(), ((Number) args[1]).doubleValue()); yield true;
                                }
                                case "stopPathfinding" -> { navigationTargets.remove(getUniqueId()); yield null; }
                                case "hasPath" -> navigationTargets.containsKey(getUniqueId());
                                case "getEntity" -> this;
                                default -> throw new AssertionError("Unexpected navigation call: " + method.getName());
                            });
            }
            private final com.destroystokyo.paper.entity.Pathfinder pathfinder;
            @Override public com.destroystokyo.paper.entity.Pathfinder getPathfinder() { return pathfinder; }
            @Override public boolean isInWater() { return inWater; }
            @Override public boolean isOnGround() { return true; }
            public NativeClock getHandle() { return clock; }
    }

    public static class NativeClock {
        public boolean isWet;
        float progress;
        final NativeLevel level = new NativeLevel();
        public float getShakeAnim(float partial) { return progress; }
        public void handleEntityEvent(byte event) { if (event == 56) progress = 0; }
        public NativeLevel level() { return level; }
    }

    public static class NativeLevel {
        public void broadcastEntityEvent(NativeClock wolf, byte event) { }
    }

    private Snowball throwToy() {
        var before = world.getEntities().stream().map(org.bukkit.entity.Entity::getUniqueId).toList();
        owner.getInventory().setItemInMainHand(toy.clone());
        actions.useWorld(owner, owner.getInventory().getItemInMainHand(), null, null, false, true);
        return world.getEntities().stream().filter(Snowball.class::isInstance).map(Snowball.class::cast)
                .filter(ball -> !before.contains(ball.getUniqueId())).findFirst().orElseThrow();
    }

    private void land(Snowball ball) {
        ball.teleport(new Location(world, 5, 64, 0));
        listener.onToyHit(new ProjectileHitEvent(ball));
    }

    private java.util.List<Item> items() {
        return world.getEntities().stream().filter(Item.class::isInstance).map(Item.class::cast).toList();
    }

    @Test void throwSucceedsWithOnlyASickPetAndLandsAsOnePickableToy() {
        pet.illness(Illness.WEAKENED); pet.need(Need.HEALTH, 0);
        Snowball ball = throwToy();
        assertEquals(0, owner.getInventory().getItemInMainHand().getAmount());
        assertNull(pet.fetch());
        land(ball);
        assertEquals(1, items().size()); assertTrue(toy.isSimilar(items().getFirst().getItemStack()));
        assertEquals(0, items().getFirst().getPickupDelay());
        assertFalse(items().getFirst().getPersistentDataContainer().has(runtime.toyKey()));
    }

    @Test void throwingWithoutAnyPetStillPreservesTheToy() {
        pet.stored(true);
        land(throwToy());
        assertEquals(1, items().size()); assertTrue(toy.isSimilar(items().getFirst().getItemStack()));
    }

    @Test void ownAndForeignPetsRaceAndOnlyFirstArrivalCanCarryAndReturnToThrower() {
        var foreignOwner = server.addPlayer(); foreignOwner.teleport(new Location(world, 20, 64, 0));
        Pet other = outsidePet(foreignOwner.getUniqueId(), "Luna", 2);
        Snowball ball = throwToy(); var job = pet.fetch();
        assertNotNull(job); assertSame(job, other.fetch()); assertEquals(owner.getUniqueId(), job.throwerId());
        land(ball);
        runtime.entity(other).teleport(new Location(world, 5, 64, 0));
        assertTrue(actions.fetchActions().claim(other));
        assertFalse(actions.fetchActions().claim(pet));
        assertEquals(other.id(), job.carrierId()); assertNull(pet.fetch()); assertTrue(items().isEmpty());
        actions.releaseFetch(other, foreignOwner, true);
        assertEquals(1, items().size()); assertTrue(toy.isSimilar(items().getFirst().getItemStack()));
        assertTrue(items().getFirst().getLocation().distance(owner.getLocation()) < 3);
        actions.releaseFetch(other, foreignOwner, true);
        assertEquals(1, items().size());
    }

    @Test void duplicateAndUnknownProjectileHitsCannotDuplicateToy() {
        Snowball ball = throwToy(); land(ball);
        assertEquals(1, items().size());
        listener.onToyHit(new ProjectileHitEvent(ball));
        var unknown = new SnowballMock(server, UUID.randomUUID()); server.registerEntity(unknown);
        unknown.getPersistentDataContainer().set(runtime.toyKey(), PersistentDataType.STRING, UUID.randomUUID().toString());
        listener.onToyHit(new ProjectileHitEvent(unknown));
        assertEquals(1, items().size()); assertTrue(unknown.isDead());
    }

    @Test void everyParticipantStandsUpAndNavigatesToTheSameAirAndGroundToy() {
        Pet other = outsidePet(UUID.randomUUID(), "Luna", -2);
        var firstBody = (WolfMock) runtime.entity(pet);
        var otherBody = (WolfMock) runtime.entity(other);
        // A stale native posture must not block the callback that clears it.
        firstBody.setSitting(true); firstBody.setAware(false);
        otherBody.setSitting(true); otherBody.setAware(false);
        Snowball ball = throwToy();
        for (var body : java.util.List.of(firstBody, otherBody)) {
            assertFalse(body.isSitting()); assertTrue(body.isAware());
            assertEquals(ball.getLocation(), navigationTargets.get(body.getUniqueId()));
            var goal = server.getMobGoals().getGoal(body, com.destroystokyo.paper.entity.ai.GoalKey.of(
                    org.bukkit.entity.Mob.class, new NamespacedKey(runtime.plugin(), "fetch_navigation")));
            assertNotNull(goal); assertTrue(goal.shouldActivate());
        }
        land(ball);
        for (var body : java.util.List.of(firstBody, otherBody)) {
            var goal = server.getMobGoals().getGoal(body, com.destroystokyo.paper.entity.ai.GoalKey.of(
                    org.bukkit.entity.Mob.class, new NamespacedKey(runtime.plugin(), "fetch_navigation")));
            goal.tick();
            assertEquals(items().getFirst().getLocation(), navigationTargets.get(body.getUniqueId()));
        }
        new PetTicker(runtime, actions).run();
        Location target = items().getFirst().getLocation();
        assertEquals(target, navigationTargets.get(firstBody.getUniqueId()));
        assertEquals(target, navigationTargets.get(otherBody.getUniqueId()));
        assertSame(pet.fetch(), other.fetch());
        otherBody.teleport(target);
        actions.fetchActions().step(other, otherBody);
        assertEquals(FetchPhase.CARRY, other.fetch().phase());
        assertNull(pet.fetch());
    }

    @Test void wetWolfKeepsFetchingAndCanShakeOnlyAfterReturningTheToy() {
        var body = (FetchWolf) runtime.entity(pet);
        body.clock.isWet = true; body.clock.progress = 0.5F;
        Snowball ball = throwToy(); var job = pet.fetch();
        assertFalse(body.clock.isWet); assertEquals(0, body.clock.progress);
        assertEquals(ball.getLocation(), navigationTargets.get(body.getUniqueId()));
        land(ball);
        var goal = server.getMobGoals().getGoal(body, com.destroystokyo.paper.entity.ai.GoalKey.of(
                org.bukkit.entity.Mob.class, new NamespacedKey(runtime.plugin(), "fetch_navigation")));
        body.clock.isWet = true; body.clock.progress = 0.5F;
        goal.tick();
        assertSame(job, pet.fetch()); assertFalse(body.clock.isWet); assertEquals(0, body.clock.progress);
        assertEquals(items().getFirst().getLocation(), navigationTargets.get(body.getUniqueId()));
        body.teleport(items().getFirst().getLocation());
        actions.fetchActions().step(pet, body);
        assertEquals(FetchPhase.CARRY, job.phase());
        body.clock.isWet = true; body.clock.progress = 0.5F;
        goal.tick(); // Shake suppression also runs inside the navigation throttle.
        assertFalse(body.clock.isWet); assertEquals(0, body.clock.progress);
        assertSame(job, pet.fetch());
        body.teleport(owner.getLocation()); actions.fetchActions().step(pet, body);
        assertNull(pet.fetch()); assertTrue(body.clock.isWet);
        assertEquals(1, items().size()); assertTrue(toy.isSimilar(items().getFirst().getItemStack()));
        body.clock.isWet = false;
        net.tfminecraft.companionpets.integration.WolfShake.restore(body);
        assertFalse(body.clock.isWet);
    }

    @Test void losingPetRunsBackWithoutTeleportEvenAfterWinnerReturnsAndCallTimeoutPasses() {
        Pet other = outsidePet(owner.getUniqueId(), "Luna", 2);
        land(throwToy());
        Location far = new Location(world, 30, 64, 0);
        items().getFirst().teleport(far);
        var winner = (FetchWolf) runtime.entity(pet);
        var loser = (FetchWolf) runtime.entity(other);
        winner.teleport(far); loser.teleport(far.clone().add(0, 0, 1));
        assertTrue(actions.fetchActions().claim(pet));
        assertNull(other.fetch()); assertEquals(Activity.ATTENDING, other.activity());
        assertEquals(owner.getLocation(), navigationTargets.get(loser.getUniqueId()));
        var teleport = new org.bukkit.event.entity.EntityTeleportEvent(loser, loser.getLocation(), owner.getLocation());
        listener.onTeleport(teleport); assertTrue(teleport.isCancelled());
        var goal = server.getMobGoals().getGoal(loser, com.destroystokyo.paper.entity.ai.GoalKey.of(
                org.bukkit.entity.Mob.class, new NamespacedKey(runtime.plugin(), "call_navigation")));
        assertNotNull(goal); assertTrue(goal.shouldActivate());
        Location before = loser.getLocation();
        winner.teleport(owner.getLocation()); actions.fetchActions().step(pet, winner);
        assertNull(pet.fetch()); assertEquals(Activity.ATTENDING, other.activity());
        new PetTicker(runtime, actions).run(); goal.tick();
        actions.roaming().tickAttention(other, loser, System.currentTimeMillis() + 60_000);
        assertEquals(before, loser.getLocation()); assertTrue(goal.shouldActivate());
        assertEquals(owner.getLocation(), navigationTargets.get(loser.getUniqueId()));
        loser.teleport(owner.getLocation()); goal.tick();
        assertEquals(Activity.NONE, other.activity()); assertFalse(goal.shouldActivate());
        var afterReturn = new org.bukkit.event.entity.EntityTeleportEvent(loser, loser.getLocation(), owner.getLocation());
        listener.onTeleport(afterReturn); assertFalse(afterReturn.isCancelled());
    }

    @Test void winnerAndLoserKeepTheirOwnFasterOutboundSpeedOnLandAndInWater() {
        Pet other = outsidePet(owner.getUniqueId(), "Luna", 2);
        pet.bond(0); pet.favoriteToy(null); other.bond(80); other.favoriteToy("STICK");
        double oldSpeed = net.tfminecraft.companionpets.behavior.Locomotion.speed(
                pet.illness(), pet.bond(), pet.need(Need.CLEANLINESS), false);
        Snowball ball = throwToy();
        var winner = (FetchWolf) runtime.entity(pet); var loser = (FetchWolf) runtime.entity(other);
        double winnerOutbound = navigationSpeeds.get(winner.getUniqueId());
        double loserOutbound = navigationSpeeds.get(loser.getUniqueId());
        assertTrue(winnerOutbound > oldSpeed); assertTrue(loserOutbound > winnerOutbound);
        land(ball); items().getFirst().teleport(new Location(world, 30, 64, 0));
        loser.teleport(new Location(world, 4, 64, 0));
        loser.inWater = true; new PetTicker(runtime, actions).run();
        var water = server.getMobGoals().getGoal(loser, com.destroystokyo.paper.entity.ai.GoalKey.of(
                org.bukkit.entity.Mob.class, new NamespacedKey(runtime.plugin(), "water_navigation")));
        water.start(); assertEquals(loserOutbound, navigationSpeeds.get(loser.getUniqueId()));
        double outboundSwim = loser.getVelocity().clone().setY(0).length();
        // Care changes during the trip do not alter its return pace.
        pet.bond(100); other.bond(0); other.need(Need.CLEANLINESS, 0);
        winner.teleport(items().getFirst().getLocation()); assertTrue(actions.fetchActions().claim(pet));
        assertEquals(loserOutbound, navigationSpeeds.get(loser.getUniqueId()));
        water.start();
        assertEquals(loserOutbound, navigationSpeeds.get(loser.getUniqueId()));
        assertEquals(outboundSwim, loser.getVelocity().clone().setY(0).length(), 1e-9);
        assertTrue(loser.getVelocity().getX() < 0);
        actions.fetchActions().step(pet, winner);
        assertEquals(winnerOutbound, navigationSpeeds.get(winner.getUniqueId()));
        winner.inWater = true; new PetTicker(runtime, actions).run();
        var winnerWater = server.getMobGoals().getGoal(winner, com.destroystokyo.paper.entity.ai.GoalKey.of(
                org.bukkit.entity.Mob.class, new NamespacedKey(runtime.plugin(), "water_navigation")));
        winnerWater.start(); assertEquals(winnerOutbound, navigationSpeeds.get(winner.getUniqueId()));
    }

    @Test void crossingWaterKeepsToyDestinationThenUsesThrowerDestinationWhileCarrying() {
        land(throwToy()); var job = pet.fetch();
        var body = (FetchWolf) runtime.entity(pet);
        body.teleport(new Location(world, 2, 64, 0)); body.inWater = true;
        body.clock.isWet = true; body.clock.progress = 0.5F;
        new PetTicker(runtime, actions).run();
        var goal = server.getMobGoals().getGoal(body, com.destroystokyo.paper.entity.ai.GoalKey.of(
                org.bukkit.entity.Mob.class, new NamespacedKey(runtime.plugin(), "water_navigation")));
        assertNotNull(goal); assertTrue(goal.shouldActivate()); goal.start();
        assertSame(job, pet.fetch());
        var teleport = new org.bukkit.event.entity.EntityTeleportEvent(body, body.getLocation(), owner.getLocation());
        listener.onTeleport(teleport); assertTrue(teleport.isCancelled());
        assertEquals(items().getFirst().getLocation(), navigationTargets.get(body.getUniqueId()));
        assertTrue(body.getVelocity().getX() > 0); assertTrue(body.getVelocity().getY() > 0);
        assertEquals(0, body.clock.progress); assertFalse(body.clock.isWet);
        for (int i = 0; i < 3; i++) { new PetTicker(runtime, actions).run(); goal.start(); }
        assertSame(job, pet.fetch()); assertTrue(body.getVelocity().getX() > 0);
        body.teleport(items().getFirst().getLocation()); goal.start();
        assertEquals(FetchPhase.CARRY, job.phase());
        assertEquals(owner.getLocation(), navigationTargets.get(body.getUniqueId()));
        assertTrue(body.getVelocity().getX() < 0);
        body.inWater = false; goal.stop();
        actions.fetchActions().step(pet, body);
        assertSame(job, pet.fetch()); assertEquals(owner.getLocation(), navigationTargets.get(body.getUniqueId()));
    }

    @Test void losingPetAlsoKeepsItsReturnRouteWhileSwimming() {
        Pet other = outsidePet(owner.getUniqueId(), "Luna", 2);
        land(throwToy());
        var body = (FetchWolf) runtime.entity(other);
        body.teleport(new Location(world, 4, 64, 0)); body.inWater = true;
        runtime.entity(pet).teleport(items().getFirst().getLocation());
        assertTrue(actions.fetchActions().claim(pet));
        new PetTicker(runtime, actions).run();
        assertEquals(Activity.ATTENDING, other.activity());
        var goal = server.getMobGoals().getGoal(body, com.destroystokyo.paper.entity.ai.GoalKey.of(
                org.bukkit.entity.Mob.class, new NamespacedKey(runtime.plugin(), "water_navigation")));
        goal.start();
        assertEquals(owner.getLocation(), navigationTargets.get(body.getUniqueId()));
        assertTrue(body.getVelocity().getX() < 0); assertEquals(Activity.ATTENDING, other.activity());
    }

    @Test void visualTickerDoesNotStopFetchingOrPlayShakeForAModeledWetWolf() throws Exception {
        land(throwToy());
        var body = (FetchWolf) runtime.entity(pet);
        Location target = navigationTargets.get(body.getUniqueId());
        var yaml = new YamlConfiguration();
        yaml.loadFromString("pets: {wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG, appearance: {type: modelengine, model: beagle}}}\n");
        var plays = new java.util.ArrayList<String>();
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var visual = new net.tfminecraft.companionpets.visual.PetVisual() {
            @Override public void apply(org.bukkit.entity.Entity entity, net.tfminecraft.companionpets.config.PetTypeDef type) { }
            @Override public boolean play(org.bukkit.entity.Entity entity, net.tfminecraft.companionpets.config.PetTypeDef type, String action) {
                plays.add(action); return true;
            }
            @Override public boolean holdsMovement(org.bukkit.entity.Entity entity) { return true; }
            @Override public boolean attached(org.bukkit.entity.Entity entity) { return true; }
            @Override public void cancelAction(org.bukkit.entity.Entity entity) { cancelled.set(true); }
        };
        var modeled = new PetRuntime(runtime.plugin(), CompanionConfig.load(runtime.plugin(), yaml), runtime.store(),
                runtime.sessions(), runtime.bodies(), visual, runtime.petKey(), runtime.toyKey());
        body.clock.isWet = true; body.clock.progress = 0.5F;
        new net.tfminecraft.companionpets.visual.PetVisualTicker(modeled).run();
        assertTrue(cancelled.get()); assertTrue(plays.isEmpty());
        assertEquals(0, body.clock.progress); assertFalse(body.clock.isWet);
        assertEquals(target, navigationTargets.get(body.getUniqueId())); assertNotNull(pet.fetch());
    }

    @Test void onlyFollowingPetsChaseWhileAwakeSittingAndStayingPetsRemainInPlace() {
        Pet sitting = outsidePet(UUID.randomUUID(), "Sit", -2); sitting.order(PetOrder.SIT);
        Pet staying = outsidePet(UUID.randomUUID(), "Stay", 2); staying.order(PetOrder.STAY);
        Pet anchored = outsidePet(UUID.randomUUID(), "Anchored", -3); anchored.staying(true);
        var sittingBody = (WolfMock) runtime.entity(sitting);
        sittingBody.setSitting(true); sittingBody.setAware(false);
        ((WolfMock) runtime.entity(staying)).setAware(false);
        Snowball ball = throwToy();
        assertNotNull(pet.fetch());
        for (Pet idle : java.util.List.of(sitting, staying, anchored)) assertNull(idle.fetch());
        assertEquals(1, navigationTargets.size());
        assertTrue(sittingBody.isSitting()); assertFalse(sittingBody.isAware());
        land(ball); new PetTicker(runtime, actions).run();
        assertEquals(1, navigationTargets.size());
        assertEquals(PetOrder.SIT, sitting.order()); assertTrue(sittingBody.isSitting());
        assertEquals(PetOrder.STAY, staying.order()); assertFalse(((WolfMock) runtime.entity(staying)).isAware());
        assertEquals(PetOrder.FOLLOW, anchored.order()); assertTrue(anchored.staying());
    }

    @Test void changingFromFollowToAHoldOrderDropsOutOfTheRace() {
        for (PetOrder order : java.util.List.of(PetOrder.SIT, PetOrder.STAY, PetOrder.LAY)) {
            pet.order(PetOrder.FOLLOW);
            land(throwToy()); assertNotNull(pet.fetch());
            pet.order(order);
            actions.fetchActions().tick(System.currentTimeMillis());
            assertNull(pet.fetch()); assertEquals(order, pet.order());
            assertTrue(items().stream().noneMatch(item -> item.getPersistentDataContainer().has(runtime.toyKey())));
        }
    }

    @Test void normalTickerReturnGoesToThrowerAndRewardsOnlyWinningPet() throws Exception {
        Pet other = outsidePet(UUID.randomUUID(), "Luna", 2);
        pet.need(Need.MOOD, 10); other.need(Need.MOOD, 10); other.favoriteToy("STICK");
        land(throwToy()); var job = other.fetch();
        assertFalse(job.favorite(pet.id())); assertTrue(job.favorite(other.id()));
        runtime.entity(other).teleport(new Location(world, 5, 64, 0));
        actions.fetchActions().step(other, (org.bukkit.entity.Mob) runtime.entity(other));
        assertEquals(FetchPhase.CARRY, job.phase()); assertNull(pet.fetch());
        runtime.entity(other).teleport(owner.getLocation());
        actions.fetchActions().step(other, (org.bukkit.entity.Mob) runtime.entity(other));
        assertNull(other.fetch()); assertEquals(1, items().size());
        assertEquals(10, pet.need(Need.MOOD)); assertEquals(43, other.need(Need.MOOD));
        assertTrue(items().getFirst().getLocation().distance(owner.getLocation()) < 3);
    }

    @Test void orphanedFlightsAndUnreachableGroundToysAreReleasedExactlyOnce() {
        Snowball ball = throwToy(); ball.remove();
        long now = System.currentTimeMillis();
        actions.fetchActions().tick(now); actions.fetchActions().tick(now + 1_500);
        actions.fetchActions().tick(now + 2_000);
        assertEquals(1, items().size()); assertNull(pet.fetch());
        land(throwToy());
        actions.fetchActions().tick(now + 65_000);
        assertEquals(2, items().size()); assertNull(pet.fetch());
        assertTrue(items().stream().noneMatch(item -> item.getPersistentDataContainer().has(runtime.toyKey())));
    }

    @Test void droppingOutOfRaceDoesNotRemoveToyOrDetachRemainingPet() {
        Pet other = outsidePet(UUID.randomUUID(), "Luna", 2);
        Snowball ball = throwToy(); var job = pet.fetch(); land(ball);
        actions.releaseFetch(pet, owner, true);
        assertSame(job, other.fetch()); assertEquals(1, items().size());
        assertTrue(items().getFirst().getPersistentDataContainer().has(runtime.toyKey()));
        actions.releaseFetch(other, null, false);
        assertEquals(1, items().size()); assertEquals(0, items().getFirst().getPickupDelay());
        assertFalse(items().getFirst().getPersistentDataContainer().has(runtime.toyKey()));
    }

    @Test void repeatedThrowsCanSplitFocusWithoutDestroyingOldToy() {
        Pet other = outsidePet(UUID.randomUUID(), "Luna", 2);
        Snowball first = throwToy(); var firstJob = pet.fetch();
        runtime.random().setSeed(4096); // First roll switches, second roll stays.
        Snowball second = throwToy();
        assertNotSame(firstJob, pet.fetch()); assertSame(firstJob, other.fetch());
        land(first); land(second);
        assertEquals(2, items().size());
        assertNotEquals(pet.fetch().itemId(), other.fetch().itemId());
    }

    @Test void carryingPetDoesNotSwitchToNewThrow() {
        Snowball first = throwToy(); var job = pet.fetch(); land(first);
        runtime.entity(pet).teleport(new Location(world, 5, 64, 0));
        assertTrue(actions.fetchActions().claim(pet));
        land(throwToy());
        assertSame(job, pet.fetch()); assertEquals(FetchPhase.CARRY, job.phase());
        assertEquals(1, items().size());
    }

    @Test void offlineReturnStoresOneOwnToyAndPersistsItsMetadata() {
        land(throwToy());
        runtime.entity(pet).teleport(new Location(world, 5, 64, 0));
        assertTrue(actions.fetchActions().claim(pet));
        actions.releaseFetch(pet, null, false);
        assertNull(pet.fetch()); assertTrue(items().isEmpty()); assertTrue(toy.isSimilar(ToyItems.decode(pet.carriedToy())));
        assertTrue(runtime.store().save()); var loaded = new PetStore(runtime.plugin()); assertTrue(loaded.load());
        assertTrue(toy.isSimilar(ToyItems.decode(loaded.get(pet.id()).carriedToy())));
    }

    @Test void sickChaserDropsOutAndAbandonedGroundToyBecomesPickable() {
        land(throwToy()); pet.illness(Illness.SICK);
        actions.fetchActions().tick(System.currentTimeMillis());
        assertNull(pet.fetch()); assertEquals(1, items().size());
        assertFalse(items().getFirst().getPersistentDataContainer().has(runtime.toyKey()));
    }

    @Test void shutdownWithSeveralChasersPreservesExactlyOneToy() {
        Pet other = outsidePet(UUID.randomUUID(), "Luna", 2);
        throwToy(); actions.stashLooseToys(); actions.stashLooseToys();
        assertNull(pet.fetch()); assertNull(other.fetch());
        assertEquals(1, items().size()); assertTrue(toy.isSimilar(items().getFirst().getItemStack()));
        assertNull(pet.carriedToy()); assertNull(other.carriedToy());
    }

    @Test void distantStoredRestingAndIncompatiblePetsDoNotChase() throws Exception {
        Pet distant = outsidePet(UUID.randomUUID(), "Far", 100);
        Pet resting = outsidePet(UUID.randomUUID(), "Rest", 2); resting.order(PetOrder.LAY);
        Pet stored = outsidePet(UUID.randomUUID(), "Stored", 2); stored.stored(true);
        var yaml = new YamlConfiguration();
        yaml.loadFromString("items: {toys: [STICK]}\npets: {wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG}, cat: {entity: CAT, egg: CAT_SPAWN_EGG, items: {toys: []}}}\n");
        runtime.config(CompanionConfig.load(runtime.plugin(), yaml));
        Pet cat = new Pet(UUID.randomUUID(), UUID.randomUUID(), "cat", "Cat", PetSex.FEMALE);
        cat.stored(false); cat.entityId(resting.entityId()); runtime.store().add(cat);
        throwToy();
        assertNotNull(pet.fetch()); assertNull(distant.fetch()); assertNull(resting.fetch());
        assertNull(stored.fetch()); assertNull(cat.fetch());
    }
}
