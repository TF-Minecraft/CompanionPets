package net.tfminecraft.companionpets.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.body.PetPlacement;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.testutil.GoalServerMock;
import net.tfminecraft.companionpets.testutil.CollisionWorldMock;
import net.tfminecraft.companionpets.visual.PetAnimation;
import net.tfminecraft.companionpets.visual.PetVisual;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Wolf;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class PetRoamingCoverageTest {
    private GoalServerMock server;
    private final java.util.Set<UUID> unloaded = new java.util.HashSet<>();
    private WorldMock world;
    private PlayerMock owner;
    private WolfMock body;
    private Pet pet;
    private PetRuntime runtime;
    private PetActions actions;
    private PetRoaming roaming;
    private final List<Location> paths = new ArrayList<>();
    private final List<Double> speeds = new ArrayList<>();
    private final List<PetAnimation> poses = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private int stops;
    private static final long NOW = 1_000_000;

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(new GoalServerMock() {
            @Override public Entity getEntity(UUID id) {
                return unloaded.contains(id) ? null : super.getEntity(id);
            }
        });
        world = new CollisionWorldMock() {
            private final java.util.Map<String, BlockMock> blocks = new java.util.HashMap<>();
            @Override public BlockMock getBlockAt(int x, int y, int z) {
                return blocks.computeIfAbsent(x + ":" + y + ":" + z, key ->
                        new BlockMock(y == 63 ? Material.STONE : Material.AIR, new Location(this, x, y, z)) {
                            @Override public boolean isPassable() { return !getType().isSolid(); }
                        });
            }
            @Override public <T extends Entity> T spawn(Location at, Class<T> type) {
                return type == Wolf.class ? type.cast(body(at)) : super.spawn(at, type);
            }
            @Override public void spawnParticle(Particle particle, Location location, int count,
                    double x, double y, double z, double extra) { particles.add(particle); }
        };
        server.addWorld(world);
        owner = server.addPlayer("Owner");
        owner.teleport(new Location(world, 0, 64, 0));
        var plugin = MockBukkit.createMockPlugin();
        var yaml = new YamlConfiguration();
        yaml.loadFromString("pets: {wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG, sounds: false}}");
        var visual = new PetVisual() {
            @Override public void apply(Entity entity, PetTypeDef type) { }
            @Override public void update(Entity entity, PetTypeDef type, PetAnimation pose) { poses.add(pose); }
        };
        var key = new NamespacedKey(plugin, "pet");
        var store = new PetStore(plugin); assertTrue(store.load());
        runtime = new PetRuntime(plugin, CompanionConfig.load(plugin, yaml), store, new Sessions(),
                new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        actions = new PetActions(runtime); roaming = actions.roaming();
        pet = new Pet(UUID.randomUUID(), owner.getUniqueId(), "wolf", "Toby", PetSex.MALE);
        pet.bindWord("come", Trick.COME); pet.progress(Trick.COME, 100);
        store.add(pet);
        body = body(owner.getLocation().add(8, 0, 0));
        runtime.remember(pet, body);
    }

    private WolfMock body(Location location) {
        var body = new WolfMock(server, UUID.randomUUID()) {
            private boolean removeWhenFarAway = true;
            @Override public void setRemoveWhenFarAway(boolean remove) { removeWhenFarAway = remove; }
            @Override public boolean getRemoveWhenFarAway() { return removeWhenFarAway; }
            @Override public float getBodyYaw() { return 0; }
            // Paper invalidates an entity object when its chunk unloads.
            @Override public boolean isValid() { return super.isValid() && !unloaded.contains(getUniqueId()); }
            @Override public com.destroystokyo.paper.entity.Pathfinder getPathfinder() {
                return (com.destroystokyo.paper.entity.Pathfinder) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{com.destroystokyo.paper.entity.Pathfinder.class}, (proxy, method, args) -> switch (method.getName()) {
                            case "stopPathfinding" -> { stops++; yield null; }
                            case "moveTo" -> { paths.add(((Location) args[0]).clone()); speeds.add((Double) args[1]); yield true; }
                            case "hasPath" -> false;
                            default -> throw new AssertionError("Unexpected navigation: " + method.getName());
                        });
            }
        };
        server.registerEntity(body); body.teleport(location);
        return body;
    }

    @AfterEach void cleanup() {
        if (actions != null) actions.holograms().clear();
        if (runtime != null) assertTrue(runtime.store().close());
        if (owner != null) PetFx.clearPlayer(owner.getUniqueId());
        MockBukkit.unmock();
    }

    @Test void ownerSessionChangeRestoresThePriorOrderWhileTheBodyIsUnloaded() {
        startRealCome(PetOrder.SIT);
        unloadBody();
        actions.ownerSessionChanged(owner);
        assertEquals(PetOrder.SIT, pet.order(), "Leaving must preserve the previous posture even without a loaded body");
        assertEquals(Activity.NONE, pet.activity());
        assertFalse(roaming.coming(pet));
        assertEquals(body.getUniqueId(), pet.entityId());
        assertNull(runtime.entity(pet));
    }

    @Test void reloadCleanupRestoresThePriorStayOrderWithoutLoadingTheBody() {
        startRealCome(PetOrder.STAY);
        unloadBody();
        actions.clearInteractions();
        assertEquals(PetOrder.STAY, pet.order(), "Reload must not turn an unloaded staying pet into a follower");
        assertTrue(pet.staying());
        assertEquals(Activity.NONE, pet.activity());
        assertNull(runtime.entity(pet));
        assertFalse(roaming.coming(pet));
        assertTrue(runtime.store().save());
        var reloaded = new PetStore(runtime.plugin());
        assertTrue(reloaded.load());
        assertEquals(PetOrder.STAY, reloaded.get(pet.id()).order());
        assertTrue(reloaded.get(pet.id()).staying());
        assertTrue(reloaded.close());
    }

    @Test void callsReplanAtHalfSecondIntervalsOrWhenTheOwnerMovesTwoBlocks() {
        roaming.attend(pet, owner, NOW);
        for (int tick = 0; tick < 10; tick++) assertTrue(roaming.tickAttention(pet, body, NOW + tick * 50));
        assertEquals(1, paths.size());
        owner.teleport(owner.getLocation().add(1, 0, 0));
        roaming.tickAttention(pet, body, NOW + 499);
        assertEquals(1, paths.size());
        roaming.tickAttention(pet, body, NOW + 500);
        assertEquals(2, paths.size());
        assertEquals(owner.getLocation(), paths.getLast());
        owner.teleport(owner.getLocation().add(2.1, 0, 0));
        roaming.tickAttention(pet, body, NOW + 550);
        assertEquals(3, paths.size());
        assertEquals(owner.getLocation(), paths.getLast());
        roaming.tickAttention(pet, body, NOW + 1049);
        assertEquals(3, paths.size());
        roaming.tickAttention(pet, body, NOW + 1050);
        assertEquals(4, paths.size());
        var other = server.addSimpleWorld("other");
        owner.teleport(new Location(other, 0, 64, 0));
        body.teleport(new Location(other, 8, 64, 0));
        roaming.tickAttention(pet, body, NOW + 1100);
        assertEquals(5, paths.size());
        assertEquals(owner.getLocation(), paths.getLast());
    }

    @Test void fetchReturnsKeepTheSameRouteBetweenReplansAndCancelOnWorldChanges() {
        roaming.returnFromFetch(pet, owner, 1.8);
        long at = System.currentTimeMillis();
        roaming.tickAttention(pet, body, at + 100);
        roaming.tickAttention(pet, body, at + 400);
        assertEquals(1, paths.size());
        roaming.tickAttention(pet, body, at + 600);
        assertEquals(2, paths.size());
        assertEquals(List.of(1.8, 1.8), speeds);
        owner.teleport(new Location(server.addSimpleWorld("other"), 0, 64, 0));
        assertTrue(roaming.tickAttention(pet, body, at + 650));
        assertFalse(roaming.returningFromFetch(pet));
        assertEquals(2, paths.size());
    }

    @Test void destinationTracksTheCurrentCallerAndExpiresOnDisconnectOrCancellation() {
        assertNull(roaming.destination(pet));
        roaming.attend(pet, owner, NOW);
        assertEquals(owner.getLocation(), roaming.destination(pet));
        owner.teleport(owner.getLocation().add(3, 0, 2));
        assertEquals(owner.getLocation(), roaming.destination(pet));
        assertTrue(roaming.tickAttention(pet, body, NOW + 1));
        assertEquals(owner.getLocation(), paths.getLast());
        assertEquals(1.25, speeds.getLast());
        roaming.cancel(pet);
        assertNull(roaming.destination(pet));
        assertEquals(Activity.NONE, pet.activity());
        roaming.attend(pet, owner, NOW + 2);
        owner.disconnect();
        assertNull(roaming.destination(pet));
        assertFalse(roaming.tickAttention(pet, body, NOW + 3));
        assertEquals(Activity.NONE, pet.activity());
    }

    @Test void clearReleasesNameAttentionListeningAndStationarityWithoutChangingOrders() {
        var other = new Pet(UUID.randomUUID(), owner.getUniqueId(), "wolf", "Molly", PetSex.FEMALE);
        other.order(PetOrder.SIT);
        runtime.store().add(other);
        runtime.remember(other, body(owner.getLocation().add(3, 0, 0)));
        roaming.attend(pet, owner, NOW);
        roaming.listen(other, owner, NOW);
        roaming.tickOwners(NOW);
        assertTrue(roaming.ownerStationary(owner, NOW + 2000));
        assertEquals(NOW + 10_000, other.listeningUntilMillis());
        actions.clearInteractions();
        assertNull(roaming.destination(pet));
        assertEquals(Activity.NONE, pet.activity());
        assertEquals(PetOrder.SIT, other.order());
        assertEquals(0, other.listeningUntilMillis());
        assertFalse(roaming.ownerStationary(owner, NOW + 2000));
        assertFalse(roaming.tickAttention(pet, body, NOW + 2001));
        actions.clearInteractions(); // Reload cleanup is idempotent.
    }

    @Test void cleanupToleratesPetsRemovedBetweenAttentionAndReload() {
        roaming.attend(pet, owner, NOW);
        roaming.listen(pet, owner, NOW);
        assertTrue(runtime.store().remove(pet.id()));
        actions.clearInteractions();
        assertNull(roaming.destination(pet));
        assertFalse(roaming.tickAttention(pet, body, NOW + 1));
        assertTrue(runtime.store().all().isEmpty());
    }

    @Test void loadedCancellationRestoresPoseAndStopsMovementForEverySavedOrder() {
        for (PetOrder previous : PetOrder.values()) {
            startRealCome(previous);
            body.setVelocity(new Vector(0.3, -0.2, 0.4));
            actions.ownerSessionChanged(owner);
            assertEquals(previous, pet.order());
            assertEquals(previous == PetOrder.STAY, pet.staying());
            assertEquals(Activity.NONE, pet.activity());
            assertFalse(roaming.coming(pet));
            assertEquals(previous != PetOrder.FOLLOW, body.isSitting());
            assertEquals(0, body.getVelocity().getX());
            assertEquals(0, body.getVelocity().getZ());
            assertEquals(-0.2, body.getVelocity().getY());
            assertTrue(body.isAware());
            assertEquals(previous == PetOrder.LAY ? PetAnimation.LIE
                    : previous == PetOrder.SIT ? PetAnimation.SIT : PetAnimation.IDLE, poses.getLast());
        }
        assertTrue(stops > 0);
    }

    @Test void ownerMotionUsesHorizontalMovementAndRestartsAfterWorldChangesOrDisconnects() {
        assertFalse(roaming.ownerStationary(owner, NOW));
        roaming.tickOwners(NOW);
        assertFalse(roaming.ownerStationary(owner, NOW + 1999));
        assertTrue(roaming.ownerStationary(owner, NOW + 2000));
        owner.teleport(owner.getLocation().add(0, 2, 0));
        owner.setVelocity(new Vector(0, 0.5, 0));
        roaming.tickOwners(NOW + 2100);
        assertTrue(roaming.ownerStationary(owner, NOW + 2100));
        owner.teleport(owner.getLocation().add(0.5, 0, 0));
        roaming.tickOwners(NOW + 2200);
        assertFalse(roaming.ownerStationary(owner, NOW + 2200));
        owner.setVelocity(new Vector(0.2, 0, 0));
        roaming.tickOwners(NOW + 3000);
        assertFalse(roaming.ownerStationary(owner, NOW + 4999));
        owner.setVelocity(new Vector());
        roaming.tickOwners(NOW + 5000);
        assertTrue(roaming.ownerStationary(owner, NOW + 5000));
        var otherWorld = server.addSimpleWorld("other");
        owner.teleport(new Location(otherWorld, 0, 64, 0));
        roaming.tickOwners(NOW + 5100);
        assertFalse(roaming.ownerStationary(owner, NOW + 5100));
        owner.disconnect();
        roaming.tickOwners(NOW + 9000);
        assertFalse(roaming.ownerStationary(owner, NOW + 9000));
    }

    @Test void listeningExpiresAtItsDeadlineAndStopsWhenCallerLeavesTheWorld() {
        roaming.tickListening(pet, body, NOW); // No active listener.
        roaming.listen(pet, owner, NOW);
        assertEquals(NOW + 10_000, pet.listeningUntilMillis());
        roaming.tickListening(pet, body, NOW + 9999);
        assertEquals(NOW + 10_000, pet.listeningUntilMillis());
        roaming.tickListening(pet, body, NOW + 10_000);
        assertEquals(0, pet.listeningUntilMillis());
        roaming.listen(pet, owner, NOW + 20_000);
        owner.teleport(new Location(server.addSimpleWorld("other"), 0, 64, 0));
        roaming.tickListening(pet, body, NOW + 20_001);
        assertEquals(0, pet.listeningUntilMillis());
        owner.teleport(new Location(world, 0, 64, 0));
        roaming.listen(pet, owner, NOW + 30_000);
        owner.disconnect();
        roaming.tickListening(pet, body, NOW + 30_001);
        assertEquals(0, pet.listeningUntilMillis());
    }

    @Test void arrivingNameCallGreetsOnceAndWaitsForTheConfiguredAttentionWindow() {
        roaming.attend(pet, owner, NOW);
        assertTrue(roaming.tickAttention(pet, body, NOW + 29_000));
        assertEquals(owner.getLocation(), paths.getLast());
        body.teleport(owner.getLocation().add(1, 0, 0));
        assertTrue(roaming.tickAttention(pet, body, NOW + 29_999));
        assertEquals(List.of(Particle.HEART), particles);
        assertTrue(roaming.tickAttention(pet, body, NOW + 39_998));
        assertEquals(List.of(Particle.HEART), particles, "Waiting must not repeatedly greet");
        assertFalse(roaming.tickAttention(pet, body, NOW + 39_999));
        assertEquals(Activity.NONE, pet.activity());
        assertEquals(PetOrder.FOLLOW, pet.order());
    }

    @Test void fetchReturnUsesItsConfiguredSpeedAndCompletesOnArrival() {
        assertFalse(roaming.returningFromFetch(pet));
        assertEquals(1.25, roaming.movementSpeed(pet));
        roaming.returnFromFetch(pet, owner, 1.8);
        assertTrue(roaming.returningFromFetch(pet));
        assertEquals(1.8, roaming.movementSpeed(pet));
        assertEquals(owner.getLocation(), paths.getLast());
        assertEquals(1.8, speeds.getLast());
        body.teleport(owner.getLocation().add(1, 0, 0));
        assertTrue(roaming.tickAttention(pet, body, 0)); // Before the internally created real-time deadline.
        assertFalse(roaming.returningFromFetch(pet));
        assertEquals(Activity.NONE, pet.activity());
        assertEquals(PetOrder.FOLLOW, pet.order());
        assertEquals(1.25, roaming.movementSpeed(pet));
        assertNull(roaming.destination(pet));
    }

    @Test void restoringAMissingBodyDiscardsItsOldNameCallRoute() {
        actions.onChat(owner, "Toby");
        assertEquals(Activity.ATTENDING, pet.activity());
        assertEquals(owner.getLocation(), roaming.destination(pet));
        pet.need(Need.MOOD, 72);
        UUID previousBody = body.getUniqueId();
        body.remove(); // No observed death: recovery may replace a missing body.
        world.loadChunk(0, 0);
        var replacement = (Mob) actions.restoreBody(pet);
        assertNotNull(replacement);
        assertNotEquals(previousBody, replacement.getUniqueId());
        Location recovered = replacement.getLocation();
        assertEquals(64, recovered.getY());
        assertEquals(.5, recovered.getX() - Math.floor(recovered.getX()));
        assertEquals(.5, recovered.getZ() - Math.floor(recovered.getZ()));
        assertTrue(PetPlacement.safe(recovered, PetPlacement.bounds(replacement)));
        assertTrue(replacement.isPersistent());
        assertFalse(replacement.getRemoveWhenFarAway());
        assertEquals(pet.id(), runtime.bodies().readId(replacement));
        assertEquals(72, pet.need(Need.MOOD));
        assertEquals(Activity.NONE, pet.activity());
        assertFalse(roaming.tickAttention(pet, replacement, NOW));
        assertNull(roaming.destination(pet), "A recovered idle body must not retain the previous body's route");
        assertEquals(PetOrder.FOLLOW, pet.order());
        assertEquals(Activity.NONE, pet.activity());
    }

    private void startRealCome(PetOrder previous) {
        pet.order(previous); pet.staying(previous == PetOrder.STAY);
        actions.onChat(owner, "Toby come");
        assertTrue(roaming.coming(pet));
        assertEquals(previous, roaming.returnOrder(pet));
        assertEquals(PetOrder.FOLLOW, pet.order());
        assertEquals(Activity.ATTENDING, pet.activity());
    }

    private void unloadBody() {
        // MockBukkit's entity registry is the boundary used by Bukkit.getEntity after chunk unloading.
        unloaded.add(body.getUniqueId());
        assertFalse(body.isDead());
        assertNull(runtime.entity(pet));
    }
}
