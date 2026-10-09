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
import org.bukkit.util.Vector;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class WaterNavigationGoalTest {
    private static final long NOW = 1_000_000L;
    private GoalServerMock server;
    private WorldMock world;
    private PlayerMock owner;
    private WolfMock body;
    private Pet pet;
    private PetRuntime runtime;
    private WaterNavigationGoal goal;
    private Pathfinder path;
    private int searches, moves, stops;
    private boolean swimming = true, hasPath;
    private boolean floor = true;
    private Location routedTo;

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(new GoalServerMock());
        world = new net.tfminecraft.companionpets.testutil.CollisionWorldMock() {
            @Override public boolean isChunkLoaded(int x, int z) { return true; }
            @Override public BlockMock getBlockAt(int x, int y, int z) {
                return new BlockMock(new Location(this, x, y, z)) {
                    @Override public Material getType() {
                        return floor && y == 63 ? Material.STONE : Material.AIR;
                    }
                    @Override public boolean isPassable() { return getType().isAir() || isLiquid(); }
                    @Override public boolean isLiquid() { return getType() == Material.WATER; }
                    @Override public Block getRelative(int dx, int dy, int dz) { return world.getBlockAt(x + dx, y + dy, z + dz); }
                };
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
        pet = new Pet(UUID.randomUUID(), owner.getUniqueId(), "wolf", "Toby", PetSex.MALE);
        pet.order(PetOrder.SIT);
        store.add(pet);
        path = mock(Pathfinder.class);
        when(path.findPath(any(Location.class))).thenAnswer(invocation -> { searches++; return null; });
        when(path.hasPath()).thenAnswer(invocation -> hasPath);
        when(path.moveTo(any(Location.class), anyDouble())).thenAnswer(invocation -> {
            moves++; routedTo = invocation.getArgument(0, Location.class).clone(); hasPath = true; return true;
        });
        doAnswer(invocation -> { stops++; hasPath = false; return null; }).when(path).stopPathfinding();
        body = new WolfMock(server, UUID.randomUUID()) {
            @Override public boolean isInWater() { return swimming; }
            @Override public boolean isOnGround() { return true; }
            @Override public float getBodyYaw() { return 0; }
            @Override public Pathfinder getPathfinder() { return path; }
            @Override public void setRemoveWhenFarAway(boolean remove) { }
        };
        server.registerEntity(body);
        body.teleport(new Location(world, 2.5, 64, 2.5));
        runtime.remember(pet, body);
        WaterNavigationGoal.ensure(runtime, pet, body);
        goal = (WaterNavigationGoal) server.getMobGoals().getGoal(body,
                GoalKey.of(Mob.class, new NamespacedKey(plugin, "water_navigation")));
    }

    @AfterEach void cleanup() {
        if (runtime != null) runtime.store().close();
        MockBukkit.unmock();
    }

    private WaterNavigationGoal registeredGoal() {
        return (WaterNavigationGoal) server.getMobGoals().getGoal(body,
                GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "water_navigation")));
    }

    @Test void idlePetReturnsToRememberedGroundWithoutChangingVelocity() {
        swimming = false; runtime.rememberGround(pet, body);
        Location ground = runtime.lastGround(pet);
        swimming = true; body.teleport(ground.clone().add(3, 0, 0));
        Vector velocity = new Vector(.12, -.04, .03); body.setVelocity(velocity);
        body.setSitting(true); body.setAware(false);
        goal.tick(NOW);
        assertEquals(ground, routedTo); assertEquals(velocity, body.getVelocity());
        assertTrue(body.isAware()); assertFalse(body.isSitting());
        assertEquals(1, moves); assertEquals(0, searches); assertEquals(0, stops);
        goal.tick(NOW + 499); goal.tick(NOW + 500);
        assertEquals(1, moves, "A live route is preserved");
        hasPath = false; goal.tick(NOW + 999); assertEquals(1, moves);
        goal.tick(NOW + 1000); assertEquals(2, moves);
        swimming = false; assertFalse(goal.shouldStayActive());
        goal.stop(); assertEquals(0, stops);
        WaterNavigationGoal.ensure(runtime, pet, body); assertSame(goal, registeredGoal());
    }

    @Test void changedOrUnsafeGroundRefreshesTheDestination() {
        swimming = false; runtime.rememberGround(pet, body);
        swimming = true; goal.tick(NOW); Location first = routedTo;
        body.teleport(first.clone().add(3, 0, 0));
        swimming = false; runtime.rememberGround(pet, body);
        swimming = true; goal.tick(NOW + 500);
        assertEquals(runtime.lastGround(pet), routedTo); assertEquals(2, moves);
        floor = false; goal.tick(NOW + 1000); assertEquals(2, moves);
        floor = true; goal.tick(NOW + 1500); assertEquals(3, moves);
    }

    @Test void missingGroundUsesNearestBankAndNoBankKeepsNativeFloatingAwake() {
        goal.tick(NOW); assertNotNull(routedTo); assertEquals(1, moves);
        floor = false; goal.tick(NOW + 500); assertEquals(1, moves);
        assertTrue(body.isAware()); assertEquals(new Vector(), body.getVelocity());
        assertEquals(0, searches); assertEquals(0, stops);
    }

    @Test void fetchAndCallOwnMoveInWaterWithoutTheWaterGoalReplacingTheirRoutes() {
        var job = new net.tfminecraft.companionpets.play.FetchJob("STICK", owner.getUniqueId());
        pet.fetch(job); pet.activity(Activity.PLAYING);
        int[] steps = {0};
        FetchNavigationGoal.ensure(runtime, pet, body, () -> steps[0]++);
        var fetch = server.getMobGoals().getGoal(body, GoalKey.of(Mob.class,
                new NamespacedKey(runtime.plugin(), "fetch_navigation")));
        assertTrue(fetch.shouldActivate()); assertTrue(fetch.shouldStayActive());
        assertEquals(java.util.EnumSet.of(com.destroystokyo.paper.entity.ai.GoalType.MOVE), fetch.getTypes());
        assertFalse(goal.shouldActivate()); goal.tick(NOW); fetch.tick(); assertEquals(1, steps[0]);
        pet.fetch(null); pet.activity(Activity.ATTENDING); pet.order(PetOrder.FOLLOW);
        CallNavigationGoal.ensure(runtime, pet, body, () -> steps[0]++);
        var call = server.getMobGoals().getGoal(body, GoalKey.of(Mob.class,
                new NamespacedKey(runtime.plugin(), "call_navigation")));
        assertTrue(call.shouldActivate()); assertTrue(call.shouldStayActive());
        assertEquals(java.util.EnumSet.of(com.destroystokyo.paper.entity.ai.GoalType.MOVE), call.getTypes());
        assertFalse(goal.shouldStayActive()); goal.tick(NOW + 500); call.tick(); assertEquals(2, steps[0]);
        assertEquals(0, moves); assertEquals(0, stops);
    }

    @Test void permittedFollowAlwaysYieldsToVanillaButHeldOrUnavailableFollowEscapes() {
        pet.order(PetOrder.FOLLOW); assertFalse(goal.shouldActivate());
        goal.start(); assertEquals(0, moves);
        pet.staying(true); assertTrue(goal.shouldActivate());
        pet.staying(false); owner.disconnect(); assertTrue(goal.shouldActivate());
        pet.stored(true); assertFalse(goal.shouldActivate());
        pet.stored(false); body.remove(); assertFalse(goal.shouldActivate());
        runtime.forgetMissingGround(); assertNull(runtime.lastGround(pet));
    }

    @Test void replacedBodiesCannotActivateAnOldGoal() {
        goal.tick(NOW); goal.stop();
        UUID original = pet.entityId(); pet.entityId(UUID.randomUUID());
        assertFalse(goal.shouldActivate()); pet.entityId(original);
        WaterNavigationGoal.ensure(runtime, pet, body); assertSame(goal, registeredGoal());
    }
}
