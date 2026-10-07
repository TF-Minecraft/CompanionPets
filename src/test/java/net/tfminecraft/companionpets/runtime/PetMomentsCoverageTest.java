package net.tfminecraft.companionpets.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.companionpets.behavior.Locomotion;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.session.TrainingSession;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.testutil.GoalServerMock;
import net.tfminecraft.companionpets.visual.PetVisual;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class PetMomentsCoverageTest {
    private GoalServerMock server;
    private WorldMock world;
    private PlayerMock owner;
    private WolfMock body;
    private Pet pet;
    private PetRuntime runtime;
    private PetMoments moments;
    private YamlConfiguration yaml;
    private final Map<String, BlockMock> blocks = new HashMap<>();
    private final List<String> animations = new ArrayList<>();
    private final List<Sound> sounds = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final List<Location> paths = new ArrayList<>();
    private boolean visible = true, inWater, grounded = true;
    private boolean bellyActive, bellyAvailable = true, rubAccepted = true, movementHeld;
    private int starts, rubs, cancels, headHolds, headReleases;
    private static final long NOW = 1_000_000;

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(new GoalServerMock());
        world = new WorldMock() {
            @Override public BlockMock getBlockAt(int x, int y, int z) {
                return blocks.computeIfAbsent(x + ":" + y + ":" + z, key -> new BlockMock(new Location(this, x, y, z)) {
                    @Override public boolean isPassable() { return !getType().isSolid(); }
                });
            }
            @Override public void playSound(Location at, Sound sound, float volume, float pitch) { sounds.add(sound); }
            @Override public void spawnParticle(Particle particle, Location at, int count,
                    double x, double y, double z, double extra) { particles.add(particle); }
        };
        server.addWorld(world);
        owner = server.addPlayer(); owner.teleport(new Location(world, 0, 64, 0));
        var plugin = MockBukkit.createMockPlugin();
        yaml = new YamlConfiguration();
        yaml.loadFromString("""
                moments:
                  initial-delay-min-seconds: 0
                  initial-delay-max-seconds: 0
                  juvenile-interval-min-seconds: 2
                  juvenile-interval-max-seconds: 2
                  adult-interval-min-seconds: 10
                  adult-interval-max-seconds: 10
                  inactive-retry-seconds: 1
                  juvenile-mischief-chance: 0
                  adult-mischief-chance: 0
                  juvenile-bark-chance: 0
                  adult-bark-chance: 0
                  juvenile-dig-chance: 0
                  adult-dig-chance: 0
                  dig-loot: {BONE: 1}
                  belly-up: {chance: 100, cooldown-seconds: 60}
                pets:
                  wolf: {entity: WOLF, model: canine, egg: WOLF_SPAWN_EGG, default-tricks: []}
                """);
        var visual = new PetVisual() {
            @Override public void apply(Entity entity, PetTypeDef type) { }
            @Override public boolean modelAvailable(PetTypeDef type) { return true; }
            @Override public Set<String> clips(PetTypeDef type) { return Set.of("lie_back", "belly_up", "get_up"); }
            @Override public boolean play(Entity entity, PetTypeDef type, String action) { animations.add(action); return true; }
            @Override public boolean startBelly(Entity entity, PetTypeDef type, long idleMillis) { starts++; bellyActive = bellyAvailable; return bellyActive; }
            @Override public boolean belly(Entity entity) { return bellyActive; }
            @Override public boolean rubBelly(Entity entity) { rubs++; return rubAccepted; }
            @Override public boolean holdsMovement(Entity entity) { return movementHeld || bellyActive; }
            @Override public void cancelAction(Entity entity) { cancels++; bellyActive = false; }
            @Override public void holdHeadLook(Entity entity, float yaw, float head, float pitch) { headHolds++; }
            @Override public void releaseHeadLook(Entity entity) { headReleases++; }
        };
        var key = new NamespacedKey(plugin, "pet");
        var store = new PetStore(plugin); assertTrue(store.load());
        runtime = new PetRuntime(plugin, CompanionConfig.load(plugin, yaml), store, new Sessions(),
                new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        moments = new PetMoments(runtime);
        pet = new Pet(UUID.randomUUID(), owner.getUniqueId(), "wolf", "Toby", PetSex.MALE);
        pet.personality(PetPersonality.PLAYFUL);
        for (Need need : Need.values()) pet.need(need, 80);
        body = new WolfMock(server, UUID.randomUUID()) {
            @Override public boolean isOnGround() { return grounded; }
            @Override public boolean isInWater() { return inWater; }
            @Override public boolean hasLineOfSight(Entity entity) { return visible; }
            @Override public float getBodyYaw() { return 0; }
            @Override public void setBodyYaw(float value) { }
            @Override public void lookAt(Entity target, float speed, float pitch) { }
            @Override public void lookAt(Location target, float speed, float pitch) { }
            @Override public com.destroystokyo.paper.entity.Pathfinder getPathfinder() {
                return (com.destroystokyo.paper.entity.Pathfinder) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{com.destroystokyo.paper.entity.Pathfinder.class}, (proxy, method, args) -> switch (method.getName()) {
                            case "stopPathfinding" -> null;
                            case "hasPath" -> false;
                            case "moveTo" -> { paths.add(((Location) args[0]).clone()); yield true; }
                            case "getEntity" -> this;
                            default -> throw new AssertionError("Unexpected navigation: " + method.getName());
                        });
            }
        };
        server.registerEntity(body); body.teleport(owner.getLocation().add(1, 0, 0));
        body.getPersistentDataContainer().set(key, PersistentDataType.STRING, pet.id().toString());
        store.add(pet); runtime.remember(pet, body);
        runtime.random().setSeed(0);
    }

    @AfterEach void cleanup() {
        if (moments != null) moments.clear();
        if (runtime != null) runtime.store().close();
        if (owner != null) PetFx.clearPlayer(owner.getUniqueId());
        MockBukkit.unmock();
    }
    private void configure(String key, Object value) {
        yaml.set(key, value); runtime.config(CompanionConfig.load(runtime.plugin(), yaml));
    }
    private String bars() {
        StringBuilder text = new StringBuilder(); Component line;
        while ((line = owner.nextActionBar()) != null) text.append(PlainTextComponentSerializer.plainText().serialize(line)).append('\n');
        return text.toString();
    }
    private void tick(long at) { moments.tick(pet, body, owner, Locomotion.Mode.FOLLOW, at); }
    private void due(long at) { tick(at); tick(at); }
    private List<Item> items() { return world.getEntities().stream().filter(Item.class::isInstance).map(Item.class::cast).toList(); }
    private Block plant() { Block plant = world.getBlockAt(2, 64, 0); plant.setType(Material.SHORT_GRASS); return plant; }
    private void startDig() {
        configure("moments.adult-dig-chance", 100);
        due(NOW);
        assertTrue(bars().contains("starts digging"));
        assertTrue(items().isEmpty());
    }
    private long attentionSignals() { return particles.stream().filter(particle -> particle == Particle.SPLASH).count(); }
    private void assertNeedsUnchanged() {
        for (Need need : Need.values()) assertEquals(80, pet.need(need));
        assertEquals(PetOrder.FOLLOW, pet.order());
    }

    @Test void giftWaitsFiveSecondsWalksToTheOwnerAndDeliversExactlyOnce() {
        startDig();
        tick(NOW + 4_999); assertTrue(items().isEmpty()); assertTrue(paths.isEmpty());
        owner.teleport(new Location(world, 8, 64, 0));
        tick(NOW + 5_000);
        assertEquals(List.of(owner.getLocation()), paths); assertTrue(items().isEmpty());
        body.teleport(owner.getLocation().add(1, 0, 0));
        tick(NOW + 5_001);
        assertEquals(1, items().size()); assertEquals(Material.BONE, items().getFirst().getItemStack().getType());
        assertTrue(bars().contains("brought you a bone"));
        tick(NOW + 5_002); assertEquals(1, items().size());
        assertNeedsUnchanged();
    }

    @Test void giftExpiresAtTwentyFiveSecondsWithoutDroppingOrChangingNeeds() {
        startDig(); owner.teleport(new Location(world, 8, 64, 0));
        tick(NOW + 24_999); assertEquals(1, paths.size());
        int previous = cancels;
        tick(NOW + 25_000);
        assertEquals(previous + 1, cancels); assertTrue(items().isEmpty());
        assertFalse(bars().contains("brought you")); assertNeedsUnchanged();
    }

    @Test void nonFollowingActivityCancelsTheGiftBeforeDelivery() {
        startDig();
        pet.activity(Activity.SLEEPING);
        moments.tick(pet, body, owner, Locomotion.Mode.SLEEP, NOW + 1);
        pet.activity(Activity.NONE);
        configure("moments.digging-enabled", false);
        tick(NOW + 6_000); tick(NOW + 6_001);
        assertTrue(items().isEmpty()); assertFalse(bars().contains("brought you"));
    }

    @Test void ownerDepartureOrInvalidBodyCancelsPendingGifts() {
        startDig();
        moments.tick(pet, body, null, Locomotion.Mode.FOLLOW, NOW + 5_000);
        assertTrue(items().isEmpty());
        moments.clear();
        due(NOW + 30_000); assertEquals(2, sounds.stream().filter(sound -> sound == Sound.BLOCK_GRAVEL_BREAK).count());
        body.remove();
        tick(NOW + 35_000);
        assertTrue(items().isEmpty());
    }

    @Test void clearingAnActiveGiftStopsDeliveryAndAllowsFreshScheduling() {
        startDig();
        moments.clear();
        configure("moments.adult-dig-chance", 0);
        due(NOW + 6_000);
        assertTrue(items().isEmpty()); assertTrue(bars().contains("attention"));
    }

    @Test void diggingRefusesEmptyLootMissingProvidersAndConflictingActivities() {
        configure("moments.dig-loot", List.of());
        assertFalse(moments.triggerDig(pet, body, owner));
        configure("moments.dig-loot", List.of(Map.of("item", "mmoitems:MATERIAL:UNAVAILABLE", "weight", 1)));
        assertFalse(moments.triggerDig(pet, body, owner));
        configure("moments.dig-loot", List.of(Map.of("item", "BONE", "weight", 1)));
        pet.carriedToy("STICK"); assertFalse(moments.triggerDig(pet, body, owner));
        pet.carriedToy(null); assertFalse(moments.triggerDig(pet, body, null));
        assertTrue(moments.triggerDig(pet, body, owner));
        assertFalse(moments.triggerDig(pet, body, owner));
        assertTrue(items().isEmpty());
    }

    @Test void initialDelayAndAdultIntervalPreventRepeatedAttention() {
        configure("moments.initial-delay-min-seconds", 3);
        configure("moments.initial-delay-max-seconds", 3);
        tick(NOW); tick(NOW + 2_999); assertTrue(bars().isEmpty());
        tick(NOW + 3_000); assertTrue(bars().contains("attention"));
        tick(NOW + 12_999); assertTrue(bars().isEmpty());
        tick(NOW + 13_000); assertEquals(2, attentionSignals());
        assertNeedsUnchanged();
    }

    @Test void juvenileIntervalAndEveryPersonalityKeepNeedsAndOrdersStable() {
        pet.bornAt(NOW - 1_000);
        for (PetPersonality personality : PetPersonality.values()) {
            moments.clear(); pet.personality(personality);
            long previous = attentionSignals();
            due(NOW); assertEquals(previous + 1, attentionSignals());
            tick(NOW + 1_999); assertEquals(previous + 1, attentionSignals());
            tick(NOW + 2_000); assertEquals(previous + 2, attentionSignals());
            assertNeedsUnchanged();
        }
    }

    @Test void reversedDelayBoundsStillScheduleInsideTheConfiguredRange() {
        configure("moments.initial-delay-min-seconds", 4);
        configure("moments.initial-delay-max-seconds", 2);
        tick(NOW); tick(NOW + 1_999); assertTrue(bars().isEmpty());
        tick(NOW + 4_000); assertTrue(bars().contains("attention"));
    }

    @Test void inactivityRetriesRatherThanConsumingTheNormalMomentInterval() {
        tick(NOW);
        owner.teleport(new Location(world, 30, 64, 0)); tick(NOW);
        owner.teleport(new Location(world, 0, 64, 0));
        tick(NOW + 999); assertTrue(bars().isEmpty());
        tick(NOW + 1_000); assertTrue(bars().contains("attention"));
        moments.clear();
        runtime.sessions().training(owner.getUniqueId(), new TrainingSession(pet.id()));
        due(NOW + 2_000); assertTrue(bars().isEmpty());
        runtime.sessions().clearTraining(owner.getUniqueId());
        tick(NOW + 3_000); assertEquals(2, attentionSignals());
    }

    @Test void disabledMomentsAndIllnessHaveDistinctOutcomes() {
        configure("moments.enabled", false);
        due(NOW); assertTrue(bars().isEmpty());
        configure("moments.enabled", true); pet.illness(Illness.SICK);
        due(NOW + 1_000);
        assertTrue(bars().contains("attention")); assertEquals(Illness.SICK, pet.illness());
        assertNeedsUnchanged();
    }

    @Test void lowMoodProtestsAtVisibleStrangersAndFallsBackToAttention() {
        pet.need(Need.MOOD, 30);
        configure("moments.low-mood-bark-chance", 100);
        var stranger = server.addPlayer(); stranger.teleport(body.getLocation().add(1, 0, 0));
        due(NOW);
        assertEquals(List.of("SPEAK"), animations); assertTrue(bars().contains("protests"));
        assertEquals(30, pet.need(Need.MOOD));
        moments.clear(); visible = false; animations.clear();
        due(NOW + 1_000);
        assertTrue(animations.isEmpty()); assertTrue(bars().contains("attention"));
        moments.clear(); configure("moments.low-mood-bark-chance", 0);
        due(NOW + 2_000); assertEquals(2, attentionSignals());
    }

    @Test void zeroDigChanceDoesNotBecomeAFallbackForUnavailableBarking() {
        configure("pets.wolf.behaviors.remove", List.of("social-protest"));
        configure("moments.adult-bark-chance", 100);
        var stranger = server.addPlayer(); stranger.teleport(body.getLocation().add(1, 0, 0));
        world.dropItem(body.getLocation(), new ItemStack(Material.STICK));
        due(NOW);
        assertFalse(sounds.contains(Sound.BLOCK_GRAVEL_BREAK), "Zero dig chance must not acquire the unavailable bark probability");
        assertTrue(animations.isEmpty()); assertEquals(1, attentionSignals());
    }

    @Test void normalBarkChoiceUsesTheNearbyLivingEntityAndDoesNotStartDigging() {
        configure("moments.adult-bark-chance", 100);
        pet.personality(PetPersonality.GRUMPY);
        var stranger = server.addPlayer(); stranger.teleport(body.getLocation().add(1, 0, 0));
        world.dropItem(body.getLocation(), new ItemStack(Material.STICK));
        due(NOW);
        assertEquals(List.of("SPEAK"), animations);
        assertTrue(bars().contains("protests"));
        assertFalse(sounds.contains(Sound.BLOCK_GRAVEL_BREAK));
    }

    @Test void automaticMischiefBreaksOnlyConfiguredPlantsAndLeavesNeedsAlone() {
        configure("moments.adult-mischief-chance", 100);
        Block plant = plant();
        Block stone = world.getBlockAt(2, 64, 1); stone.setType(Material.STONE);
        due(NOW);
        assertEquals(Material.AIR, plant.getType()); assertEquals(Material.STONE, stone.getType());
        assertTrue(bars().contains("pulled up a plant")); assertNeedsUnchanged();
    }

    @Test void protectionCancellationPreventsPlantBreakingAndMischiefFeedback() {
        Block plant = plant();
        server.getPluginManager().registerEvent(EntityChangeBlockEvent.class, new Listener() { }, EventPriority.HIGH,
                (unused, raw) -> {
                    EntityChangeBlockEvent event = (EntityChangeBlockEvent) raw;
                    assertSame(body, event.getEntity()); assertEquals(plant, event.getBlock());
                    assertEquals(Material.AIR, event.getTo()); event.setCancelled(true);
                }, runtime.plugin());
        assertFalse(moments.triggerMischief(pet, body, owner));
        assertEquals(Material.SHORT_GRASS, plant.getType()); assertTrue(bars().isEmpty());
    }

    @Test void mobGriefingAndAConcurrentBlockChangeAreRespected() {
        Block plant = plant();
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        assertFalse(moments.triggerMischief(pet, body, owner));
        assertEquals(Material.SHORT_GRASS, plant.getType());
        configure("moments.respect-mob-griefing", false);
        server.getPluginManager().registerEvent(EntityChangeBlockEvent.class, new Listener() { }, EventPriority.HIGH,
                (unused, raw) -> ((EntityChangeBlockEvent) raw).getBlock().setType(Material.AIR), runtime.plugin());
        assertFalse(moments.triggerMischief(pet, body, owner));
        assertTrue(bars().isEmpty());
    }

    @Test void failedBellyAttemptsAndSuccessfulCancellationBothKeepTheCooldown() {
        bellyAvailable = false;
        assertFalse(moments.petBelly(pet, body, owner, NOW)); assertEquals(1, starts);
        bellyAvailable = true;
        assertFalse(moments.petBelly(pet, body, owner, NOW + 59_999)); assertEquals(1, starts);
        assertTrue(moments.petBelly(pet, body, owner, NOW + 60_000)); assertEquals(2, starts);
        moments.cancel(pet);
        assertFalse(moments.petBelly(pet, body, owner, NOW + 60_001));
        assertTrue(moments.petBelly(pet, body, owner, NOW + 120_000));
        assertNeedsUnchanged();
    }

    @Test void rejectedRubDoesNotEndAnOtherwiseValidBellyPose() {
        assertTrue(moments.triggerBelly(pet, body, owner));
        bars(); rubAccepted = false;
        assertTrue(moments.petBelly(pet, body, owner, NOW));
        assertTrue(bellyActive); assertEquals(1, rubs); assertTrue(bars().isEmpty());
        assertFalse(moments.triggerBelly(pet, body, owner)); assertEquals(2, rubs);
        assertTrue(bellyActive);
    }

    @Test void bellyTickCancelsAnInvalidPoseAndReleasesItsHeadConstraint() {
        assertTrue(moments.triggerBelly(pet, body, owner));
        assertTrue(moments.tickBelly(pet, body, owner));
        inWater = true;
        assertFalse(moments.tickBelly(pet, body, owner));
        assertFalse(bellyActive); assertTrue(cancels > 0); assertTrue(headReleases > 0);
        assertNeedsUnchanged();
    }

    @Test void clearRemovesBellyStateAndCooldownForTheNextSession() {
        assertTrue(moments.petBelly(pet, body, owner, NOW));
        moments.clear();
        assertFalse(bellyActive);
        assertTrue(moments.petBelly(pet, body, owner, NOW + 1));
        assertEquals(2, starts);
    }

    @Test void zeroBarkChanceDoesNotBecomeAFallbackForUnavailableMischief() {
        configure("moments.adult-mischief-chance", 100);
        var stranger = server.addPlayer(); stranger.teleport(body.getLocation().add(1, 0, 0));
        due(NOW);
        assertTrue(animations.isEmpty(), "No plants exist, but zero bark chance must still prohibit a protest");
        assertEquals(1, attentionSignals());
        assertFalse(sounds.contains(Sound.BLOCK_GRAVEL_BREAK));
    }

    @Test void weightedLootCanSelectEitherConfiguredItemAndEachJobDropsOne() {
        configure("moments.dig-loot", List.of(Map.of("item", "BONE", "weight", 3), Map.of("item", "STICK", "weight", 1)));
        configure("moments.adult-dig-chance", 100);
        runtime.random().setSeed(91_731);
        Map<Material, Integer> delivered = new EnumMap<>(Material.class);
        for (int attempt = 0; attempt < 64; attempt++) {
            moments.clear();
            long at = NOW + attempt * 100_000L;
            due(at); tick(at + 5_000);
            assertEquals(1, items().size());
            Item item = items().getFirst();
            assertEquals(1, item.getItemStack().getAmount());
            delivered.merge(item.getItemStack().getType(), 1, Integer::sum);
            item.remove();
        }
        assertEquals(Set.of(Material.BONE, Material.STICK), delivered.keySet());
        assertTrue(delivered.get(Material.BONE) > delivered.get(Material.STICK), "The heavier item wins more often for this fixed random stream");
        assertNeedsUnchanged();
    }
}
