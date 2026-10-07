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
    @Test void modeledWaterDropsStopWithTheNativeShakeAndDoNotRestartAfterGreeting() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("pets: {wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG, model: beagle}}\n");
        var player = new net.tfminecraft.companionpets.visual.AnimationPlayer() {
            private boolean active;
            public boolean play(net.tfminecraft.companionpets.config.PetAppearance.Clip clip, boolean loop) { active = true; return true; }
            public void stop(String clip) { active = false; }
            public boolean playing(String clip) { return active; }
            public double length(String clip) { return 1.08; }
        };
        var controller = new net.tfminecraft.companionpets.visual.AnimationController(player,
                java.util.Map.of(net.tfminecraft.companionpets.visual.PetAnimation.SHAKE,
                        new net.tfminecraft.companionpets.config.PetAppearance.Clip("shake", 1, 0)));
        var visual = new net.tfminecraft.companionpets.visual.PetVisual() {
            public void apply(org.bukkit.entity.Entity entity, net.tfminecraft.companionpets.config.PetTypeDef type) { }
            public void update(org.bukkit.entity.Entity entity, net.tfminecraft.companionpets.config.PetTypeDef type, net.tfminecraft.companionpets.visual.PetAnimation pose) { controller.update(pose); }
            public boolean attached(org.bukkit.entity.Entity entity) { return true; }
            public boolean shake(org.bukkit.entity.Entity entity, net.tfminecraft.companionpets.config.PetTypeDef type, double progress) { return controller.shake(progress); }
        };
        var updated = new PetRuntime(runtime.plugin(), CompanionConfig.load(runtime.plugin(), yaml), runtime.store(), runtime.sessions(),
                runtime.bodies(), visual, runtime.petKey(), runtime.toyKey());
        var ticker = new net.tfminecraft.companionpets.visual.PetVisualTicker(updated);
        var body = (FetchWolf) runtime.entity(pet);
        body.clock.progress = .05f; body.clock.isWet = true; ticker.run();
        assertEquals(1, splashes);
        body.clock.progress = 1.7f; ticker.run(); assertEquals(2, splashes);
        body.clock.progress = 0; body.clock.isWet = false; ticker.run(); assertEquals(2, splashes);
        pet.activity(Activity.GREETING); body.clock.isWet = true; ticker.run();
        pet.activity(Activity.NONE); ticker.run();
        assertFalse(body.clock.isWet); assertEquals(2, splashes);
    }
    private ServerMock server;
    private PetRuntime runtime;
    private PetActions actions;
    private PetListener listener;
    private PlayerMock owner;
    private Pet pet;
    private WorldMock world;
    private ItemStack toy;
    private int splashes;
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
                                case "removeGoal" -> { goals.remove(id + ":" + (args[1] instanceof com.destroystokyo.paper.entity.ai.Goal<?> goal ? goal.getKey() : args[1])); yield null; }
                                default -> throw new AssertionError("Unexpected goals call: " + method.getName());
                            };
                        });
            }
        }); var plugin = MockBukkit.createMockPlugin();
        world = new net.tfminecraft.companionpets.testutil.CollisionWorldMock() {
            @Override public boolean isChunkLoaded(int x, int z) { return true; }
            @Override public void spawnParticle(org.bukkit.Particle particle, Location at, int count,
                    double x, double y, double z, double extra) { if (particle == org.bukkit.Particle.SPLASH) splashes++; }
            @Override public BlockMock getBlockAt(int x, int y, int z) {
                return new BlockMock(y == 63 ? Material.STONE : Material.AIR, new Location(this, x, y, z)) { @Override public boolean isPassable() { return y != 63; } };
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
            int navigationRequests;
            final NativeClock clock = new NativeClock();
            public FetchWolf(ServerMock server, UUID id, java.util.Map<UUID, Location> navigationTargets,
                    java.util.Map<UUID, Double> navigationSpeeds) {
                super(server, id);
                pathfinder =
                    (com.destroystokyo.paper.entity.Pathfinder) java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(), new Class<?>[]{com.destroystokyo.paper.entity.Pathfinder.class},
                            (proxy, method, args) -> switch (method.getName()) {
                                case "moveTo" -> {
                                    navigationRequests++;
                                    Location target = args[0] instanceof Location at ? at
                                            : ((com.destroystokyo.paper.entity.Pathfinder.PathResult) args[0]).getFinalPoint();
                                    navigationTargets.put(getUniqueId(), target.clone());
                                    navigationSpeeds.put(getUniqueId(), ((Number) args[1]).doubleValue()); yield true;
                                }
                                case "stopPathfinding" -> { navigationTargets.remove(getUniqueId()); yield null; }
                                case "hasPath" -> navigationTargets.containsKey(getUniqueId());
                                case "getEntity" -> this;
                                case "findPath" -> (com.destroystokyo.paper.entity.Pathfinder.PathResult) java.lang.reflect.Proxy.newProxyInstance(
                                        getClass().getClassLoader(), new Class<?>[]{com.destroystokyo.paper.entity.Pathfinder.PathResult.class},
                                        (p, m, a) -> switch (m.getName()) {
                                            case "canReachFinalPoint" -> true;
                                            case "getFinalPoint" -> args[0];
                                            default -> null;
                                        });
                                default -> throw new AssertionError("Unexpected navigation call: " + method.getName());
                            });
            }
            private final com.destroystokyo.paper.entity.Pathfinder pathfinder;
            private float bodyYaw;
            @Override public float getBodyYaw() { return bodyYaw; }
            @Override public void setBodyYaw(float yaw) { bodyYaw = yaw; }
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

    @Test void oneConfiguredPaceAppliesToCarrierAndFollowersWithoutFavoriteBonus() {
        var yaml = new YamlConfiguration();
        yaml.set("items.toys", java.util.List.of("STICK")); yaml.set("pets.wolf.entity", "WOLF");
        yaml.set("pets.wolf.egg", "WOLF_SPAWN_EGG");
        yaml.set("play.fetch-speed-multiplier", 1.1);
        runtime.config(CompanionConfig.load(runtime.plugin(), yaml));
        Pet other = outsidePet(owner.getUniqueId(), "Luna", 2);
        pet.bond(50); other.bond(50); other.favoriteToy("STICK");
        double base = net.tfminecraft.companionpets.behavior.Locomotion.speed(pet.illness(), pet.bond(), pet.need(Need.CLEANLINESS), false);
        Snowball ball = throwToy();
        assertEquals(base * 1.1, navigationSpeeds.get(pet.entityId()), 0.0001);
        assertEquals(base * 1.1, navigationSpeeds.get(other.entityId()), 0.0001);
        land(ball); items().getFirst().teleport(new Location(world, 12, 64, 0));
        var carrier = (org.bukkit.entity.Mob) runtime.entity(pet);
        var follower = (org.bukkit.entity.Mob) runtime.entity(other);
        carrier.teleport(items().getFirst().getLocation()); assertTrue(actions.fetchActions().claim(pet));
        pet.bond(100); other.bond(0);
        actions.fetchActions().step(pet, carrier); actions.fetchActions().step(other, follower);
        assertEquals(base * 1.1, actions.fetchActions().movementSpeed(pet), 0.0001);
        assertEquals(base * 1.1, actions.fetchActions().movementSpeed(other), 0.0001);
        follower.teleport(new Location(world, 8, 64, 0)); carrier.teleport(owner.getLocation());
        actions.fetchActions().step(pet, carrier);
        assertEquals(Activity.ATTENDING, other.activity());
        assertEquals(base * 1.1, actions.roaming().movementSpeed(other), 0.0001);
    }

    @Test void fetchRefreshesRoutesAtItsCadenceButPicksUpAndDeliversImmediately() {
        Snowball ball = throwToy();
        var body = (FetchWolf) runtime.entity(pet);
        int requests = body.navigationRequests;
        long now = System.currentTimeMillis();
        for (int i = 0; i < 20; i++) actions.fetchActions().step(pet, body, now + i);
        assertEquals(requests, body.navigationRequests);
        actions.fetchActions().step(pet, body, now + 300);
        assertEquals(requests + 1, body.navigationRequests);
        land(ball); actions.fetchActions().step(pet, body, now + 301);
        assertEquals(items().getFirst().getLocation(), navigationTargets.get(body.getUniqueId()), "Landing redirects without waiting");
        body.teleport(items().getFirst().getLocation()); actions.fetchActions().step(pet, body, now + 302);
        assertEquals(FetchPhase.CARRY, pet.fetch().phase()); assertTrue(items().isEmpty());
        body.teleport(owner.getLocation()); actions.fetchActions().step(pet, body, now + 303);
        assertNull(pet.fetch()); assertEquals(1, items().size());
    }

    @Test void throwingTheWatchedToyReleasesAttentionAndStartsNormalFetch() {
        owner.getInventory().setItemInMainHand(toy.clone());
        actions.anticipation().tick(System.currentTimeMillis());
        assertTrue(actions.anticipation().active(pet)); assertEquals(Activity.TOY_FOCUS, pet.activity());
        Snowball ball = throwToy();
        assertNotNull(ball); assertNotNull(pet.fetch()); assertEquals(Activity.PLAYING, pet.activity());
        assertFalse(actions.anticipation().active(pet)); assertTrue(navigationTargets.containsKey(pet.entityId()));
        assertFalse(server.getMobGoals().getGoal((org.bukkit.entity.Mob) runtime.entity(pet),
                com.destroystokyo.paper.entity.ai.GoalKey.of(org.bukkit.entity.Mob.class,
                        new NamespacedKey(runtime.plugin(), "toy_navigation"))).shouldActivate());
    }

    @Test void favoriteToyGivesMoreMoodButNoExtraOutboundSpeed() {
        Pet other = outsidePet(owner.getUniqueId(), "Luna", 2);
        pet.bond(50); other.bond(50); pet.favoriteToy(null); other.favoriteToy("STICK");
        pet.need(Need.MOOD, 10); other.need(Need.MOOD, 10); Snowball ball = throwToy();
        assertEquals(navigationSpeeds.get(pet.entityId()), navigationSpeeds.get(other.entityId()));
        assertFalse(pet.fetch().favorite(pet.id())); assertTrue(other.fetch().favorite(other.id()));
        land(ball); var item = items().getFirst();
        runtime.entity(other).teleport(item.getLocation()); assertTrue(actions.fetchActions().claim(other));
        runtime.entity(other).teleport(owner.getLocation()); actions.fetchActions().step(other, (org.bukkit.entity.Mob) runtime.entity(other));
        assertEquals(10 + runtime.config().care().playMoodGain() * runtime.config().care().favoriteMoodMultiplier(), other.need(Need.MOOD));
    }

    @Test void configurableCatPlayInspectsBeforeCollectingRatherThanChangingSpeed() {
        var yaml = new YamlConfiguration();
        yaml.set("items.toys", java.util.List.of("STICK")); yaml.set("pets.wolf.entity", "WOLF");
        yaml.set("pets.wolf.egg", "WOLF_SPAWN_EGG"); yaml.set("pets.wolf.behaviors", java.util.List.of("fetch", "cat-play"));
        runtime.config(CompanionConfig.load(runtime.plugin(), yaml));
        Snowball ball = throwToy(); double speed = navigationSpeeds.get(pet.entityId()); land(ball);
        var body = (org.bukkit.entity.Mob) runtime.entity(pet); body.teleport(items().getFirst().getLocation());
        var job = pet.fetch(); actions.fetchActions().step(pet, body, 1000);
        assertEquals(FetchPhase.GROUND, job.phase()); assertNull(navigationTargets.get(pet.entityId()));
        actions.fetchActions().step(pet, body, 1499); assertEquals(FetchPhase.GROUND, job.phase());
        actions.fetchActions().step(pet, body, 1500); assertEquals(FetchPhase.CARRY, job.phase());
        assertEquals(speed, actions.fetchActions().movementSpeed(pet));
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
        assertEquals(other.id(), job.carrierId()); assertSame(job, pet.fetch()); assertTrue(items().isEmpty());
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
        assertSame(other.fetch(), pet.fetch());
    }

    @Test void wetWolfKeepsFetchingAndFinishesDryWithoutAnotherShake() {
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
        assertNull(pet.fetch()); assertFalse(body.clock.isWet);
        assertEquals(1, items().size()); assertTrue(toy.isSimilar(items().getFirst().getItemStack()));
        body.clock.isWet = false;
        net.tfminecraft.companionpets.integration.WolfShake.restore(body);
        assertFalse(body.clock.isWet);
    }

    @Test void losingPetFollowsMovingCarrierThenFinishesItsReturnWithoutTeleport() {
        Pet other = outsidePet(owner.getUniqueId(), "Luna", 2);
        land(throwToy());
        Location far = new Location(world, 30, 64, 0);
        items().getFirst().teleport(far);
        var winner = (FetchWolf) runtime.entity(pet);
        var loser = (FetchWolf) runtime.entity(other);
        winner.teleport(far); loser.teleport(far.clone().add(0, 0, 1));
        assertTrue(actions.fetchActions().claim(pet));
        assertSame(pet.fetch(), other.fetch()); assertEquals(Activity.PLAYING, other.activity());
        assertTrue(navigationTargets.get(loser.getUniqueId()).distance(winner.getLocation()) < 3);
        assertTrue(navigationTargets.get(loser.getUniqueId()).distance(owner.getLocation()) > 25);
        var teleport = new org.bukkit.event.entity.EntityTeleportEvent(loser, loser.getLocation(), owner.getLocation());
        listener.onTeleport(teleport); assertTrue(teleport.isCancelled());
        var goal = server.getMobGoals().getGoal(loser, com.destroystokyo.paper.entity.ai.GoalKey.of(
                org.bukkit.entity.Mob.class, new NamespacedKey(runtime.plugin(), "fetch_navigation")));
        assertNotNull(goal); assertTrue(goal.shouldActivate());
        winner.teleport(new Location(world, 20, 64, 4)); goal.tick();
        assertTrue(navigationTargets.get(loser.getUniqueId()).distance(winner.getLocation()) < 3);
        assertTrue(navigationTargets.get(loser.getUniqueId()).distance(owner.getLocation()) > 18);
        Location before = loser.getLocation();
        winner.teleport(owner.getLocation()); actions.fetchActions().step(pet, winner);
        assertNull(pet.fetch()); assertEquals(Activity.ATTENDING, other.activity());
        assertNull(other.fetch()); assertFalse(goal.shouldActivate());
        goal = server.getMobGoals().getGoal(loser, com.destroystokyo.paper.entity.ai.GoalKey.of(
                org.bukkit.entity.Mob.class, new NamespacedKey(runtime.plugin(), "call_navigation")));
        assertNotNull(goal);
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
        assertTrue(loser.getVelocity().getX() > 0, "The loser swims toward the carrier, not directly back to the thrower");
        actions.fetchActions().step(pet, winner);
        assertEquals(winnerOutbound, navigationSpeeds.get(winner.getUniqueId()));
        winner.inWater = true; new PetTicker(runtime, actions).run();
        var winnerWater = server.getMobGoals().getGoal(winner, com.destroystokyo.paper.entity.ai.GoalKey.of(
                org.bukkit.entity.Mob.class, new NamespacedKey(runtime.plugin(), "water_navigation")));
        winnerWater.start(); assertEquals(winnerOutbound, navigationSpeeds.get(winner.getUniqueId()));
    }

    @Test void losingPetReturnExpiresWithoutTeleportingWhenOwnerIsUnreachable() {
        var body = (FetchWolf) runtime.entity(pet);
        body.teleport(new Location(world, 30, 64, 0));
        var before = body.getLocation();
        long now = System.currentTimeMillis();
        actions.roaming().returnFromFetch(pet, owner, 1.3);
        assertTrue(actions.roaming().returningFromFetch(pet));
        actions.roaming().tickAttention(pet, body, now + 121_000);
        assertFalse(actions.roaming().returningFromFetch(pet));
        assertEquals(Activity.NONE, pet.activity()); assertEquals(PetOrder.FOLLOW, pet.order());
        assertEquals(before, body.getLocation());
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

    @Test void losingPetFollowsTheCarrierWhileSwimming() {
        Pet other = outsidePet(owner.getUniqueId(), "Luna", 2);
        land(throwToy());
        var body = (FetchWolf) runtime.entity(other);
        body.teleport(new Location(world, 4, 64, 0)); body.inWater = true;
        runtime.entity(pet).teleport(items().getFirst().getLocation());
        assertTrue(actions.fetchActions().claim(pet));
        new PetTicker(runtime, actions).run();
        assertEquals(Activity.PLAYING, other.activity()); assertSame(pet.fetch(), other.fetch());
        var goal = server.getMobGoals().getGoal(body, com.destroystokyo.paper.entity.ai.GoalKey.of(
                org.bukkit.entity.Mob.class, new NamespacedKey(runtime.plugin(), "water_navigation")));
        goal.start();
        assertEquals(actions.fetchActions().destination(other), navigationTargets.get(body.getUniqueId()));
        assertTrue(body.getVelocity().getX() > 0); assertEquals(Activity.PLAYING, other.activity());
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
        assertEquals(PetOrder.STAY, staying.order()); var stayingBody = (WolfMock) runtime.entity(staying);
        assertTrue(stayingBody.isAware(), "Staying keeps native AI awake");
        assertTrue(server.getMobGoals().getGoal(stayingBody, com.destroystokyo.paper.entity.ai.GoalKey.of(
                org.bukkit.entity.Mob.class, new org.bukkit.NamespacedKey(runtime.plugin(), "posture_navigation"))).shouldActivate());
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
        assertEquals(FetchPhase.CARRY, job.phase()); assertSame(job, pet.fetch());
        runtime.entity(other).teleport(owner.getLocation());
        actions.fetchActions().step(other, (org.bukkit.entity.Mob) runtime.entity(other));
        assertNull(other.fetch()); assertNull(pet.fetch()); assertEquals(1, items().size());
        assertEquals(10, pet.need(Need.MOOD)); assertEquals(43, other.need(Need.MOOD));
        assertTrue(items().getFirst().getLocation().distance(owner.getLocation()) < 3);
    }

    @Test void multipleFollowersHaveSeparateCarrierRoutesAndLeavingDoesNotStealOrDuplicateToy() {
        Pet first = outsidePet(owner.getUniqueId(), "Luna", 2);
        Pet second = outsidePet(owner.getUniqueId(), "Sol", -2);
        land(throwToy()); var job = pet.fetch();
        var carrier = (org.bukkit.entity.Mob) runtime.entity(pet);
        carrier.teleport(items().getFirst().getLocation()); assertTrue(actions.fetchActions().claim(pet));
        Location firstAt = actions.fetchActions().destination(first), secondAt = actions.fetchActions().destination(second);
        assertTrue(firstAt.distance(secondAt) >= 1.2);
        assertTrue(firstAt.distance(carrier.getLocation()) < 3); assertTrue(secondAt.distance(carrier.getLocation()) < 3);
        assertSame(job, first.fetch()); assertSame(job, second.fetch()); assertTrue(items().isEmpty());
        actions.releaseFetch(first, owner, true);
        assertNull(first.fetch()); assertSame(job, pet.fetch()); assertSame(job, second.fetch());
        assertEquals(pet.id(), job.carrierId()); assertTrue(items().isEmpty());
        carrier.teleport(owner.getLocation()); actions.fetchActions().step(pet, carrier);
        assertNull(second.fetch()); assertNull(pet.fetch()); assertEquals(1, items().size());
        assertTrue(toy.isSimilar(items().getFirst().getItemStack()));
        actions.releaseFetch(second, owner, true); assertEquals(1, items().size());
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
