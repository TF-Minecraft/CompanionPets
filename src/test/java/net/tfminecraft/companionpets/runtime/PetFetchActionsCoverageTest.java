package net.tfminecraft.companionpets.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.entity.ai.GoalKey;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.item.ToyItems;
import net.tfminecraft.companionpets.listen.PetListener;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.play.FetchPhase;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.testutil.GoalServerMock;
import net.tfminecraft.companionpets.testutil.CollisionWorldMock;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.entity.CatMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;
import org.mockbukkit.mockbukkit.world.WorldMock;

@ExtendWith(PetFetchActionsCoverageTest.FailUnimplemented.class)
class PetFetchActionsCoverageTest {
    private GoalServerMock server;
    private WorldMock world;
    private PlayerMock owner;
    private PetRuntime runtime;
    private PetActions actions;
    private PetListener listener;
    private Pet pet;
    private YamlConfiguration yaml;
    private ItemStack toy;
    private boolean failSpawn;
    private boolean loadedWaterChunks;
    private java.util.Set<String> modelClips = java.util.Set.of();
    private Runnable beforeSpawnFailure = () -> { };
    private final Map<UUID, Location> targets = new HashMap<>();
    private final Map<UUID, Double> speeds = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private Handler logs;

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(new GoalServerMock());
        world = new CollisionWorldMock() {
            @Override public boolean isChunkLoaded(int x, int z) { return loadedWaterChunks || super.isChunkLoaded(x, z); }
            @Override public <T extends Entity> T spawn(Location at, Class<T> type, java.util.function.Consumer<? super T> callback) {
                if (type == Snowball.class && failSpawn) {
                    beforeSpawnFailure.run(); throw new IllegalStateException("Projectile spawning is unavailable");
                }
                return super.spawn(at, type, callback);
            }
            @Override public BlockMock getBlockAt(int x, int y, int z) {
                return new BlockMock(y == 63 ? Material.STONE : Material.AIR, new Location(this, x, y, z)) {
                    @Override public boolean isPassable() { return !getType().isSolid(); }
                };
            }
            @Override public void spawnParticle(Particle particle, Location at, int count, double x, double y, double z, double extra) { }
        };
        server.addWorld(world);
        owner = new PlayerMock(server, "Thrower") {
            private final org.bukkit.inventory.PlayerInventory inventory =
                    new org.mockbukkit.mockbukkit.inventory.PlayerInventoryMock(this) {
                @Override public HashMap<Integer, ItemStack> addItem(ItemStack... stacks) {
                    // Bukkit adds to storage only; MockBukkit incorrectly includes equipment/extra slots.
                    var storage = server.createInventory(null, 36);
                    storage.setContents(getStorageContents());
                    var leftover = storage.addItem(stacks);
                    setStorageContents(storage.getContents());
                    return leftover;
                }
            };
            @Override public org.bukkit.inventory.PlayerInventory getInventory() {
                return inventory == null ? super.getInventory() : inventory;
            }
        };
        server.addPlayer(owner); owner.teleport(new Location(world, 0, 64, 0));
        owner.openInventory(server.createInventory(null, 9));
        var plugin = MockBukkit.createMockPlugin();
        yaml = new YamlConfiguration(); yaml.loadFromString("""
            items: {toys: [STICK]}
            moments: {enabled: false, belly-up: {enabled: false}, greeting: {enabled: false}}
            social: {enabled: false}
            pets:
              wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG}
              cat: {entity: CAT, egg: CAT_SPAWN_EGG, behavior: cat}
            """);
        var visual = new net.tfminecraft.companionpets.visual.PetVisual() {
            @Override public void apply(Entity entity, net.tfminecraft.companionpets.config.PetTypeDef type) { }
            @Override public boolean modelAvailable(net.tfminecraft.companionpets.config.PetTypeDef type) { return true; }
            @Override public java.util.Set<String> clips(net.tfminecraft.companionpets.config.PetTypeDef type) { return modelClips; }
        };
        var key = new NamespacedKey(plugin, "pet");
        var store = new PetStore(plugin); assertTrue(store.load());
        runtime = new PetRuntime(plugin, CompanionConfig.load(plugin, yaml), store, new Sessions(),
                new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        actions = new PetActions(runtime); listener = new PetListener(runtime, actions);
        pet = wolf(owner.getUniqueId(), 2);
        toy = new ItemStack(Material.STICK); var meta = toy.getItemMeta();
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "original-toy"), PersistentDataType.STRING, "fetch-me");
        meta.setCustomModelData(1234); toy.setItemMeta(meta);
        logs = new Handler() {
            @Override public void publish(LogRecord record) { warnings.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        plugin.getLogger().addHandler(logs);
    }

    @AfterEach void cleanup() {
        runtime.plugin().getLogger().removeHandler(logs);
        MockBukkit.unmock();
    }

    private Pet wolf(UUID ownerId, double x) {
        var body = new PetFetchWorkflowTest.FetchWolf(server, UUID.randomUUID(), targets, speeds);
        server.registerEntity(body); body.teleport(new Location(world, x, 64, 0));
        return register(ownerId, "wolf", body);
    }

    private Pet foreignPet() {
        var carer = server.addPlayer(); carer.teleport(new Location(world, 10, 64, 0));
        Pet result = wolf(carer.getUniqueId(), 3);
        result.carers().reinforce(owner.getUniqueId(), 40, System.currentTimeMillis(), 0);
        runtime.random().setSeed(4096);
        return result;
    }
    private Pet cat(boolean crouched) {
        var body = new CatMock(server, UUID.randomUUID()) {
            @Override public double getWidth() { return .6; }
            @Override public Pathfinder getPathfinder() { return pathfinder(this); }
            @Override public boolean isInWater() { return false; }
            @Override public boolean isOnGround() { return true; }
        };
        body.setSneaking(crouched); server.registerEntity(body); body.teleport(new Location(world, 3, 64, 0));
        return register(owner.getUniqueId(), "cat", body);
    }

    private Pet register(UUID ownerId, String type, Mob body) {
        var result = new Pet(UUID.randomUUID(), ownerId, type, "Fetcher", PetSex.MALE);
        for (Need need : Need.values()) result.need(need, 80);
        runtime.store().add(result); runtime.remember(result, body); return result;
    }

    private Pathfinder pathfinder(Mob body) {
        return (Pathfinder) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Pathfinder.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "moveTo" -> { targets.put(body.getUniqueId(), ((Location) args[0]).clone()); yield true; }
                case "setCanFloat" -> { assertEquals(true, args[0]); yield null; }
                case "stopPathfinding" -> { targets.remove(body.getUniqueId()); yield null; }
                case "hasPath" -> targets.containsKey(body.getUniqueId());
                case "getEntity" -> body;
                default -> throw new AssertionError(method.getName());
            });
    }

    private void useToy(int amount) {
        var hand = toy.clone(); hand.setAmount(amount); owner.getInventory().setItemInMainHand(hand);
        assertTrue(actions.handledWorld(owner, hand, null, null, false, true));
        actions.anticipation().tick(System.currentTimeMillis());
        actions.useWorld(owner, owner.getInventory().getItemInMainHand(), null, null, false, true);
    }
    private Snowball throwToy() {
        useToy(1);
        return world.getEntities().stream().filter(Snowball.class::isInstance).map(Snowball.class::cast).findFirst().orElseThrow();
    }
    private Item land(Snowball ball) {
        ball.teleport(new Location(world, 8, 64, 0)); listener.onToyHit(new ProjectileHitEvent(ball)); return items().getFirst();
    }
    private void carry(Pet winner) {
        Item item = land(throwToy()); runtime.entity(winner).teleport(item.getLocation());
        assertTrue(actions.fetchActions().claim(winner)); assertEquals(FetchPhase.CARRY, winner.fetch().phase());
    }
    private List<Item> items() {
        return server.getWorlds().stream().flatMap(w -> w.getEntities().stream()).filter(Item.class::isInstance).map(Item.class::cast).toList();
    }
    private void assertOnePlainToy() { assertOnePlainToy(0); }
    private void assertOnePlainToy(int pickupDelay) {
        assertEquals(1, items().size()); assertTrue(toy.isSimilar(items().getFirst().getItemStack()));
        assertEquals(1, items().getFirst().getItemStack().getAmount());
        assertEquals(pickupDelay, items().getFirst().getPickupDelay());
        assertFalse(items().getFirst().getPersistentDataContainer().has(runtime.toyKey()));
    }

    @Test void successfulToyThrowPlaysOneLaunchSoundForThrowerAndNearbyPlayers() {
        var nearby = server.addPlayer(); nearby.teleport(owner.getLocation());
        throwToy();
        for (var player : List.of(owner, nearby)) {
            assertEquals(1, player.getHeardSounds().stream()
                    .filter(sound -> sound.getSound().equals(Sound.ENTITY_SNOWBALL_THROW.getKey().getKey())).count());
        }
    }

    @Test void failedProjectileSpawnRefundsExactlyTheConsumedItemWithItsMetadata() {
        failSpawn = true; useToy(7);
        assertTrue(owner.getHeardSounds().stream()
                .noneMatch(sound -> sound.getSound().equals(Sound.ENTITY_SNOWBALL_THROW.getKey().getKey())), "Failed launches are silent");
        assertEquals(7, owner.getInventory().getItemInMainHand().getAmount());
        assertTrue(toy.isSimilar(owner.getInventory().getItemInMainHand()));
        assertTrue(items().isEmpty()); assertNull(pet.fetch());
        actions.stashLooseToys(); assertTrue(items().isEmpty(), "A failed launch registers no recoverable second toy");
    }

    @Test void failedProjectileSpawnDropsRefundIfAnotherSpawnHandlerFillsTheInventory() {
        failSpawn = true;
        beforeSpawnFailure = () -> {
            for (int slot = 0; slot < 36; slot++) owner.getInventory().setItem(slot, new ItemStack(Material.DIAMOND, 64));

        };
        useToy(1);
        assertEquals(Material.DIAMOND, owner.getInventory().getItemInMainHand().getType(), "Spawn boundary filled the freed hand slot");
        assertFalse(java.util.Arrays.stream(owner.getInventory().getStorageContents()).anyMatch(java.util.Objects::isNull),
                "Spawn handler filled every storage slot");
        assertOnePlainToy(10); assertEquals(owner.getLocation(), items().getFirst().getLocation());
        assertNull(pet.fetch()); actions.stashLooseToys(); assertOnePlainToy(10);
    }

    @Test void failedCreativeThrowPreservesHandAndCreatesNoRefund() {
        failSpawn = true; owner.setGameMode(GameMode.CREATIVE); useToy(7);
        assertEquals(7, owner.getInventory().getItemInMainHand().getAmount());
        assertTrue(items().isEmpty()); assertNull(pet.fetch());
    }

    @Test void inFlightDestinationIsTheCurrentProjectilePosition() {
        Snowball ball = throwToy(); assertEquals(ball.getLocation(), actions.fetchActions().destination(pet));
        ball.teleport(new Location(world, 15, 68, 3));
        assertEquals(ball.getLocation(), actions.fetchActions().destination(pet));
        ball.remove(); assertNull(actions.fetchActions().destination(pet));
        actions.fetchActions().tick(1000); actions.fetchActions().tick(2501); assertOnePlainToy();
    }

    @Test void throwerDisconnectDuringCarryStoresOnlyTheirOwnToyAndDetachesFollowers() {
        var other = wolf(owner.getUniqueId(), 3); carry(pet); owner.disconnect();
        actions.fetchActions().step(pet, (Mob) runtime.entity(pet));
        assertNull(pet.fetch()); assertNull(other.fetch()); assertTrue(items().isEmpty());
        assertTrue(toy.isSimilar(ToyItems.decode(pet.carriedToy()))); assertNull(other.carriedToy());
        actions.stashLooseToys(); assertTrue(items().isEmpty());
    }

    @Test void throwerDisconnectDoesNotStoreTheirToyOnAForeignCarrier() {
        pet.order(PetOrder.STAY); var foreign = foreignPet(); carry(foreign);
        Location at = runtime.entity(foreign).getLocation(); owner.disconnect();
        actions.fetchActions().step(foreign, (Mob) runtime.entity(foreign));
        assertNull(foreign.fetch()); assertNull(foreign.carriedToy()); assertOnePlainToy();
        assertEquals(at, items().getFirst().getLocation());
    }

    @Test void tiredCarrierReturnsTheSingleToyInsteadOfContinuingTheRace() {
        carry(pet); pet.need(Need.ENERGY, 20);
        actions.fetchActions().step(pet, (Mob) runtime.entity(pet));
        assertNull(pet.fetch()); assertNull(pet.carriedToy()); assertOnePlainToy();
        assertTrue(items().getFirst().getLocation().distance(owner.getLocation()) < 3);
    }

    @Test void removedGroundItemEndsTheRaceWithoutRecreatingAConsumedToy() {
        var item = land(throwToy()); item.remove();
        actions.fetchActions().step(pet, (Mob) runtime.entity(pet));
        assertNull(pet.fetch()); assertEquals(Activity.NONE, pet.activity()); assertTrue(items().isEmpty());
        actions.stashLooseToys(); assertTrue(items().isEmpty());
    }

    @Test void catsStalkingAndPouncingAreExcludedFromTheMotionWatch() throws Exception {
        pet.order(PetOrder.STAY); var cat = cat(false); var body = (Cat) runtime.entity(cat);
        Item item = land(throwToy()); body.teleport(item.getLocation());
        actions.fetchActions().step(cat, body, 1000);
        var job = cat.fetch(); assertTrue(job.stalking(cat.id()));
        body.teleport(new Location(world, 30, 64, 0));
        var ticker = new PetTicker(runtime, actions);
        var watch = PetTicker.class.getDeclaredMethod("watchFetchMotion", Pet.class, Mob.class, long.class);
        watch.setAccessible(true);
        watch.invoke(ticker, cat, body, 1000L); watch.invoke(ticker, cat, body, 11_000L);
        assertSame(job, cat.fetch()); assertTrue(body.isSneaking());
        actions.fetchActions().step(cat, body, 2500);
        assertTrue(job.pouncing(cat.id()));
        watch.invoke(ticker, cat, body, 12_000L); watch.invoke(ticker, cat, body, 22_000L);
        assertSame(job, cat.fetch()); assertEquals(FetchPhase.GROUND, job.phase());
    }

    @Test void catInspectionRestoresItsPreviousCrouchWhenItClaimsOrLeavesTheRace() {
        pet.order(PetOrder.STAY); var cat = cat(false); var body = (Cat) runtime.entity(cat);
        var item = land(throwToy()); body.teleport(item.getLocation());
        actions.fetchActions().step(cat, body, 1000);
        assertTrue(body.isSneaking()); assertEquals(FetchPhase.GROUND, cat.fetch().phase());
        actions.fetchActions().step(cat, body, 2499);
        assertTrue(body.isSneaking()); assertEquals(FetchPhase.GROUND, cat.fetch().phase());
        actions.fetchActions().step(cat, body, 2500);
        assertFalse(body.isSneaking()); assertEquals(.3, body.getVelocity().getY(), .0001);
        assertEquals(FetchPhase.GROUND, cat.fetch().phase(), "Pouncing precedes pickup");
        actions.fetchActions().step(cat, body, 3500);
        assertFalse(body.isSneaking()); assertEquals(FetchPhase.CARRY, cat.fetch().phase());
        actions.releaseFetch(cat, owner, true); assertOnePlainToy(); items().getFirst().remove();
        body.setSneaking(true); body.teleport(new Location(world, 3, 64, 0));
        item = land(throwToy()); body.teleport(item.getLocation());
        actions.fetchActions().step(cat, body, 4000);
        actions.releaseFetch(cat, owner, true);
        assertTrue(body.isSneaking(), "The pre-existing crouch survives cancellation"); assertOnePlainToy();
    }

    @Test void modeledCatOnlyUsesNativeCrouchWhenItsModelCanRenderThePose() {
        pet.order(PetOrder.STAY);
        var cat = cat(false); var body = (Cat) runtime.entity(cat);
        yaml.set("pets.cat.model", "feline");
        for (boolean available : new boolean[]{false, true}) {
            modelClips = available ? java.util.Set.of("crouch") : java.util.Set.of();
            runtime.config(CompanionConfig.load(runtime.plugin(), yaml));
            var item = land(throwToy()); body.teleport(item.getLocation());
            actions.fetchActions().step(cat, body, 1000);
            assertEquals(available, body.isSneaking());
            assertTrue(cat.fetch().stalking(cat.id())); assertEquals(FetchPhase.GROUND, cat.fetch().phase());
            actions.releaseFetch(cat, owner, true);
            assertFalse(body.isSneaking()); assertOnePlainToy(); items().getFirst().remove();
        }
    }

    @Test void ownerWorldChangeStashesOwnCarriedToyWithoutDroppingItInAnotherWorld() {
        carry(pet); owner.teleport(new Location(server.addSimpleWorld("other"), 0, 64, 0));
        actions.fetchActions().step(pet, (Mob) runtime.entity(pet));
        assertNull(pet.fetch()); assertTrue(toy.isSimilar(ToyItems.decode(pet.carriedToy()))); assertTrue(items().isEmpty());
    }

    @Test void followerStopsAtItsFormationPositionUntilTheCarrierMoves() {
        var follower = wolf(owner.getUniqueId(), 3); carry(pet); var body = (Mob) runtime.entity(follower);
        Location destination = actions.fetchActions().destination(follower); body.teleport(destination);
        actions.fetchActions().step(follower, body);
        assertNull(targets.get(body.getUniqueId())); assertSame(pet.fetch(), follower.fetch());
        runtime.entity(pet).teleport(new Location(world, 14, 64, 0));
        actions.fetchActions().step(follower, body, System.currentTimeMillis() + 300);
        assertEquals(actions.fetchActions().destination(follower), targets.get(body.getUniqueId()));
    }

    @Test void swimmingFollowerKeepsFollowingTheCarrierUntilDeliveryThenReturnsToItsOwner() {
        Pet follower = foreignPet();
        Pet other = wolf(owner.getUniqueId(), 4);
        carry(pet); var job = pet.fetch();
        var body = (PetFetchWorkflowTest.FetchWolf) runtime.entity(follower);
        var carrier = (PetFetchWorkflowTest.FetchWolf) runtime.entity(pet);
        var otherBody = (PetFetchWorkflowTest.FetchWolf) runtime.entity(other);
        Player petOwner = Bukkit.getPlayer(follower.ownerId());
        petOwner.teleport(new Location(world, 14, 64, 8));
        body.teleport(new Location(world, 12, 64, 0));
        double speed = actions.fetchActions().movementSpeed(follower);
        follower.bond(100); follower.need(Need.CLEANLINESS, 0);
        int carrierRequests = carrier.navigationRequests, otherRequests = otherBody.navigationRequests;
        var velocity = new org.bukkit.util.Vector(.08, -.02, .03); body.setVelocity(velocity);
        body.inWater = true;
        WaterNavigationGoal.ensure(runtime, follower, body);
        var goal = server.getMobGoals().getGoal(body, com.destroystokyo.paper.entity.ai.GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "water_navigation")));
        long now = System.currentTimeMillis();
        assertFalse(goal.shouldActivate()); goal.tick();
        actions.fetchActions().step(follower, body, now + 500);
        assertSame(job, follower.fetch()); assertEquals(Activity.PLAYING, follower.activity());
        assertFalse(actions.roaming().returningFromFetch(follower));
        assertEquals(actions.fetchActions().destination(follower), targets.get(body.getUniqueId()));
        assertEquals(speed, speeds.get(body.getUniqueId())); assertEquals(velocity, body.getVelocity());
        assertSame(job, pet.fetch()); assertSame(job, other.fetch());
        assertEquals(FetchPhase.CARRY, job.phase()); assertEquals(pet.id(), job.carrierId());
        assertEquals(carrierRequests, carrier.navigationRequests); assertEquals(otherRequests, otherBody.navigationRequests);
        assertTrue(items().isEmpty());
        carrier.teleport(owner.getLocation()); actions.fetchActions().step(pet, carrier, now + 1000);
        assertNull(pet.fetch()); assertNull(other.fetch()); assertNull(follower.fetch());
        assertEquals(Activity.ATTENDING, follower.activity());
        assertTrue(actions.roaming().returningFromFetch(follower));
        assertEquals(petOwner.getLocation(), targets.get(body.getUniqueId()));
        assertEquals(speed, speeds.get(body.getUniqueId())); assertEquals(velocity, body.getVelocity());
        assertFalse(goal.shouldActivate()); assertOnePlainToy();
        actions.fetchActions().returned(pet, owner); actions.fetchActions().tick(now + 1500);
        assertOnePlainToy();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void swimmingSharedFollowerDetachesAtTheNextRaceValidationWhenItsOwnerIsUnavailable(boolean offline) {
        Pet follower = foreignPet(); carry(pet); var job = pet.fetch();
        var body = (PetFetchWorkflowTest.FetchWolf) runtime.entity(follower);
        PlayerMock petOwner = (PlayerMock) Bukkit.getPlayer(follower.ownerId());
        if (offline) petOwner.disconnect();
        else petOwner.teleport(new Location(server.addSimpleWorld("owner-away"), 0, 64, 0));
        body.inWater = true;
        actions.fetchActions().step(follower, body);
        actions.fetchActions().tick(System.currentTimeMillis());
        assertNull(follower.fetch()); assertEquals(Activity.NONE, follower.activity());
        assertFalse(actions.roaming().returningFromFetch(follower));
        assertNull(actions.roaming().destination(follower)); assertSame(job, pet.fetch());
        assertTrue(items().isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"tired", "owner-missing", "owner-away", "body-missing"})
    void deliveryOnlyReleasesFollowersThatCannotReturn(String unavailable) {
        Pet follower = foreignPet(); carry(pet);
        switch (unavailable) {
            case "tired" -> follower.need(Need.ENERGY, 20);
            case "owner-missing" -> ((PlayerMock) Bukkit.getPlayer(follower.ownerId())).disconnect();
            case "owner-away" -> Bukkit.getPlayer(follower.ownerId()).teleport(
                    new Location(server.addSimpleWorld("owner-away"), 0, 64, 0));
            case "body-missing" -> runtime.entity(follower).remove();
            default -> fail("Unknown unavailability");
        }
        var carrier = (Mob) runtime.entity(pet);
        carrier.teleport(owner.getLocation()); actions.fetchActions().step(pet, carrier);
        assertNull(follower.fetch()); assertEquals(Activity.NONE, follower.activity());
        assertFalse(actions.roaming().returningFromFetch(follower));
        assertNull(actions.roaming().destination(follower)); assertOnePlainToy();
    }

    @Test void deliveryOnlyReleasesAFollowerWhenItsResolvedOwnerIsOffline() {
        Pet follower = foreignPet(); carry(pet);
        Player offlineOwner = org.mockito.Mockito.mock(Player.class);
        try (var bukkit = org.mockito.Mockito.mockStatic(Bukkit.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            bukkit.when(() -> Bukkit.getPlayer(follower.ownerId())).thenReturn(offlineOwner);
            var carrier = (Mob) runtime.entity(pet);
            carrier.teleport(owner.getLocation()); actions.fetchActions().step(pet, carrier);
        }
        assertNull(follower.fetch()); assertEquals(Activity.NONE, follower.activity());
        assertFalse(actions.roaming().returningFromFetch(follower));
        assertNull(actions.roaming().destination(follower)); assertOnePlainToy();
    }

    @Test void vanishedForeignCarrierDropsItsToyAtTheLastObservedCarrierPosition() {
        pet.order(PetOrder.STAY); var foreign = foreignPet(); carry(foreign);
        Entity body = runtime.entity(foreign); body.teleport(new Location(world, 18, 64, 2));
        actions.fetchActions().tick(System.currentTimeMillis());
        Location last = body.getLocation(); body.remove();
        actions.fetchActions().tick(System.currentTimeMillis() + 1);
        assertNull(foreign.fetch()); assertNull(foreign.carriedToy()); assertOnePlainToy();
        assertEquals(last, items().getFirst().getLocation());
    }

    @Test void neglectDeathDuringCarryRecoversTheToyAfterTheCarrierLeavesTheStore() throws Exception {
        carry(pet); var body = runtime.entity(pet); Location at = body.getLocation();
        actions.fetchActions().tick(System.currentTimeMillis());
        yaml.set("care.death-on-neglect", true); runtime.config(CompanionConfig.load(runtime.plugin(), yaml));
        pet.need(Need.HEALTH, 0); pet.illness(Illness.SICK);
        var ticker = new PetTicker(runtime, actions);
        // Advance the ticker's clock, keeping the death transition itself in the actual care workflow.
        var clock = PetTicker.class.getDeclaredField("lastCareAt"); clock.setAccessible(true);
        clock.setLong(ticker, System.currentTimeMillis() - 1000);
        ticker.run();
        assertTrue(pet.dead()); assertNull(runtime.store().get(pet.id())); assertFalse(body.isValid());
        assertOnePlainToy(); assertEquals(at, items().getFirst().getLocation());
        actions.stashLooseToys(); assertOnePlainToy();
    }

    @Test void shutdownStoresOwnCarriedToyAndPersistsItsExactMetadataOnlyOnce() {
        carry(pet); actions.stashLooseToys(); actions.stashLooseToys();
        assertNull(pet.fetch()); assertTrue(items().isEmpty()); assertTrue(toy.isSimilar(ToyItems.decode(pet.carriedToy())));
        assertTrue(runtime.store().save()); var reopened = new PetStore(runtime.plugin()); assertTrue(reopened.load());
        assertTrue(toy.isSimilar(ToyItems.decode(reopened.get(pet.id()).carriedToy())));
    }

    @Test void corruptPersistedToyIsLoggedAndDoesNotCreateAnUnrelatedItem() throws Exception {
        pet.carriedToy(ToyItems.encode(toy)); assertTrue(runtime.store().save());
        var file = runtime.plugin().getDataFolder().toPath().resolve("pets.yml");
        var saved = YamlConfiguration.loadConfiguration(file.toFile());
        saved.set("pets." + pet.id() + ".carried-toy", "stack:not-base64!"); saved.save(file.toFile());
        var reopened = new PetStore(runtime.plugin()); assertTrue(reopened.load());
        actions.dropPlain(owner.getLocation(), reopened.get(pet.id()).carriedToy());
        assertTrue(items().isEmpty()); assertTrue(warnings.stream().anyMatch(message -> message.contains("Could not restore saved toy")));
        actions.dropPlain(owner.getLocation(), ToyItems.encode(toy)); assertOnePlainToy();
    }

    public static class FailUnimplemented implements TestExecutionExceptionHandler, LifecycleMethodExecutionExceptionHandler {
        @Override public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable { check(throwable); }
        @Override public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable { check(throwable); }
        private void check(Throwable throwable) throws Throwable {
            if (throwable instanceof UnimplementedOperationException) throw new AssertionError("Implement the required Bukkit fixture boundary", throwable);
            throw throwable;
        }
    }
    @Test void disconnectingTheOwnerDropsForeignCarriedToyAtThePetExactlyOnce() {
        pet.order(PetOrder.STAY); Pet foreign = foreignPet(); carry(foreign);
        Location dropAt = runtime.entity(foreign).getLocation();
        ((PlayerMock) Bukkit.getPlayer(foreign.ownerId())).disconnect();
        long now = System.currentTimeMillis();
        actions.fetchActions().tick(now); actions.fetchActions().tick(now + 500);
        assertNull(foreign.fetch()); assertNull(foreign.carriedToy());
        assertOnePlainToy(); assertEquals(dropAt, items().getFirst().getLocation());
    }

    @Test void foreignOwnerLeashBreakReleasesGroundToyAndAirToySurvivesLanding() {
        pet.order(PetOrder.STAY); Pet foreign = foreignPet();
        Player petOwner = Bukkit.getPlayer(foreign.ownerId());
        Snowball ball = throwToy(); assertNotNull(foreign.fetch());
        petOwner.teleport(new Location(world, 13, 64, 0));
        actions.fetchActions().tick(System.currentTimeMillis()); assertNull(foreign.fetch());
        land(ball); assertOnePlainToy(); items().getFirst().remove();
        petOwner.teleport(new Location(world, 10, 64, 0)); runtime.random().setSeed(4096);
        Item ground = land(throwToy()); assertNotNull(foreign.fetch());
        petOwner.teleport(new Location(world, 12, 64, 0));
        actions.fetchActions().tick(System.currentTimeMillis()); assertNotNull(foreign.fetch());
        petOwner.teleport(new Location(world, 12.01, 64, 0));
        actions.fetchActions().tick(System.currentTimeMillis()); assertNull(foreign.fetch());
        assertTrue(ground.isValid()); assertOnePlainToy(); assertNull(foreign.carriedToy());
    }

    @Test void anyOwnerTrickImmediatelyAbandonsForeignFetchAndCallingItsNameAlsoLocksOutVisitors() throws Exception {
        pet.order(PetOrder.STAY); Pet foreign = foreignPet(); carry(foreign);
        Player petOwner = Bukkit.getPlayer(foreign.ownerId());
        foreign.bindWord("speak", Trick.SPEAK); foreign.progress(Trick.SPEAK, 100);
        actions.onChat(petOwner, foreign.name() + " speak");
        assertNull(foreign.fetch()); assertNull(foreign.carriedToy()); assertOnePlainToy();
        assertFalse(actions.anticipation().attentive(foreign, owner, System.currentTimeMillis()));
        owner.getInventory().setItemInMainHand(toy.clone());
        actions.anticipation().tick(System.currentTimeMillis()); assertFalse(actions.anticipation().active(foreign));
        actions.anticipation().clear(); runtime.entity(foreign).teleport(new Location(world, 3, 64, 0)); runtime.random().setSeed(4096);
        actions.anticipation().tick(System.currentTimeMillis()); assertTrue(actions.anticipation().active(foreign));
        actions.onChat(petOwner, foreign.name());
        assertFalse(actions.anticipation().active(foreign));
        actions.anticipation().tick(System.currentTimeMillis()); assertFalse(actions.anticipation().active(foreign));
    }}
