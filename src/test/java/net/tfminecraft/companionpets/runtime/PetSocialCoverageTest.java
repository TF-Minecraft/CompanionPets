package net.tfminecraft.companionpets.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.config.BehaviorProfile;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.testutil.GoalServerMock;
import net.tfminecraft.companionpets.testutil.CollisionWorldMock;
import net.tfminecraft.companionpets.visual.PetVisual;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;
import org.mockbukkit.mockbukkit.world.WorldMock;

@ExtendWith(PetSocialCoverageTest.FailUnimplemented.class)
class PetSocialCoverageTest {
    private record Emission(Particle particle, Location at, int count) { }
    private GoalServerMock server;
    private WorldMock world;
    private PlayerMock owner;
    private PetRuntime runtime;
    private PetActions actions;
    private Pet first, second;
    private SocialWolf a, b;
    private YamlConfiguration yaml;
    private long now;
    private boolean blockedSight, floor = true, reachable = true;
    private int ceiling;
    private final List<Emission> particles = new ArrayList<>();
    private final List<Sound> sounds = new ArrayList<>();
    private final Map<UUID, Double> tails = new HashMap<>();
    private final Map<UUID, Location> paths = new HashMap<>();

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(new GoalServerMock());
        world = new CollisionWorldMock() {
            @Override public boolean isChunkLoaded(int x, int z) { return true; }
            @Override public BlockMock getBlockAt(int x, int y, int z) {
                Material type = floor && y == 63 || ceiling != 0 && y == ceiling ? Material.STONE : Material.AIR;
                return new BlockMock(type, new Location(this, x, y, z)) {
                    @Override public boolean isPassable() { return !getType().isSolid(); }
                };
            }
            @Override public void playSound(Location at, Sound sound, float volume, float pitch) { sounds.add(sound); }
            @Override public void playSound(Location at, String sound, float volume, float pitch) { }
            @Override public void spawnParticle(Particle particle, Location at, int count, double x, double y, double z, double extra) {
                particles.add(new Emission(particle, at.clone(), count));
            }
        };
        server.addWorld(world); owner = server.addPlayer(); owner.teleport(new Location(world, 0, 64, 0));
        owner.openInventory(server.createInventory(null, 9));
        var plugin = MockBukkit.createMockPlugin();
        yaml = new YamlConfiguration(); yaml.loadFromString("""
            roaming: {stationary-seconds: 0}
            social: {encounter-chance: 100, chase-chance: 0, search-interval-seconds: 0.5}
            pets: {wolf: {entity: WOLF, behavior: dog, egg: WOLF_SPAWN_EGG}}
            """);
        var visual = new PetVisual() {
            @Override public void apply(Entity entity, PetTypeDef type) { }
            @Override public void wagTail(Entity entity, double hz) { tails.put(entity.getUniqueId(), hz); }
        };
        var key = new NamespacedKey(plugin, "pet"); var store = new PetStore(plugin); assertTrue(store.load());
        runtime = new PetRuntime(plugin, CompanionConfig.load(plugin, yaml), store, new Sessions(),
                new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        actions = new PetActions(runtime);
        first = pet(1); second = pet(2.2); a = (SocialWolf) runtime.entity(first); b = (SocialWolf) runtime.entity(second);
        now = System.currentTimeMillis(); runtime.random().setSeed(1); actions.roaming().tickOwners(now);
    }

    @AfterEach void cleanup() { runtime.store().close(); MockBukkit.unmock(); }

    private Pet pet(double x) {
        Pet result = new Pet(UUID.randomUUID(), owner.getUniqueId(), "wolf", "Friend " + x, PetSex.MALE);
        result.personality(PetPersonality.FRIENDLY); result.bond(100);
        for (Need need : Need.values()) result.need(need, 100);
        SocialWolf body = new SocialWolf(); server.registerEntity(body); body.teleport(new Location(world, x, 64, 0));
        body.getPersistentDataContainer().set(runtime.petKey(), PersistentDataType.STRING, result.id().toString());
        runtime.store().add(result); runtime.remember(result, body); return result;
    }
    private void profile(BehaviorProfile profile) {
        yaml.set("pets.wolf.behavior", profile.id()); configure();
    }
    private void finishInitialGreeting() {
        // Every profile greets first. Isolate the later meeting's friendship reward.
        yaml.set("social.greeting-friendship-gain", 0);
        yaml.set("social.encounter-cooldown-seconds", 2);
        configure();
        social().tick(now);
        assertTrue(social().engaged(first));
        social().advance(first, now + 100);
        social().advance(first, now + 3000);
        assertEnded();
        assertEquals(0, social().friendship(first, second));
        now += 6000;
        sounds.clear(); particles.clear();
    }
    private void configure() { runtime.config(CompanionConfig.load(runtime.plugin(), yaml)); }
    private PetSocial social() { return actions.social(); }
    private Goal<Mob> goal(Pet pet) {
        return server.getMobGoals().getGoal((Mob) runtime.entity(pet),
                GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "social_navigation")));
    }
    private void territorial() { first.personality(PetPersonality.TERRITORIAL); second.personality(PetPersonality.TERRITORIAL); }
    private void assertEnded() {
        assertFalse(social().engaged(first)); assertFalse(social().engaged(second));
        assertFalse(goal(first).shouldActivate()); assertFalse(goal(second).shouldActivate());
        assertEquals(0, first.socialTailHz()); assertEquals(0, second.socialTailHz());
        assertEquals(0.0, tails.get(a.getUniqueId()), 0.0); assertEquals(0.0, tails.get(b.getUniqueId()), 0.0);
        assertFalse(paths.containsKey(a.getUniqueId())); assertFalse(paths.containsKey(b.getUniqueId()));
    }

    @Test void completedFriendshipReachesDiskThroughTheNormalPendingFlush() throws Exception {
        assertTrue(runtime.store().save()); assertFalse(runtime.store().pending());
        assertTrue(social().trigger(owner, first, "sniff", now));
        social().advance(first, now + 100); social().advance(first, now + 5100);
        assertEquals(6, social().friendship(first, second));
        runtime.store().flush();
        var drain = PetStore.class.getDeclaredMethod("awaitWrites"); drain.setAccessible(true); drain.invoke(runtime.store());
        var restored = new PetStore(runtime.plugin()); assertTrue(restored.load());
        assertEquals(6, restored.get(first.id()).friends().trust(second.id()), "Completed meetings must be included in the next pending flush");
        assertEquals(6, restored.get(second.id()).friends().trust(first.id()));
    }

    @Test void ownerCalmsBarkingPetsWithRepeatedActualInteractions() {
        territorial(); assertTrue(social().trigger(owner, first, "bark", now));
        assertNotNull(social().status(first)); assertNotNull(social().status(second));
        assertTrue(goal(first).shouldActivate()); assertTrue(goal(second).shouldActivate());
        assertEquals(2, sounds.stream().filter(sound -> sound == Sound.ENTITY_WOLF_GROWL).count());
        for (int i = 0; i < runtime.config().social().calmClicks() - 1; i++) {
            assertTrue(actions.useOnPet(owner, a, new ItemStack(Material.AIR))); assertTrue(social().engaged(first));
        }
        assertTrue(actions.useOnPet(owner, a, new ItemStack(Material.AIR)));
        assertEnded(); assertNull(social().status(first)); assertEquals(0, social().friendship(first, second));
        assertEquals(6, particles.stream().filter(p -> p.particle() == Particle.HEART).mapToInt(Emission::count).sum());
        var bars = new ArrayList<String>(); net.kyori.adventure.text.Component message;
        while ((message = owner.nextActionBar()) != null) bars.add(PlainTextComponentSerializer.plainText().serialize(message));
        assertTrue(bars.stream().anyMatch(text -> text.contains("has calmed down")));
    }

    @Test void calmingRequiresPermissionAndDoesNotCancelUnrelatedOrFriendlyMeetings() {
        territorial(); assertTrue(social().trigger(owner, first, "bark", now));
        var visitor = server.addPlayer(); visitor.setOp(false);
        assertFalse(social().calm(visitor, first)); assertTrue(social().engaged(first));
        Pet third = pet(12); assertFalse(social().calm(owner, third)); assertNull(social().status(third));
        visitor.addAttachment(runtime.plugin(), "companionpets.test", true);
        assertTrue(social().calm(visitor, second)); assertEnded();
        first.personality(PetPersonality.FRIENDLY); second.personality(PetPersonality.FRIENDLY);
        assertTrue(social().trigger(owner, first, "sniff", now + 10_000));
        assertFalse(social().calm(owner, first)); assertNull(social().status(first)); assertTrue(social().engaged(first));
    }

    @Test void calmingAfterOneBodyUnloadsReleasesTheSurvivingPet() {
        territorial(); assertTrue(social().trigger(owner, first, "bark", now)); a.remove();
        assertTrue(social().calm(owner, second));
        assertFalse(social().engaged(first)); assertFalse(social().engaged(second));
        assertFalse(goal(second).shouldActivate()); assertEquals(0, second.socialTailHz());
        assertEquals(2, particles.stream().filter(p -> p.particle() == Particle.HEART).mapToInt(Emission::count).sum());
    }

    @Test void nearbyUnfamiliarTerritorialPetsCanAutomaticallyBark() {
        territorial(); profile(BehaviorProfile.DOG);
        yaml.set("social.same-owner-bark-chance", 100); configure();
        finishInitialGreeting();
        social().tick(now + 1000);
        assertNotNull(social().status(first)); assertNotNull(social().status(second));
        assertEquals(2, sounds.stream().filter(sound -> sound == Sound.ENTITY_WOLF_GROWL).count());
        social().advance(first, now + 10_000); assertEnded(); assertEquals(0, social().friendship(first, second));
    }

    @Test void nonPlayingFriendlyPetsAutomaticallySniffAndRememberTheMeeting() {
        profile(BehaviorProfile.BASIC);
        finishInitialGreeting();
        social().tick(now + 1000); assertTrue(social().engaged(first));
        social().advance(first, now + 1100); social().advance(first, now + 6200);
        assertEnded(); assertEquals(6, social().friendship(first, second));
    }

    @Test void cautiousPetsAutomaticallyObserveWithoutApproachingAnUnfamiliarPet() {
        profile(BehaviorProfile.BASIC); first.personality(PetPersonality.SHY);
        finishInitialGreeting();
        b.teleport(new Location(world, 6, 64, 0));
        social().tick(now + 1000); assertTrue(social().engaged(first));
        social().advance(first, now + 1500);
        assertFalse(paths.containsKey(a.getUniqueId()), "Shy observer keeps its personal space");
        assertTrue(paths.containsKey(b.getUniqueId()), "Its friendly partner can approach");
        social().advance(first, now + 5200); assertEnded();
        assertEquals(0, social().friendship(first, second), "A distant observation does not award closeness");
    }

    @Test void automaticChaseEndsWhenTheOwnerMoves() {
        profile(BehaviorProfile.DOG); first.personality(PetPersonality.PLAYFUL);
        yaml.set("social.chase-chance", 100); yaml.set("roaming.stationary-seconds", 1); configure();
        finishInitialGreeting();
        social().tick(now + 2000); assertTrue(social().engaged(first));
        social().advance(first, now + 2100); assertFalse(paths.isEmpty());
        owner.teleport(owner.getLocation().add(1, 0, 0)); actions.roaming().tickOwners(now + 2200);
        social().advance(first, now + 2200); assertEnded(); assertEquals(0, social().friendship(first, second));
    }

    @Test void distanceAndLostVisibilityEndMeetingsWithoutFriendshipRewards() {
        assertTrue(social().trigger(owner, first, "sniff", now));
        b.teleport(new Location(world, 10, 64, 0)); social().advance(first, now + 500); assertEnded();
        b.teleport(new Location(world, 2.2, 64, 0)); assertTrue(social().trigger(owner, first, "sniff", now + 1000));
        blockedSight = true; social().advance(first, now + 1500); assertEnded();
        assertEquals(0, social().friendship(first, second));
    }

    @Test void barkingStopsWhenPetsSeparateBeyondItsSmallerRadius() {
        territorial(); assertTrue(social().trigger(owner, first, "bark", now));
        b.teleport(new Location(world, 8, 64, 0)); social().advance(first, now + 500); assertEnded();
        assertEquals(0, social().friendship(first, second));
    }

    @Test void playfulGreetingHopsOnlyOnceWhenThereIsSafeGroundAndHeadroom() {
        first.personality(PetPersonality.PLAYFUL); second.personality(PetPersonality.PLAYFUL);
        assertTrue(social().trigger(owner, first, "greeting", now));
        assertTrue(first.socialTailHz() > 0); assertTrue(second.socialTailHz() > 0);
        floor = false; social().advance(first, now + 500); assertEquals(100, first.need(Need.ENERGY));
        floor = true; ceiling = 65; social().advance(first, now + 600); assertEquals(0, a.getVelocity().getY());
        ceiling = 66; social().advance(first, now + 700); assertEquals(0, a.getVelocity().getY());
        ceiling = 0; a.grounded = false; b.grounded = false; social().advance(first, now + 800);
        assertEquals(100, first.need(Need.ENERGY));
        a.grounded = true; b.grounded = true; social().advance(first, now + 900);
        assertTrue(a.getVelocity().getY() > .23 && a.getVelocity().getY() <= .30);
        assertTrue(b.getVelocity().getY() > .23 && b.getVelocity().getY() <= .30);
        assertEquals(99.85, first.need(Need.ENERGY), 1e-8); assertEquals(99.85, second.need(Need.ENERGY), 1e-8);
        social().advance(first, now + 1500); assertEquals(99.85, first.need(Need.ENERGY), 1e-8);
        social().advance(first, now + 3100); assertEnded(); assertEquals(2, social().friendship(first, second));
    }

    @Test void unsafeAndUnreachableMeetingRoutesStopNavigation() {
        b.teleport(new Location(world, 5, 64, 0));
        assertTrue(social().trigger(owner, first, "sniff", now));
        floor = false; social().advance(first, now + 100); assertTrue(paths.isEmpty());
        floor = true; reachable = false; social().advance(first, now + 500); assertTrue(paths.isEmpty());
        reachable = true; social().advance(first, now + 1000); assertFalse(paths.isEmpty());
        assertTrue(paths.values().stream().allMatch(at -> at.getY() == 64));
    }

    @Test void disablingSocialMeetingsReleasesGoalsAndVisualsWithoutAwardingFriendship() {
        assertTrue(social().trigger(owner, first, "greeting", now)); assertTrue(first.socialTailHz() > 0);
        yaml.set("social.enabled", false); configure(); social().tick(now + 500);
        assertEnded(); assertEquals(0, social().friendship(first, second));
    }

    private final class SocialWolf extends WolfMock {
        boolean grounded = true;
        private Location planned;
        SocialWolf() { super(PetSocialCoverageTest.this.server, UUID.randomUUID()); }
        @Override public boolean isOnGround() { return grounded; }
        @Override public boolean isInWater() { return false; }
        @Override public boolean hasLineOfSight(Entity target) { return !blockedSight; }
        @Override public float getBodyYaw() { return 0; }
        @Override public void setBodyYaw(float yaw) { }
        @Override public Pathfinder getPathfinder() {
            return (Pathfinder) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Pathfinder.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getEntity" -> this;
                    case "hasPath" -> paths.containsKey(getUniqueId());
                    case "stopPathfinding" -> { paths.remove(getUniqueId()); yield null; }
                    case "findPath" -> {
                        planned = ((Location) args[0]).clone();
                        yield Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Pathfinder.PathResult.class},
                            (path, call, values) -> switch (call.getName()) {
                                case "canReachFinalPoint" -> reachable;
                                case "getFinalPoint" -> planned;
                                default -> throw new AssertionError(call.getName());
                            });
                    }
                    case "moveTo" -> { paths.put(getUniqueId(), (args[0] instanceof Location at ? at : planned).clone()); yield true; }
                    default -> throw new AssertionError(method.getName());
                });
        }
    }

    public static class FailUnimplemented implements TestExecutionExceptionHandler, LifecycleMethodExecutionExceptionHandler {
        @Override public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable { check(throwable); }
        @Override public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable { check(throwable); }
        private void check(Throwable throwable) throws Throwable {
            if (throwable instanceof UnimplementedOperationException) throw new AssertionError("Implement the required Bukkit fixture boundary", throwable);
            throw throwable;
        }
    }
}
