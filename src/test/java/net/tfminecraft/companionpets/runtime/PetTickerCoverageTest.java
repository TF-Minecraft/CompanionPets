package net.tfminecraft.companionpets.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.session.TrainingSession;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.testutil.GoalServerMock;
import net.tfminecraft.companionpets.testutil.CollisionWorldMock;
import net.tfminecraft.companionpets.visual.PetVisual;
import net.tfminecraft.companionpets.visual.AnimationController;
import net.tfminecraft.companionpets.visual.AnimationPlayer;
import net.tfminecraft.companionpets.config.PetAppearance;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;
import org.mockbukkit.mockbukkit.world.ChunkMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

@ExtendWith(PetTickerCoverageTest.FailUnimplemented.class)
class PetTickerCoverageTest {
    record Emission(Particle particle, int count, Object data) {}

    private GoalServerMock server;
    private WorldMock world;
    private PlayerMock owner;
    private WolfMock body;
    private Pet pet;
    private PetRuntime runtime;
    private PetActions actions;
    private PetTicker ticker;
    private YamlConfiguration yaml;
    private AnimationController controller;
    private AnimationPlayer animations;
    private final List<Emission> particles = new ArrayList<>();
    private final List<Sound> sounds = new ArrayList<>();
    private final List<Location> navigation = new ArrayList<>();
    private int stops, spawns, removals, lastChunkX, lastChunkZ, velocityUpdates;
    private boolean chunksLoaded = true, entitiesLoaded = true, modeled, heldMovement, swimming;
    private long now;

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(new GoalServerMock());
        world = new CollisionWorldMock() {
            @Override public boolean isChunkLoaded(int x, int z) { return chunksLoaded; }
            @Override public ChunkMock getChunkAt(int x, int z) {
                lastChunkX = x; lastChunkZ = z;
                return new ChunkMock(this, x, z) {
                    @Override public boolean isEntitiesLoaded() { return entitiesLoaded; }
                    @Override public Entity[] getEntities() {
                        return world.getEntities().stream().filter(entity ->
                            entity.getLocation().getBlockX() >> 4 == x && entity.getLocation().getBlockZ() >> 4 == z)
                            .toArray(Entity[]::new);
                    }
                };
            }
            @Override public <T extends Entity> T spawn(Location at, Class<T> type) {
                if (type != Wolf.class) return super.spawn(at, type);
                spawns++;
                return type.cast(wolf(at));
            }
            @Override public BlockMock getBlockAt(int x, int y, int z) {
                return new BlockMock(y == 63 ? Material.STONE : Material.AIR, new Location(this, x, y, z)) {
                    @Override public boolean isPassable() { return !getType().isSolid(); }
                };
            }
            @Override public void playSound(Location at, Sound sound, float volume, float pitch) { sounds.add(sound); }
            @Override public void spawnParticle(Particle particle, Location at, int count,
                    double x, double y, double z, double extra) { particles.add(new Emission(particle, count, null)); }
            @Override public <T> void spawnParticle(Particle particle, Location at, int count,
                    double x, double y, double z, double extra, T data) { particles.add(new Emission(particle, count, data)); }
        };
        server.addWorld(world);
        owner = server.addPlayer();
        owner.teleport(new Location(world, 0, 64, 0));
        owner.openInventory(server.createInventory(null, 9));
        var plugin = MockBukkit.createMockPlugin();
        yaml = new YamlConfiguration();
        yaml.loadFromString("""
                care:
                  hunger-minutes-to-critical: 0
                  mood-minutes-to-critical: 0
                  energy-minutes-to-critical: 0
                  dirty-every-minutes: 0
                  health-regen-per-minute: 0
                  bond-gain-per-minute: 0
                  bond-loss-per-minute: 0
                moments: {enabled: false, belly-up: {enabled: false}, greeting: {enabled: false}}
                social: {enabled: false}
                items: {toys: [STICK], treats: [COD], foods: [{item: BEEF, hunger: 35}]}
                pets:
                  wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG, default-tricks: []}
                """);
        animations = mock(AnimationPlayer.class);
        when(animations.play(any(), anyBoolean())).thenReturn(true);
        when(animations.playing(anyString())).thenReturn(true);
        when(animations.length(anyString())).thenReturn(5.0);
        controller = new AnimationController(animations, java.util.Map.of(), () -> now);
        var visual = new PetVisual() {
            @Override public void apply(Entity entity, PetTypeDef type) { }
            @Override public void cancelAction(Entity entity) { controller.cancelAction(); }
            @Override public boolean playClip(Entity entity, PetTypeDef type, String clip, double duration) {
                return controller.playCustom(new PetAppearance.Clip(clip, 1, .15), duration);
            }
            @Override public boolean attached(Entity entity) { return modeled; }
            @Override public boolean holdsMovement(Entity entity) { return heldMovement; }
            @Override public void removeBody(Entity entity) { removals++; PetVisual.super.removeBody(entity); }
        };
        var key = new NamespacedKey(plugin, "pet");
        var store = new PetStore(plugin);
        assertTrue(store.load());
        runtime = new PetRuntime(plugin, CompanionConfig.load(plugin, yaml), store, new Sessions(),
            new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        actions = new PetActions(runtime);
        ticker = new PetTicker(runtime, actions);
        pet = new Pet(UUID.randomUUID(), owner.getUniqueId(), "wolf", "Toby", PetSex.MALE);
        for (Need need : Need.values()) pet.need(need, 80);
        pet.bond(40);
        body = wolf(owner.getLocation().add(1, 0, 0));
        body.getPersistentDataContainer().set(key, PersistentDataType.STRING, pet.id().toString());
        store.add(pet);
        runtime.remember(pet, body);
        runtime.random().setSeed(0);
        now = System.currentTimeMillis() + 10_000;
    }

    private WolfMock wolf(Location at) {
        var wolf = new WolfMock(server, UUID.randomUUID()) {
            @Override public void setVelocity(org.bukkit.util.Vector velocity) { velocityUpdates++; super.setVelocity(velocity); }
            @Override public boolean isInWater() { return swimming; }
            @Override public boolean isOnGround() { return true; }
            @Override public boolean hasLineOfSight(Entity target) { return true; }
            @Override public float getBodyYaw() { return 0; }
            @Override public void setBodyYaw(float yaw) { }
            @Override public void lookAt(Entity target) { }
            @Override public void lookAt(Entity target, float speed, float pitch) { }
            @Override public void lookAt(Location target) { }
            @Override public void lookAt(Location target, float speed, float pitch) { }
            @Override public void setRemoveWhenFarAway(boolean remove) { }
            @Override public Pathfinder getPathfinder() {
                return (Pathfinder) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Pathfinder.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "stopPathfinding" -> { stops++; yield null; }
                        case "hasPath" -> false;
                        case "getEntity" -> this;
                        case "moveTo" -> {
                            if (args[0] instanceof Location target) navigation.add(target.clone());
                            yield true;
                        }
                        case "findPath" -> Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] {Pathfinder.PathResult.class}, (p, m, a) -> switch (m.getName()) {
                                case "canReachFinalPoint" -> true;
                                case "getFinalPoint" -> at;
                                default -> null;
                            });
                        default -> throw new AssertionError("Unexpected navigation: " + method.getName());
                    });
            }
        };
        server.registerEntity(wolf);
        wolf.teleport(at);
        return wolf;
    }

    @AfterEach void cleanup() {
        if (actions != null) actions.holograms().clear();
        if (runtime != null) runtime.store().close();
        if (owner != null) PetFx.clearPlayer(owner.getUniqueId());
        MockBukkit.unmock();
    }

    private void configure(String key, Object value) {
        yaml.set(key, value);
        runtime.config(CompanionConfig.load(runtime.plugin(), yaml));
    }

    // Exercise the production phases with explicit clock arguments; no wall-clock sleeps or state injection.
    private void care(long at, long elapsed) throws Exception { invoke("care", new Class<?>[] {long.class, long.class}, at, elapsed); }
    private void move(long at) throws Exception { invoke("move", new Class<?>[] {long.class}, at); }
    private void watch(long at) throws Exception { invoke("watchTraining", new Class<?>[] {long.class}, at); }
    private void invoke(String name, Class<?>[] parameters, Object... values) throws Exception {
        var method = PetTicker.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        try { method.invoke(ticker, values); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw (Error) failure.getCause();
        }
    }

    private String bars() {
        StringBuilder result = new StringBuilder();
        Component message;
        while ((message = owner.nextActionBar()) != null) result.append(PlainTextComponentSerializer.plainText().serialize(message)).append('\n');
        return result.toString();
    }
    private String messages() {
        StringBuilder result = new StringBuilder();
        String message;
        while ((message = owner.nextMessage()) != null) result.append(message).append('\n');
        return result.toString();
    }
    private void assertSavedNeeds(double hunger) {
        assertTrue(runtime.store().save());
        var loaded = new PetStore(runtime.plugin());
        assertTrue(loaded.load());
        assertEquals(hunger, loaded.get(pet.id()).need(Need.HUNGER), .00001);
        loaded.close();
    }

    @Test void postureStopsASlideOnceAndLeavesAStillBodyAloneOnRepeatedPasses() throws Exception {
        pet.order(PetOrder.SIT);
        body.setVelocity(new org.bukkit.util.Vector(.3, -.2, .4));
        velocityUpdates = 0;
        move(now);
        assertEquals(1, velocityUpdates);
        assertEquals(new org.bukkit.util.Vector(0, -.2, 0), body.getVelocity());
        var goal = server.getMobGoals().getGoal(body, GoalKey.of(Mob.class,
                new NamespacedKey(runtime.plugin(), "posture_navigation")));
        goal.start();
        for (int pass = 0; pass < 6; pass++) {
            move(now + 500 * pass);
            goal.tick();
        }
        assertEquals(1, velocityUpdates);
        assertTrue(body.isAware());
    }

    @Test void sittingPetKeepsItsCustomTrickAcrossRepeatedTickerPasses() throws Exception {
        pet.order(PetOrder.SIT);
        move(now);
        assertTrue(runtime.visual().playClip(body, runtime.config().type(pet.typeId()), "wave", 5));
        for (int pass = 0; pass < 6; pass++) {
            now += 500;
            move(now);
            assertTrue(controller.holdsMovement());
        }
        verify(animations, never()).stop("wave");
        actions.clearInteractions(pet);
        assertFalse(controller.holdsMovement());
        verify(animations).stop("wave");
    }

    @Test void missingBodyFreezesCareUntilLoadedEntitiesAndTheFullGracePeriod() throws Exception {
        configure("care.hunger-minutes-to-critical", 1);
        body.remove();
        pet.place(world.getName(), -0.5, 64, -16.5, 0);
        chunksLoaded = false;
        care(now, 60_000);
        chunksLoaded = true;
        entitiesLoaded = false;
        care(now + 10_000, 60_000);
        assertEquals(0, spawns);
        assertEquals(80, pet.need(Need.HUNGER));
        entitiesLoaded = true;
        care(now + 20_000, 1000);
        care(now + 24_999, 1000);
        assertNull(runtime.entity(pet));
        assertEquals(0, spawns);
        assertEquals(-1, lastChunkX);
        assertEquals(-2, lastChunkZ);
        care(now + 25_000, 1000);
        Entity restored = runtime.entity(pet);
        assertNotNull(restored);
        assertNotEquals(body.getUniqueId(), restored.getUniqueId());
        assertEquals(1, spawns);
        assertTrue(pet.need(Need.HUNGER) < 80);
        assertSavedNeeds(pet.need(Need.HUNGER));
        care(now + 50_000, 1000);
        assertEquals(1, spawns, "A restored body is reused rather than duplicated");
    }

    @Test void failedSpawnRetriesAfterAnotherGracePeriodAndNeverDecaysAMissingPet() throws Exception {
        body.remove();
        // An enabled provider with an unavailable API is a supported reflective integration failure.
        MockBukkit.createMockPlugin("MythicMobs");
        configure("pets.wolf.mythic-mob", "unavailable_pet");
        var failedAttempts = new java.util.concurrent.atomic.AtomicInteger();
        runtime.plugin().getLogger().addHandler(new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                if (record.getMessage().equals("Could not spawn MythicMob unavailable_pet")) failedAttempts.incrementAndGet();
            }
            @Override public void flush() { }
            @Override public void close() { }
        });
        care(now, 60_000);
        care(now + 5000, 60_000);
        assertEquals(1, failedAttempts.get());
        assertEquals(0, spawns);
        assertEquals(80, pet.need(Need.HUNGER));
        care(now + 9999, 60_000);
        assertEquals(1, failedAttempts.get());
        configure("pets.wolf.mythic-mob", null);
        care(now + 10_000, 1);
        assertNotNull(runtime.entity(pet));
        assertEquals(1, spawns);
        assertFalse(pet.dead());
    }

    @Test void unloadingEntitiesResetsTheMissingBodyGraceInsteadOfSpawningOnTheFirstReloadedTick() throws Exception {
        body.remove();
        care(now, 1);
        entitiesLoaded = false;
        care(now + 4900, 1);
        entitiesLoaded = true;
        care(now + 20_000, 1);
        assertEquals(0, spawns);
        care(now + 25_000, 1);
        assertEquals(1, spawns);
    }

    @Test void unknownWorldRemovedTypesAndDeadRecordsNeverRestoreOrDecay() throws Exception {
        body.remove();
        pet.place("not_loaded", 0, 64, 0, 0);
        care(now, 60_000);
        configure("pets.wolf", null);
        care(now + 60_000, 60_000);
        pet.dead(true);
        care(now + 120_000, 60_000);
        assertEquals(0, spawns);
        assertEquals(80, pet.need(Need.HUNGER));
        assertSame(pet, runtime.store().get(pet.id()));
    }

    @Test void storedCareHonorsBothItsDecaySettingAndOwnerPresenceWithoutABody() throws Exception {
        body.remove();
        pet.stored(true);
        configure("care.hunger-minutes-to-critical", 1);
        care(now, 60_000);
        assertEquals(80, pet.need(Need.HUNGER));
        configure("care.decay-while-stored", true);
        care(now + 1000, 60_000);
        assertTrue(pet.need(Need.HUNGER) < 80);
        double hunger = pet.need(Need.HUNGER);
        owner.disconnect();
        care(now + 2000, 60_000);
        assertEquals(hunger, pet.need(Need.HUNGER));
        assertEquals(0, spawns);
    }

    @Test void incompatibleBodiesAreHeldAndCannotAdvanceCare() throws Exception {
        configure("care.hunger-minutes-to-critical", 1);
        configure("pets.wolf.entity", "CAT");
        care(now, 60_000);
        move(now);
        assertEquals(80, pet.need(Need.HUNGER));
        assertTrue(stops > 0);
        assertEquals(0, spawns);
        assertSame(body, runtime.entity(pet));
    }

    @Test void exhaustedPetsSleepRecoverAndWakeUnlessTheirOwnerOrderedLay() throws Exception {
        pet.need(Need.ENERGY, 20);
        care(now, 1);
        assertEquals(Activity.SLEEPING, pet.activity());
        assertTrue(bars().contains("lies down to rest"));
        assertTrue(world.getEntities().stream().anyMatch(entity -> entity instanceof ArmorStand && entity.isValid()));
        care(now + 480_000, 480_000);
        assertEquals(100, pet.need(Need.ENERGY));
        assertEquals(Activity.NONE, pet.activity());
        assertTrue(bars().contains("fully rested"));
        move(now + 480_000);
        assertFalse(world.getEntities().stream().anyMatch(entity -> entity instanceof ArmorStand && entity.isValid()));

        pet.order(PetOrder.LAY);
        pet.need(Need.ENERGY, 20);
        care(now + 500_000, 1);
        care(now + 980_000, 480_000);
        assertEquals(100, pet.need(Need.ENERGY));
        assertEquals(Activity.SLEEPING, pet.activity());
    }

    @Test void swimmingPetDoesNotLieDownOrRecoverSleepEnergy() throws Exception {
        swimming = true;
        pet.need(Need.ENERGY, 20);
        care(now, 60_000);
        move(now);
        assertEquals(Activity.NONE, pet.activity());
        assertEquals(20, pet.need(Need.ENERGY));
        assertTrue(body.getVelocity().getY() >= .16);
        assertFalse(body.isSitting());
    }

    @Test void lowNeedsAndIllnessProduceTheirMessagesParticlesAndSavedCareState() throws Exception {
        configure("care.hunger-minutes-to-critical", 1);
        pet.need(Need.HUNGER, 61);
        care(now, 2000);
        assertTrue(pet.need(Need.HUNGER) < 60);
        assertTrue(bars().contains("stomach is rumbling"));
        Emission food = particles.stream().filter(p -> p.particle() == Particle.ITEM).findFirst().orElseThrow();
        assertEquals(Material.BEEF, ((ItemStack) food.data()).getType());
        long notices = particles.size();
        care(now + 1, 1);
        assertEquals(notices, particles.size(), "A low need announces only its transition");
        pet.need(Need.HUNGER, 1);
        configure("care.minutes-until-unwell", .01);
        configure("care.minutes-until-sick", .01);
        care(now + 1000, 1000);
        assertEquals(Illness.UNWELL, pet.illness());
        assertFalse(bars().isBlank());
        care(now + 2000, 1000);
        assertEquals(Illness.SICK, pet.illness());
        assertFalse(bars().isBlank());
        assertSavedNeeds(pet.need(Need.HUNGER));
    }

    @Test void neglectDeathDeletesVanillaBodyAndDurableRecordExactlyOnce() throws Exception {
        neglect();
        pet.carriedToy("STICK");
        care(now, 60_000);
        assertTrue(pet.dead());
        assertNull(runtime.store().get(pet.id()));
        assertFalse(body.isValid());
        assertEquals(1, removals);
        assertTrue(messages().contains("passed away"));
        assertTrue(Files.readString(runtime.plugin().getDataFolder().toPath().resolve("pet-deletions.log"))
            .contains(pet.id().toString()));
        assertTrue(runtime.store().save());
        var loaded = new PetStore(runtime.plugin());
        assertTrue(loaded.load());
        assertNull(loaded.get(pet.id()));
        loaded.close();
        care(now + 60_000, 60_000);
        assertEquals(1, removals);
    }

    @Test void modeledNeglectDeathKeepsTheBodyForItsDeathAnimationAndPlaysTheConfiguredCue() throws Exception {
        neglect();
        modeled = true;
        body.setSilent(true);
        care(now, 60_000);
        assertNull(runtime.store().get(pet.id()));
        assertEquals(0, body.getHealth());
        assertEquals(0, removals);
        assertTrue(sounds.contains(Sound.ENTITY_WOLF_DEATH), sounds.toString());
    }

    @Test void failedDeathJournalPreservesTheBodyAndBlocksUnsafePersistence() throws Exception {
        neglect();
        assertTrue(runtime.store().save());
        Files.createDirectory(runtime.plugin().getDataFolder().toPath().resolve("pet-deletions.log"));
        care(now, 60_000);
        assertSame(pet, runtime.store().get(pet.id()));
        assertTrue(body.isValid());
        assertEquals(0, removals);
        assertFalse(runtime.store().save());
        assertFalse(runtime.store().canRestoreBodies());
    }

    private void neglect() {
        configure("care.death-on-neglect", true);
        pet.need(Need.HUNGER, 0);
        pet.need(Need.HEALTH, 1);
        pet.illness(Illness.SICK);
    }

    @Test void favoriteToyReplacementNotifiesAndCarriedToysReturnOnlyWhenTheOwnerIsNear() throws Exception {
        pet.favoriteToy("SNOWBALL");
        pet.carriedToy("STICK");
        owner.teleport(owner.getLocation().add(100, 0, 0));
        owner.openInventory(server.createInventory(null, 9));
        care(now, 1);
        assertEquals("STICK", pet.favoriteToy());
        assertTrue(messages().contains("new favorite toy"));
        assertEquals("STICK", pet.carriedToy());
        owner.teleport(body.getLocation().add(1, 0, 0));
        owner.openInventory(server.createInventory(null, 9));
        care(now + 1, 1);
        assertNull(pet.carriedToy());
        List<Item> items = world.getEntities().stream().filter(entity -> entity instanceof Item).map(entity -> (Item) entity).toList();
        assertEquals(1, items.size());
        assertEquals(Material.STICK, items.getFirst().getItemStack().getType());
        care(now + 2, 1);
        assertEquals(1, world.getEntities().stream().filter(entity -> entity instanceof Item).count());
        configure("items.toys", List.of());
        care(now + 3, 1);
        assertNull(pet.favoriteToy());
    }

    @Test void completedPlayAwardsMoodAndSpendsEnergyOnlyOnce() throws Exception {
        pet.activity(Activity.PLAYING);
        pet.playUntilMillis(now + 1000);
        pet.need(Need.MOOD, 40);
        care(now, 1);
        assertEquals(Activity.PLAYING, pet.activity());
        assertEquals(40, pet.need(Need.MOOD));
        care(now + 1000, 1);
        assertEquals(Activity.NONE, pet.activity());
        assertEquals(62, pet.need(Need.MOOD));
        assertEquals(77, pet.need(Need.ENERGY));
        assertTrue(particles.contains(new Emission(Particle.HEART, 3, null)));
        assertTrue(sounds.contains(Sound.BLOCK_NOTE_BLOCK_BELL));
        care(now + 2000, 1);
        assertEquals(62, pet.need(Need.MOOD));
        assertEquals(77, pet.need(Need.ENERGY));
    }

    @Test void playingPetApproachesThenJumpsNearbyAndStopsWhenTheOwnerChangesWorld() throws Exception {
        pet.activity(Activity.PLAYING);
        pet.playUntilMillis(Long.MAX_VALUE);
        body.teleport(new Location(world, 8, 64, 0));
        move(now);
        assertEquals(owner.getLocation(), navigation.getLast());
        PlayNavigationGoal goal = playGoal();
        assertTrue(goal.shouldActivate());
        assertTrue(goal.shouldStayActive());
        assertEquals(EnumSet.of(GoalType.MOVE, GoalType.JUMP), goal.getTypes());
        int routes = navigation.size();
        goal.tick();
        assertEquals(routes, navigation.size(), "Repeated native ticks respect the half-second navigation interval");
        body.teleport(owner.getLocation().add(1, 0, 0));
        body.setTicksLived(31);
        goal.stop();
        move(now + 1);
        assertSame(goal, playGoal());
        assertEquals(.48, body.getVelocity().getY());
        assertTrue(sounds.contains(Sound.BLOCK_NOTE_BLOCK_PLING));
        owner.teleport(new Location(server.addSimpleWorld("other"), 0, 64, 0));
        int stopped = stops;
        goal.stop();
        move(now + 2);
        assertTrue(stops > stopped);
        pet.activity(Activity.NONE);
        assertFalse(goal.shouldActivate());
        assertFalse(goal.shouldStayActive());
        goal.tick();
        assertEquals(routes, navigation.size());
    }

    private PlayNavigationGoal playGoal() {
        return (PlayNavigationGoal) server.getMobGoals().getGoal(body,
            GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "play_navigation")));
    }

    @Test void visualMovementHoldAndCombatTargetKeepTheTickerFromIssuingPaths() throws Exception {
        heldMovement = true;
        body.setVelocity(new org.bukkit.util.Vector(1, 0, 1));
        move(now);
        assertEquals(0, body.getVelocity().getX());
        assertEquals(0, body.getVelocity().getZ());
        assertTrue(navigation.isEmpty());
        heldMovement = false;
        body.setTarget(server.addPlayer());
        move(now + 1);
        assertTrue(navigation.isEmpty());
        assertNotNull(body.getTarget());
    }

    @Test void anActiveSocialEncounterRetainsControlOfBothPetsMovement() throws Exception {
        configure("social.enabled", true);
        pet.personality(PetPersonality.FRIENDLY);
        Pet friend = new Pet(UUID.randomUUID(), owner.getUniqueId(), "wolf", "Buddy", PetSex.MALE);
        friend.personality(PetPersonality.FRIENDLY);
        for (Need need : Need.values()) friend.need(need, 80);
        WolfMock friendBody = wolf(owner.getLocation().add(2, 0, 0));
        friendBody.getPersistentDataContainer().set(runtime.petKey(), PersistentDataType.STRING, friend.id().toString());
        runtime.store().add(friend);
        runtime.remember(friend, friendBody);
        assertTrue(actions.social().trigger(owner, pet, "sniff", now));
        assertTrue(actions.social().engaged(pet));
        assertTrue(actions.social().engaged(friend));
        navigation.clear();

        move(now + 1);

        assertTrue(actions.social().engaged(pet));
        assertTrue(actions.social().engaged(friend));
        assertEquals(Activity.NONE, pet.activity());
        assertTrue(navigation.isEmpty(), "The social goal owns paths while the pets meet");
    }

    @Test void lieAndSleepModesStopPathsAndHighBondCanShowAffection() throws Exception {
        pet.order(PetOrder.LAY);
        pet.bond(100);
        long seed = 0;
        while (new java.util.Random(seed).nextInt(40) != 0) seed++;
        runtime.random().setSeed(seed);
        move(now);
        assertTrue(body.isSitting());
        assertTrue(particles.contains(new Emission(Particle.HEART, 1, null)));
        pet.activity(Activity.SLEEPING);
        move(now + 1);
        assertTrue(body.isSitting());
        assertTrue(stops > 0);
    }

    @Test void criticalAndDirtyNeedsShowParticlesWithACooldownAndDistantOwnersHearACry() throws Exception {
        pet.need(Need.HUNGER, 1);
        pet.need(Need.CLEANLINESS, 40);
        body.setTicksLived(41);
        owner.teleport(owner.getLocation().add(100, 0, 0));
        move(now);
        assertTrue(particles.stream().anyMatch(p -> p.particle() == Particle.ITEM));
        assertTrue(particles.stream().anyMatch(p -> p.particle() == Particle.DUST_PLUME));
        assertTrue(particles.stream().anyMatch(p -> p.particle() == Particle.SPLASH));
        long nextCritical = pet.nextCriticalSoundAtMillis();
        long nextCry = pet.nextCryAtMillis();
        assertTrue(nextCritical > now);
        assertTrue(nextCry > now);
        particles.clear();
        move(now + 1);
        assertFalse(particles.stream().anyMatch(p -> p.particle() == Particle.ITEM));
        assertEquals(nextCritical, pet.nextCriticalSoundAtMillis());
        assertEquals(nextCry, pet.nextCryAtMillis());
        pet.illness(Illness.SICK);
        move(nextCritical);
        assertTrue(particles.stream().anyMatch(p -> p.particle() == Particle.SNEEZE));
    }

    @Test void trainingEndsForAStoredOrRemovedPetAndWhenTheOwnerWalksAway() throws Exception {
        owner.getInventory().setItemInMainHand(new ItemStack(Material.COD));
        TrainingSession session = new TrainingSession(pet.id());
        runtime.sessions().training(owner.getUniqueId(), session);
        pet.stored(true);
        watch(now);
        assertNull(runtime.sessions().training(owner.getUniqueId()));
        assertTrue(messages().contains("Pet House"));
        pet.stored(false);
        runtime.sessions().training(owner.getUniqueId(), new TrainingSession(pet.id()));
        owner.teleport(owner.getLocation().add(100, 0, 0));
        watch(now + 1);
        assertNull(runtime.sessions().training(owner.getUniqueId()));
        assertTrue(messages().contains("too far"));
        runtime.sessions().training(owner.getUniqueId(), new TrainingSession(pet.id()));
        assertTrue(runtime.store().remove(pet.id()));
        watch(now + 2);
        assertNull(runtime.sessions().training(owner.getUniqueId()));
    }

    static final class FailUnimplemented implements TestExecutionExceptionHandler, LifecycleMethodExecutionExceptionHandler {
        @Override public void handleTestExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
            throw checked(failure);
        }
        @Override public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
            throw checked(failure);
        }
        private static Throwable checked(Throwable failure) {
            return failure instanceof UnimplementedOperationException
                ? new AssertionError("Ticker fixture requires an unsupported MockBukkit operation", failure) : failure;
        }
    }
}
