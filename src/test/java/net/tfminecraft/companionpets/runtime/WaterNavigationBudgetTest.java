package net.tfminecraft.companionpets.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.entity.ai.GoalKey;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.testutil.GoalServerMock;
import net.tfminecraft.companionpets.visual.PetVisual;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class WaterNavigationBudgetTest {
    private static final long NOW = 1_000_000L;
    private GoalServerMock server;
    private WorldMock world;
    private PlayerMock owner;
    private WolfMock body;
    private Pet pet;
    private PetRuntime runtime;
    private PetActions actions;
    private WaterNavigationGoal goal;
    private Pathfinder path;
    private Pathfinder.PathResult result;
    private int blockReads, searches, moves, stops;
    private boolean swimming = true, grounded = true, nativeRoute, hasPath;
    private boolean blocked = true, floor = true, nullPath;
    private double clearFromX = Double.POSITIVE_INFINITY;
    private Location routedTo;
    private Location waterTarget;

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(new GoalServerMock());
        world = new net.tfminecraft.companionpets.testutil.CollisionWorldMock() {
            @Override public boolean isChunkLoaded(int x, int z) { return true; }
            @Override public BlockMock getBlockAt(int x, int y, int z) {
                blockReads++;
                return new BlockMock(new Location(this, x, y, z)) {
                    @Override public Material getType() {
                        if (waterTarget != null && x == waterTarget.getBlockX() && y == waterTarget.getBlockY()
                                && z == waterTarget.getBlockZ()) return Material.WATER;
                        return floor && y == 63 ? Material.STONE : Material.AIR;
                    }
                    @Override public boolean isPassable() { return getType().isAir() || isLiquid(); }
                    @Override public boolean isLiquid() { return getType() == Material.WATER; }
                    @Override public Block getRelative(int dx, int dy, int dz) { return world.getBlockAt(x + dx, y + dy, z + dz); }
                };
            }
            @Override public RayTraceResult rayTraceBlocks(Location from, Vector direction, double distance,
                    FluidCollisionMode fluid, boolean ignorePassable) {
                double targetX = from.getX() + direction.getX() * distance;
                return blocked && targetX < clearFromX ? new RayTraceResult(from.toVector()) : null;
            }
        };
        server.addWorld(world);
        owner = server.addPlayer();
        owner.teleport(new Location(world, 6.5, 64, 2.5));
        var plugin = MockBukkit.createMockPlugin();
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
                pets: {wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG, default-tricks: [], sounds: false}}
                moments: {enabled: false}
                social: {enabled: false}
                """);
        var visual = new PetVisual() { @Override public void apply(Entity entity, PetTypeDef type) { } };
        var key = new NamespacedKey(plugin, "pet");
        var store = new PetStore(plugin); assertTrue(store.load());
        runtime = new PetRuntime(plugin, CompanionConfig.load(plugin, yaml), store, new Sessions(),
                new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        actions = new PetActions(runtime);
        pet = new Pet(UUID.randomUUID(), owner.getUniqueId(), "wolf", "Toby", PetSex.MALE);
        pet.order(PetOrder.SIT);
        store.add(pet);
        path = mock(Pathfinder.class); result = mock(Pathfinder.PathResult.class);
        when(result.canReachFinalPoint()).thenAnswer(invocation -> nativeRoute);
        when(path.findPath(any(Location.class))).thenAnswer(invocation -> {
            searches++; routedTo = invocation.getArgument(0, Location.class).clone();
            return nullPath ? null : result;
        });
        when(path.hasPath()).thenAnswer(invocation -> hasPath);
        when(path.getCurrentPath()).thenAnswer(invocation -> hasPath ? result : null);
        when(result.getFinalPoint()).thenAnswer(invocation -> routedTo);
        when(path.moveTo(any(Pathfinder.PathResult.class), anyDouble())).thenAnswer(invocation -> {
            moves++; hasPath = true; return true;
        });
        doAnswer(invocation -> { stops++; hasPath = false; return null; }).when(path).stopPathfinding();
        body = new WolfMock(server, UUID.randomUUID()) {
            @Override public boolean isInWater() { return swimming; }
            @Override public boolean isOnGround() { return grounded; }
            @Override public float getBodyYaw() { return 0; }
            @Override public Pathfinder getPathfinder() { return path; }
        };
        server.registerEntity(body);
        body.teleport(new Location(world, 2.5, 64, 2.5));
        runtime.remember(pet, body);
        WaterNavigationGoal.ensure(runtime, pet, body, actions);
        goal = (WaterNavigationGoal) server.getMobGoals().getGoal(body,
                GoalKey.of(Mob.class, new NamespacedKey(plugin, "water_navigation")));
    }

    @AfterEach void cleanup() {
        if (actions != null) actions.holograms().clear();
        if (runtime != null) runtime.store().close();
        MockBukkit.unmock();
    }

    @Test void followYieldsAtTwentyFiveSecondsWithoutAnExitAndStopsSearching() {
        pet.order(PetOrder.FOLLOW);
        goal.tick(NOW);
        assertTrue(goal.shouldActivate(NOW + WaterNavigationGoal.STUCK_MILLIS - 1));
        assertFalse(goal.shouldActivate(NOW + WaterNavigationGoal.STUCK_MILLIS));
        int reads = blockReads, before = searches;
        goal.tick(NOW + WaterNavigationGoal.STUCK_MILLIS + 500);
        assertEquals(reads, blockReads); assertEquals(before, searches);
        assertEquals(Activity.NONE, pet.activity()); assertEquals(PetOrder.FOLLOW, pet.order());
        assertFalse(body.isSitting()); assertTrue(body.isAware());
        assertNativeTeleportAllowed();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = PetOrder.class, names = {"STAY", "SIT", "LAY"})
    void heldOrdersNeverYieldDespiteLongFailedSearches(PetOrder order) {
        pet.order(order);
        goal.tick(NOW); goal.tick(NOW + WaterNavigationGoal.STUCK_MILLIS * 2);
        assertTrue(goal.shouldActivate(NOW + WaterNavigationGoal.STUCK_MILLIS * 3));
        assertEquals(order, pet.order()); assertTrue(body.getVelocity().getY() > 0);
        pet.order(PetOrder.FOLLOW); pet.staying(true);
        goal.tick(NOW + WaterNavigationGoal.STUCK_MILLIS * 4);
        assertTrue(goal.shouldActivate(NOW + WaterNavigationGoal.STUCK_MILLIS * 5));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void unavailableOwnerPreventsYieldAndRestartsTheDeadline(boolean offline) {
        pet.order(PetOrder.FOLLOW); goal.tick(NOW);
        assertFalse(goal.shouldActivate(NOW + WaterNavigationGoal.STUCK_MILLIS));
        Location home = owner.getLocation();
        if (offline) owner.disconnect();
        else owner.teleport(new Location(server.addSimpleWorld("other"), 0, 64, 0));
        assertTrue(goal.shouldActivate(NOW + 26_000));
        goal.tick(NOW + 60_000); assertTrue(goal.shouldActivate(NOW + 90_000));
        if (offline) owner.reconnect(); else owner.teleport(home);
        goal.tick(NOW + 100_000);
        assertTrue(goal.shouldActivate(NOW + 124_999));
        assertFalse(goal.shouldActivate(NOW + 125_000));
    }

    @Test void leavingWaterRestartsAStuckFollowerOnItsNextEntry() {
        pet.order(PetOrder.FOLLOW); goal.tick(NOW);
        assertFalse(goal.shouldActivate(NOW + 25_000));
        swimming = false; assertFalse(goal.shouldActivate(NOW + 26_000)); goal.stop();
        swimming = true; goal.tick(NOW + 27_000);
        assertTrue(goal.shouldActivate(NOW + 51_999));
        assertFalse(goal.shouldActivate(NOW + 52_000));
    }

    @Test void findingAnExitRestartsTheDeadlineIfThatRouteLaterFails() {
        pet.order(PetOrder.FOLLOW); goal.tick(NOW);
        nativeRoute = true; goal.tick(NOW + 20_000);
        assertTrue(goal.shouldActivate(NOW + 45_000), "A known exit prevents surrender");
        nativeRoute = false; hasPath = false; goal.tick(NOW + 46_000);
        assertTrue(goal.shouldActivate(NOW + 70_999));
        assertFalse(goal.shouldActivate(NOW + 71_000));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = PetOrder.class, names = {"STAY", "SIT", "LAY"})
    void changingOrderClearsTheStuckStateAndRestartsFollow(PetOrder order) {
        pet.order(PetOrder.FOLLOW); goal.tick(NOW);
        assertFalse(goal.shouldActivate(NOW + 25_000));
        pet.order(order); assertTrue(goal.shouldActivate(NOW + 26_000));
        pet.order(PetOrder.FOLLOW); goal.tick(NOW + 27_000);
        assertTrue(goal.shouldActivate(NOW + 51_999));
        assertFalse(goal.shouldActivate(NOW + 52_000));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void losingOrChangingTheBodyForgetsItsStuckState(boolean missing) {
        pet.order(PetOrder.FOLLOW); goal.tick(NOW);
        assertFalse(goal.shouldActivate(NOW + 25_000));
        UUID original = pet.entityId();
        if (missing) pet.entityId(null); else pet.entityId(UUID.randomUUID());
        runtime.forgetMissingGround(); assertNull(runtime.waterGoal(body));
        assertFalse(goal.shouldActivate(NOW + 26_000));
        pet.entityId(original); WaterNavigationGoal.ensure(runtime, pet, body, actions);
        goal.tick(NOW + 27_000);
        assertTrue(goal.shouldActivate(NOW + 51_999));
        assertFalse(goal.shouldActivate(NOW + 52_000));
    }

    @Test void chunkUnloadForgetsStuckStateEvenBeforeTheBodyBecomesInvalid() {
        pet.order(PetOrder.FOLLOW); goal.tick(NOW);
        assertFalse(goal.shouldActivate(NOW + 25_000));
        new net.tfminecraft.companionpets.listen.PetListener(runtime, actions).onUnload(
                new org.bukkit.event.world.EntitiesUnloadEvent(body.getLocation().getChunk(), java.util.List.of(body)));
        assertNull(runtime.waterGoal(body));
        WaterNavigationGoal.ensure(runtime, pet, body, actions);
        goal.tick(NOW + 27_000);
        assertTrue(goal.shouldActivate(NOW + 51_999));
        assertFalse(goal.shouldActivate(NOW + 52_000));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void surrenderCancelsCallsAndFetchReturnsWithoutRestoringTheirOldPosture(boolean fetchReturn) {
        pet.order(PetOrder.FOLLOW);
        if (fetchReturn) actions.roaming().returnFromFetch(pet, owner, 1.8);
        else actions.roaming().come(pet, owner, NOW, PetOrder.STAY);
        assertEquals(Activity.ATTENDING, pet.activity());
        goal.tick(NOW); assertFalse(goal.shouldActivate(NOW + 25_000));
        assertFalse(actions.fetchingOrReturning(pet)); assertNull(actions.roaming().destination(pet));
        assertEquals(Activity.NONE, pet.activity()); assertEquals(PetOrder.FOLLOW, pet.order());
        assertNoPluginMovement(); assertNativeTeleportAllowed();
    }

    @Test void surrenderReleasesSleepingAndForcedSittingAndEveryRegisteredMovementGoal() {
        pet.order(PetOrder.FOLLOW); pet.activity(Activity.SLEEPING);
        pet.forcedSitUntilMillis(Long.MAX_VALUE); body.setSitting(true);
        PostureNavigationGoal.hold(runtime, pet, body);
        FetchNavigationGoal.ensure(runtime, pet, body, () -> fail("Fetch must not run"));
        CallNavigationGoal.ensure(runtime, pet, body, () -> fail("Call must not run"));
        PlayNavigationGoal.ensure(runtime, pet, body, () -> fail("Play must not run"));
        TrainingNavigationGoal.begin(runtime, pet, body, owner);
        SocialNavigationGoal.ensure(runtime, pet, body, actions.social());
        ToyNavigationGoal.ensure(runtime, pet, body, actions.anticipation());
        goal.tick(NOW); assertFalse(goal.shouldActivate(NOW + 25_000));
        assertEquals(Activity.NONE, pet.activity()); assertEquals(0, pet.forcedSitUntilMillis());
        new PetTicker(runtime, actions).run();
        assertFalse(body.isSitting()); assertNoPluginMovement(); assertNativeTeleportAllowed();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"AIR,false,false", "GROUND,false,false", "CARRY,false,false",
            "AIR,true,false", "GROUND,true,false", "CARRY,true,false", "CARRY,true,true"})
    void surrenderPreservesExactlyOneToyThroughEachFetchPhaseAndThrower(
            net.tfminecraft.companionpets.play.FetchPhase phase, boolean foreign, boolean offlineThrower) {
        pet.order(PetOrder.FOLLOW); goal.tick(NOW);
        var thrower = foreign ? server.addPlayer() : owner;
        if (foreign) thrower.teleport(owner.getLocation().add(3, 0, 0));
        var toy = new org.bukkit.inventory.ItemStack(Material.STICK);
        var meta = toy.getItemMeta(); meta.displayName(net.kyori.adventure.text.Component.text("Water toy")); toy.setItemMeta(meta);
        var job = new net.tfminecraft.companionpets.play.FetchJob(
                net.tfminecraft.companionpets.item.ToyItems.encode(toy), thrower.getUniqueId());
        pet.fetch(job); job.join(pet.id()); pet.activity(Activity.PLAYING);
        var ball = world.spawn(body.getLocation().add(3, 0, 0), org.bukkit.entity.Snowball.class);
        ball.getPersistentDataContainer().set(runtime.toyKey(), org.bukkit.persistence.PersistentDataType.STRING, job.id().toString());
        actions.fetchActions().register(job, ball);
        if (phase != net.tfminecraft.companionpets.play.FetchPhase.AIR) {
            actions.toyLanded(ball);
            if (phase == net.tfminecraft.companionpets.play.FetchPhase.CARRY) {
                plainToys().getFirst().teleport(body.getLocation());
                assertTrue(actions.fetchActions().claim(pet));
            }
        }
        FetchNavigationGoal.ensure(runtime, pet, body, () -> actions.fetchActions().step(pet, body));
        if (offlineThrower) thrower.disconnect();
        assertTrue(goal.shouldActivate(NOW + 24_999)); assertSame(job, pet.fetch());
        assertFalse(goal.shouldActivate(NOW + 25_000));
        assertNull(pet.fetch()); assertNull(pet.carriedToy()); assertNoPluginMovement(); assertNativeTeleportAllowed();
        if (phase == net.tfminecraft.companionpets.play.FetchPhase.AIR) {
            assertTrue(ball.isValid()); assertTrue(plainToys().isEmpty());
            actions.toyLanded(ball);
        }
        assertEquals(1, plainToys().size());
        var dropped = plainToys().getFirst();
        assertTrue(toy.isSimilar(dropped.getItemStack())); assertEquals(0, dropped.getPickupDelay());
        assertFalse(dropped.getPersistentDataContainer().has(runtime.toyKey()));
        if (phase == net.tfminecraft.companionpets.play.FetchPhase.CARRY)
            assertTrue(dropped.getLocation().distance(offlineThrower ? body.getLocation() : thrower.getLocation()) < 3);
        actions.releaseFetch(pet, owner, true); actions.toyLanded(ball);
        actions.fetchActions().tick(System.currentTimeMillis() + 120_000);
        assertEquals(1, plainToys().size(), "Repeated release and expiry cannot duplicate the toy");
    }

    @Test void surrenderingOneSharedChaserLeavesTheToyForTheOtherAndLastReleaseUnprotectsIt() {
        pet.order(PetOrder.FOLLOW); goal.tick(NOW);
        var job = new net.tfminecraft.companionpets.play.FetchJob("STICK", owner.getUniqueId());
        pet.fetch(job); job.join(pet.id()); pet.activity(Activity.PLAYING);
        var other = new Pet(UUID.randomUUID(), owner.getUniqueId(), "wolf", "Luna", PetSex.FEMALE);
        runtime.store().add(other); other.fetch(job); job.join(other.id()); other.activity(Activity.PLAYING);
        var ball = world.spawn(body.getLocation().add(3, 0, 0), org.bukkit.entity.Snowball.class);
        ball.getPersistentDataContainer().set(runtime.toyKey(), org.bukkit.persistence.PersistentDataType.STRING, job.id().toString());
        actions.fetchActions().register(job, ball); actions.toyLanded(ball);
        assertFalse(goal.shouldActivate(NOW + 25_000));
        assertNull(pet.fetch()); assertSame(job, other.fetch()); assertEquals(1, plainToys().size());
        assertTrue(plainToys().getFirst().getPersistentDataContainer().has(runtime.toyKey()));
        actions.releaseFetch(other, owner, true);
        assertEquals(1, plainToys().size()); assertEquals(0, plainToys().getFirst().getPickupDelay());
        assertFalse(plainToys().getFirst().getPersistentDataContainer().has(runtime.toyKey()));
    }

    @Test void aReplacementBodyStartsWithAFreshWaterDeadline() {
        pet.order(PetOrder.FOLLOW); goal.tick(NOW);
        assertFalse(goal.shouldActivate(NOW + 25_000));
        var replacement = new WolfMock(server, UUID.randomUUID()) {
            @Override public boolean isInWater() { return swimming; }
            @Override public com.destroystokyo.paper.entity.Pathfinder getPathfinder() { return path; }
        };
        server.registerEntity(replacement); replacement.teleport(body.getLocation());
        runtime.remember(pet, replacement); runtime.forgetMissingGround();
        assertNull(runtime.waterGoal(body)); assertFalse(goal.shouldActivate(NOW + 26_000));
        WaterNavigationGoal.ensure(runtime, pet, replacement, actions);
        var fresh = runtime.waterGoal(replacement); assertNotSame(goal, fresh);
        fresh.tick(NOW + 27_000);
        assertTrue(fresh.shouldActivate(NOW + 51_999));
        assertFalse(fresh.shouldActivate(NOW + 52_000));
    }

    private java.util.List<org.bukkit.entity.Item> plainToys() {
        return world.getEntities().stream().filter(org.bukkit.entity.Item.class::isInstance)
                .map(org.bukkit.entity.Item.class::cast).toList();
    }

    private void assertNoPluginMovement() {
        for (var registered : server.getMobGoals().getAllGoals(body)) {
            if (registered.getTypes().contains(com.destroystokyo.paper.entity.ai.GoalType.MOVE)) {
                assertFalse(registered.shouldActivate(), registered.getKey().toString());
                assertFalse(registered.shouldStayActive(), registered.getKey().toString());
            }
        }
    }

    private void assertNativeTeleportAllowed() {
        var event = new org.bukkit.event.entity.EntityTeleportEvent(body, body.getLocation(), owner.getLocation());
        new net.tfminecraft.companionpets.listen.PetListener(runtime, actions).onTeleport(event);
        assertFalse(event.isCancelled());
    }

    @Test void shoreRouteChecksUseTheRememberedGoalAndForgetRemovedBodies() {
        swimming = false;
        runtime.rememberGround(pet, body);
        body.teleport(body.getLocation().add(1.5, 0, 0));
        swimming = true; nativeRoute = true;
        goal.tick(NOW);
        swimming = false;
        int lookups = server.goalLookups();
        for (int tick = 0; tick < 20; tick++) assertTrue(WaterNavigationGoal.finishing(runtime, body));
        hasPath = false;
        assertFalse(WaterNavigationGoal.finishing(runtime, body));
        assertEquals(lookups, server.goalLookups());
        assertSame(goal, runtime.waterGoal(body));
        body.remove();
        runtime.forgetMissingGround();
        assertNull(runtime.waterGoal(body));
        assertFalse(WaterNavigationGoal.finishing(runtime, body));
    }

    @Test void walledCanalBudgetsAllPathsAndDoesNotScanAgainDuringBackoff() {
        goal.tick(NOW);
        assertEquals(WaterNavigationGoal.PATH_SEARCH_BUDGET, searches);
        assertTrue(blockReads > 1_000);
        int reads = blockReads;
        goal.tick(NOW + 20); goal.tick(NOW + 500);
        assertEquals(reads, blockReads);
        assertEquals(4, searches);
        assertEquals(0, moves);
        assertTrue(body.getVelocity().getY() > 0);
        goal.tick(NOW + 1_000);
        assertEquals(8, searches);
        assertTrue(blockReads > reads);
        reads = blockReads;
        goal.tick(NOW + 1_500); goal.tick(NOW + 2_000); goal.tick(NOW + 2_500);
        assertEquals(reads, blockReads);
        body.teleport(body.getLocation().add(3, 0, 0));
        goal.tick(NOW + 3_000);
        assertEquals(12, searches);
        goal.tick(NOW + 4_000);
        assertEquals(16, searches, "Moving two blocks resets the failed-search delay");
    }

    @Test void offlineBoatOwnerSharesTheBudgetAndRetriesWithACappedExponentialDelay() {
        pet.order(PetOrder.FOLLOW);
        owner.teleport(new Location(world, 80, 64, 0));
        owner.disconnect();
        long at = NOW;
        for (long delay : new long[]{1_000, 2_000, 4_000, 8_000, 16_000, 16_000}) {
            int before = searches;
            goal.tick(at);
            assertEquals(WaterNavigationGoal.PATH_SEARCH_BUDGET, searches - before);
            int reads = blockReads;
            for (long elapsed = 500; elapsed < delay; elapsed += 500) {
                owner.teleport(owner.getLocation().add(0.1, 0, 0));
                goal.tick(at + elapsed);
                assertEquals(reads, blockReads);
                assertEquals(before + 4, searches);
            }
            at += delay;
        }
        goal.tick(at);
        assertEquals(28, searches);
    }

    @Test void sittingPetSwimsBackToItsLastDryBlockWithoutScanning() {
        swimming = false;
        runtime.rememberGround(pet, body);
        Location ground = runtime.lastGround(pet);
        ground.add(20, 0, 0);
        assertEquals(2.5, runtime.lastGround(pet).getX(), "Saved locations are defensive copies");
        body.teleport(body.getLocation().add(1.5, 0, 0));
        swimming = true; nativeRoute = true;
        int reads = blockReads;
        goal.tick(NOW);
        assertEquals(1, searches);
        assertEquals(1, moves);
        assertEquals(runtime.lastGround(pet), routedTo);
        assertTrue(blockReads - reads < 20);
        assertEquals(PetOrder.SIT, pet.order());
        assertFalse(body.isSitting());
        assertTrue(body.getVelocity().getX() < 0);
        goal.tick(NOW + 500);
        assertEquals(1, searches);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void fetchSwimsTowardsTheToyInWaterOrAcrossItInsteadOfReturningToTheRememberedBank(boolean inWater) {
        swimming = false; runtime.rememberGround(pet, body);
        swimming = true; body.teleport(body.getLocation().add(1.5, 0, 0));
        var target = new Location(world, 12.5, 64, 2.5);
        if (inWater) waterTarget = target;
        blocked = false;
        pet.order(PetOrder.FOLLOW);
        pet.fetch(new net.tfminecraft.companionpets.play.FetchJob("STICK", owner.getUniqueId()));
        var fetch = mock(PetFetchActions.class);
        var waterActions = mock(PetActions.class);
        when(waterActions.fetchActions()).thenReturn(fetch);
        when(fetch.destination(pet)).thenReturn(target);
        when(fetch.movementSpeed(pet)).thenReturn(1.1);
        server.getMobGoals().removeGoal(body, goal);
        WaterNavigationGoal.ensure(runtime, pet, body, waterActions);
        goal = (WaterNavigationGoal) server.getMobGoals().getGoal(body,
                GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "water_navigation")));
        int reads = blockReads;
        goal.tick(NOW);
        assertEquals(target, routedTo);
        assertEquals(1, searches); assertEquals(0, moves);
        assertTrue(blockReads - reads < 20, "A reachable toy requires no bank scan");
        assertTrue(body.getVelocity().getX() > 0, "Swim away from the remembered bank towards the toy");
        body.teleport(body.getLocation().add(2, 0, 0));
        goal.tick(NOW + 500);
        assertEquals(1, searches);
        assertTrue(body.getVelocity().getX() > 0);
        verify(fetch, times(2)).step(pet, body);
    }

    @Test void tickerRemembersSittingPetsBeforeTheyFallIntoWaterAndKeepsTheirRoute() throws Exception {
        var ticker = new PetTicker(runtime, actions);
        var move = PetTicker.class.getDeclaredMethod("move", long.class);
        move.setAccessible(true);
        swimming = false;
        for (Need need : Need.values()) pet.need(need, 80);
        pet.bond(40); runtime.random().setSeed(0);
        move.invoke(ticker, NOW);
        assertNotNull(runtime.lastGround(pet));
        assertTrue(body.isSitting());
        swimming = true; nativeRoute = true;
        body.teleport(body.getLocation().add(1.5, 0, 0));
        move.invoke(ticker, NOW + 500);
        int reads = blockReads;
        goal.tick(NOW + 500);
        assertEquals(1, searches); assertEquals(1, moves);
        assertTrue(blockReads - reads < 20);
        int before = stops;
        move.invoke(ticker, NOW + 1_000); goal.tick(NOW + 1_000);
        assertEquals(1, searches); assertEquals(before, stops);
        assertEquals(PetOrder.SIT, pet.order());
        assertFalse(body.isSitting());
        var posture = server.getMobGoals().getGoal(body,
                GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "posture_navigation")));
        Location ground = runtime.lastGround(pet);
        body.teleport(ground.clone().add(0.8, 0, 0)); swimming = false;
        goal.stop();
        move.invoke(ticker, NOW + 1_500);
        assertFalse(posture.shouldActivate(), "The posture goal must not cancel the unfinished shore route");
        assertEquals(before, stops); assertTrue(hasPath);
        assertFalse(body.isSitting());
        body.teleport(ground); hasPath = false;
        move.invoke(ticker, NOW + 2_000);
        assertTrue(posture.shouldActivate());
        assertTrue(body.isSitting(), "The pet sits down once its native route ends on the bank");
    }

    @Test void comeInWaterRestoresTheLayOrderButSwimsToLandBeforeLyingDown() throws Exception {
        swimming = false;
        runtime.rememberGround(pet, body);
        Location ground = runtime.lastGround(pet);
        swimming = true; nativeRoute = true;
        body.teleport(body.getLocation().add(1.5, 0, 0));
        owner.teleport(new Location(world, 9.5, 64, 2.5));
        pet.order(PetOrder.FOLLOW);
        actions.roaming().come(pet, owner, NOW, PetOrder.LAY);
        goal.tick(NOW);
        assertEquals(owner.getLocation(), routedTo);
        body.teleport(owner.getLocation().add(-1.5, 0, 0));
        int reads = blockReads;
        goal.tick(NOW + 500);
        assertEquals(Activity.NONE, pet.activity());
        assertEquals(PetOrder.LAY, pet.order());
        assertFalse(body.isSitting(), "The restored pose must wait until the pet leaves the water");
        assertEquals(ground, routedTo);
        assertTrue(blockReads - reads < 20, "Reaching the player switches straight to the remembered bank");
        assertTrue(body.getVelocity().getX() < 0);
        assertTrue(body.getVelocity().getY() > 0);
        assertTrue(goal.shouldStayActive());
        body.teleport(ground); swimming = false; hasPath = false;
        assertFalse(goal.shouldStayActive());
        goal.stop();
        var ticker = new PetTicker(runtime, actions);
        var move = PetTicker.class.getDeclaredMethod("move", long.class);
        move.setAccessible(true);
        for (Need need : Need.values()) pet.need(need, 80);
        pet.bond(40); runtime.random().setSeed(0);
        move.invoke(ticker, NOW + 1_000);
        assertEquals(PetOrder.LAY, pet.order());
        assertTrue(body.isSitting(), "The wolf can finally apply its lying posture on dry ground");
    }

    @Test void preferredRouteIsReusedUntilTheOwnerMovesTwoBlocksAndSurvivesStop() {
        pet.order(PetOrder.FOLLOW); nativeRoute = true;
        goal.tick(NOW);
        assertEquals(owner.getLocation(), routedTo);
        assertEquals(1, searches); assertEquals(1, moves);
        owner.teleport(owner.getLocation().add(1, 0, 0));
        goal.tick(NOW + 500); goal.tick(NOW + 1_000); goal.tick(NOW + 2_000);
        assertEquals(1, searches);
        goal.stop(); goal.tick(NOW + 2_500);
        assertEquals(0, stops); assertEquals(1, searches);
        owner.teleport(owner.getLocation().add(2, 0, 0));
        goal.tick(NOW + 3_000);
        assertEquals(2, searches); assertEquals(2, moves);
        verify(path, never()).moveTo(any(Location.class), anyDouble());
        assertEquals(1, stops, "Changing destination cancels the old route");
        pet.stored(true); goal.stop();
        assertEquals(2, stops);
    }

    @Test void candidatesBeyondTheBudgetStillUseTheCheapSwimCheckAndKeepTheirExit() {
        nullPath = true; clearFromX = 6;
        goal.tick(NOW);
        assertEquals(4, searches);
        assertEquals(0, moves);
        assertTrue(body.getVelocity().getX() > 0);
        int reads = blockReads;
        goal.tick(NOW + 500); goal.tick(NOW + 1_000);
        assertEquals(4, searches);
        assertTrue(blockReads - reads < 20, "An already selected shore needs no block scan");
        goal.tick(NOW + 2_000);
        assertEquals(5, searches);
        assertTrue(blockReads - reads < 40);
        clearFromX = Double.POSITIVE_INFINITY;
        goal.tick(NOW + 4_000);
        assertEquals(9, searches, "Invalidating a cached exit still shares the four-path budget");
        reads = blockReads;
        goal.tick(NOW + 4_500);
        assertEquals(reads, blockReads);
    }

    @Test void aFinishedNativeRouteCanBeRebuiltWithoutScanningTheBanks() {
        pet.order(PetOrder.FOLLOW); nativeRoute = true;
        goal.tick(NOW);
        hasPath = false;
        int reads = blockReads;
        goal.tick(NOW + 500); goal.tick(NOW + 1_500);
        assertEquals(1, searches);
        goal.tick(NOW + 2_000);
        assertEquals(2, searches); assertEquals(2, moves);
        assertTrue(blockReads - reads < 20);
    }

    @Test void removedShoreAndArrivingAtTheExitAllowAFreshSearch() {
        nativeRoute = true;
        goal.tick(NOW);
        int reads = blockReads;
        floor = false; nativeRoute = false;
        goal.tick(NOW + 500);
        assertTrue(blockReads - reads > 1_000);
        assertFalse(hasPath, "A vanished bank must not leave an obsolete route running");
        floor = true; nativeRoute = true;
        body.teleport(body.getLocation().add(3, 0, 0));
        goal.tick(NOW + 1_000);
        body.teleport(routedTo);
        int before = searches;
        goal.tick(NOW + 1_500);
        assertTrue(searches > before, "Reaching a cached bank releases its destination");
    }

    @Test void groundMemoryRejectsWaterAirAndUnsafeLandAndCleansAbsentPets() {
        runtime.rememberGround(pet, body);
        assertNull(runtime.lastGround(pet));
        swimming = false; grounded = false;
        runtime.rememberGround(pet, body); assertNull(runtime.lastGround(pet));
        grounded = true; floor = false;
        runtime.rememberGround(pet, body); assertNull(runtime.lastGround(pet));
        floor = true;
        runtime.rememberGround(pet, body); assertNotNull(runtime.lastGround(pet));
        runtime.forgetMissingGround(); assertNotNull(runtime.lastGround(pet));
        pet.stored(true); runtime.forgetMissingGround(); assertNull(runtime.lastGround(pet));
        pet.stored(false); runtime.rememberGround(pet, body);
        pet.dead(true); runtime.forgetMissingGround(); assertNull(runtime.lastGround(pet));
        pet.dead(false); runtime.rememberGround(pet, body);
        body.remove(); runtime.forgetMissingGround(); assertNull(runtime.lastGround(pet));
        runtime.rememberGround(pet, body);
        assertTrue(runtime.store().remove(pet.id()));
        runtime.forgetMissingGround(); assertNull(runtime.lastGround(pet));
    }
}
