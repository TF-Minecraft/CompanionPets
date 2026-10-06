package net.tfminecraft.companionpets.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.visual.PetVisual;

class PetInteractionTest {
    private PetRuntime runtime;
    private PetActions actions;
    private PlayerMock player;
    private WolfMock body;
    private Pet pet;
    private int bodyRemovals;
    private int navigationStops;
    private int navigationRequests;
    private Location navigationTarget;
    private boolean inWater;
    private Location lookedAt;
    private boolean greetingGround;
    private boolean reachableGreetingPath = true;
    private Location plannedPathTarget;
    private boolean unreachableFormationSlot, existingFollowPath;
    private Location partialFollowEnd;
    private boolean lookingAtPet;
    private boolean blockedHop;
    private boolean blockedSocialSight;
    private org.bukkit.entity.EntityType greetingSpecies = org.bukkit.entity.EntityType.WOLF;
    private double navigationSpeed;
    private double tailHz;
    private final java.util.List<org.bukkit.Sound> greetingSounds = new java.util.ArrayList<>();
    private final java.util.List<Float> greetingPitches = new java.util.ArrayList<>();
    private final java.util.List<String> customSounds = new java.util.ArrayList<>();
    private YamlConfiguration testConfig;
    private boolean bellyAvailable, bellyActive;
    private boolean headTiltAvailable;
    private int headTiltPlays, actionCancels;
    private boolean trainingAttention;
    private boolean visualGround;
    private net.tfminecraft.companionpets.visual.PetAnimation visualPose;
    private int headHolds, headReleases;
    private float heldBodyYaw, heldHeadYaw, heldPitch;
    private org.bukkit.Sound nativeSound = org.bukkit.Sound.ENTITY_FROG_AMBIENT;

    @BeforeEach void setup() throws Exception {
        var goals = new java.util.HashMap<String, com.destroystokyo.paper.entity.ai.Goal<?>>();
        var server = MockBukkit.mock(new org.mockbukkit.mockbukkit.ServerMock() {
            @Override public com.destroystokyo.paper.entity.ai.MobGoals getMobGoals() {
                return (com.destroystokyo.paper.entity.ai.MobGoals) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{com.destroystokyo.paper.entity.ai.MobGoals.class}, (proxy, method, args) -> {
                            var id = ((org.bukkit.entity.Mob) args[0]).getUniqueId();
                            return switch (method.getName()) {
                                case "getGoal" -> goals.get(id + ":" + args[1]);
                                case "addGoal" -> { goals.put(id + ":" + ((com.destroystokyo.paper.entity.ai.Goal<?>) args[2]).getKey(), (com.destroystokyo.paper.entity.ai.Goal<?>) args[2]); yield null; }
                                case "removeGoal" -> { goals.remove(id + ":" + (args[1] instanceof com.destroystokyo.paper.entity.ai.Goal<?> goal ? goal.getKey() : args[1])); yield null; }
                                case "removeAllGoals" -> { goals.keySet().removeIf(k -> k.startsWith(id + ":")); yield null; }
                                default -> throw new AssertionError("Unexpected goals call: " + method.getName());
                            };
                        });
            }
        });
        var plugin = MockBukkit.createMockPlugin();
        var world = new WorldMock() {
            @Override public void playSound(Location at, org.bukkit.Sound sound, float volume, float pitch) {
                greetingSounds.add(sound); greetingPitches.add(pitch);
            }
            @Override public void playSound(Location at, String sound, float volume, float pitch) {
                customSounds.add(sound);
            }
            @Override public org.bukkit.util.RayTraceResult rayTraceBlocks(Location at, org.bukkit.util.Vector direction, double distance, org.bukkit.FluidCollisionMode fluids, boolean ignorePassable) { return null; }
            @Override public org.bukkit.util.RayTraceResult rayTraceEntities(Location at, org.bukkit.util.Vector direction, double distance, java.util.function.Predicate<? super Entity> filter) {
                return lookingAtPet ? new org.bukkit.util.RayTraceResult(body.getLocation().toVector(), body) : null;
            }
            @Override public <T extends Entity> T spawn(Location at, Class<T> type) {
                if (type != org.bukkit.entity.Wolf.class) return super.spawn(at, type);
                var wolf = new WolfMock(server, UUID.randomUUID()) {
                    @Override public org.bukkit.entity.EntityType getType() { return greetingSpecies; }
                    @Override public org.bukkit.Sound getAmbientSound() { return nativeSound; }
                    @Override public org.bukkit.Sound getHurtSound() { return org.bukkit.Sound.ENTITY_PIG_HURT; }
                    private float bodyYaw;
                    @Override public float getBodyYaw() { return bodyYaw; }
                    @Override public void setBodyYaw(float yaw) { bodyYaw = yaw; }
                    @Override public int getHeadRotationSpeed() { return 40; }
                    @Override public int getMaxHeadPitch() { return 30; }
                    @Override public void lookAt(Entity target, float speed, float pitch) {
                        lookedAt = target instanceof org.bukkit.entity.LivingEntity living ? living.getEyeLocation() : target.getLocation();
                    }
                    @Override public void lookAt(Location target, float speed, float pitch) { lookedAt = target.clone(); }
                    @Override public boolean isInWater() { return inWater; }
                    @Override public boolean isOnGround() { return visualGround || super.isOnGround(); }
                    @Override public boolean hasLineOfSight(Entity entity) { return !blockedSocialSight; }
                    @Override public void setRemoveWhenFarAway(boolean remove) { }
                    @Override public com.destroystokyo.paper.entity.Pathfinder getPathfinder() {
                        return (com.destroystokyo.paper.entity.Pathfinder) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[]{com.destroystokyo.paper.entity.Pathfinder.class}, (proxy, method, args) -> switch (method.getName()) {
                                    case "stopPathfinding" -> { navigationStops++; yield null; }
                                    case "moveTo" -> { navigationRequests++; navigationTarget = (args[0] instanceof Location at ? at : plannedPathTarget).clone(); navigationSpeed = (double) args[1]; yield true; }
                                    case "hasPath" -> existingFollowPath;
                                    case "getEntity" -> this;
                                    case "findPath" -> { plannedPathTarget = ((Location) args[0]).clone();
                                        boolean reaches = reachableGreetingPath && (!unreachableFormationSlot || plannedPathTarget.distanceSquared(player.getLocation()) < 0.1);
                                        yield (com.destroystokyo.paper.entity.Pathfinder.PathResult) java.lang.reflect.Proxy.newProxyInstance(
                                            getClass().getClassLoader(), new Class<?>[]{com.destroystokyo.paper.entity.Pathfinder.PathResult.class},
                                            (p, m, a) -> switch (m.getName()) { case "canReachFinalPoint" -> reaches; case "getFinalPoint" -> partialFollowEnd; default -> null; }); }
                                    default -> throw new AssertionError("Unexpected navigation call: " + method.getName());
                                });
                    }
                };
                server.registerEntity(wolf);
                wolf.teleport(at);
                return type.cast(wolf);
            }
            @Override public BlockMock getBlockAt(int x, int y, int z) {
                if (blockedHop && y == 65) return new BlockMock(Material.STONE, new Location(this, x, y, z)) {
                    @Override public boolean isPassable() { return false; }
                };
                if (greetingGround && y == 63) return new BlockMock(Material.STONE, new Location(this, x, y, z)) {
                    @Override public boolean isPassable() { return false; }
                };
                return new BlockMock(new Location(this, x, y, z)) {
                    @Override public boolean isPassable() { return true; }
                };
            }
            @Override public boolean isChunkLoaded(int x, int z) { return greetingGround || super.isChunkLoaded(x, z); }
        };
        server.addWorld(world);
        player = server.addPlayer();
        player.teleport(new Location(world, 0, 64, 0));
        player.openInventory(server.createInventory(null, 9));
        var yaml = new YamlConfiguration(); testConfig = yaml;
        yaml.loadFromString("""
                limits: {max-out: 2}
                items:
                  treats: [COD, SALMON]
                  foods: [{item: BEEF, hunger: 35}]
                  medicines: [MILK_BUCKET, HONEY_BOTTLE]
                  brushes: [FEATHER]
                  toys: [STICK]
                pets:
                  wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG, sex: choose}
                  selective:
                    entity: WOLF
                    egg: CAT_SPAWN_EGG
                    items: {foods: [], treats: [], medicines: [], brushes: [], toys: []}
                """);
        var visual = new PetVisual() {
            @Override public void trainingAttention(Entity entity, net.tfminecraft.companionpets.config.PetTypeDef type, boolean focused) {
                if (focused && !trainingAttention && headTiltAvailable) headTiltPlays++;
                trainingAttention = focused;
            }
            @Override public void update(Entity entity, net.tfminecraft.companionpets.config.PetTypeDef type, net.tfminecraft.companionpets.visual.PetAnimation pose) { visualPose = pose; }
            @Override public boolean play(Entity entity, net.tfminecraft.companionpets.config.PetTypeDef type, String action) {
                if (action.equals("HEAD_TILT") && headTiltAvailable) { headTiltPlays++; return true; }
                return false;
            }
            @Override public boolean startBelly(Entity entity, net.tfminecraft.companionpets.config.PetTypeDef type, long duration) {
                bellyActive = bellyAvailable; return bellyActive;
            }
            @Override public boolean belly(Entity entity) { return bellyActive; }
            @Override public boolean holdsMovement(Entity entity) { return bellyActive; }
            @Override public void cancelAction(Entity entity) { actionCancels++; bellyActive = false; }
            @Override public void holdHeadLook(Entity entity, float yaw, float head, float pitch) {
                headHolds++; heldBodyYaw = yaw; heldHeadYaw = head; heldPitch = pitch;
            }
            @Override public void releaseHeadLook(Entity entity) { headReleases++; }
            @Override public void wagTail(Entity entity, double hz) { tailHz = hz; }
            @Override public void apply(Entity entity, net.tfminecraft.companionpets.config.PetTypeDef type) { }
            @Override public void removeBody(Entity entity) {
                bodyRemovals++;
                PetVisual.super.removeBody(entity);
            }
        };
        var key = new NamespacedKey(plugin, "pet");
        runtime = new PetRuntime(plugin, CompanionConfig.load(plugin, yaml), loadedStore(plugin),
                new Sessions(), new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        actions = new PetActions(runtime);
        pet = new Pet(UUID.randomUUID(), player.getUniqueId(), "wolf", "Toby", PetSex.MALE);
        body = (WolfMock) world.spawn(player.getLocation().add(1, 0, 0), org.bukkit.entity.Wolf.class);
        body.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.STRING, pet.id().toString());
        net.tfminecraft.companionpets.training.DefaultTricks.apply(runtime.config(), pet); runtime.store().add(pet);
        runtime.remember(pet, body);
        pet.stored(false);
    }

    private static PetStore loadedStore(org.bukkit.plugin.java.JavaPlugin plugin) {
        var store = new PetStore(plugin);
        assertTrue(store.load());
        return store;
    }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    private void greetingSpecies(org.bukkit.entity.EntityType entity) {
        greetingSpecies = entity; testConfig.set("pets.wolf.entity", entity.name());
        runtime.config(CompanionConfig.load(runtime.plugin(), testConfig));
    }
    private void eagerGreeting() { pet.bond(100); pet.personality(PetPersonality.FRIENDLY); }

    @Test void trainingStartsWithAvailableHeadTiltWithoutCancellingItOnRepeatedTreatClicks() {
        headTiltAvailable = true;
        var treat = new ItemStack(Material.COD);
        player.getInventory().setItemInMainHand(treat);
        actions.useOnPet(player, body, treat);
        assertEquals(1, headTiltPlays);
        int cancels = actionCancels;
        actions.useOnPet(player, body, treat);
        assertEquals(1, headTiltPlays, "Do not restart the same gesture while it is playing");
        assertEquals(cancels, actionCancels, "Do not cancel attention on another treat click");
        assertTrue(trainingGoal().shouldActivate());
    }

    @Test void trainingStillHoldsAttentionWhenHeadTiltClipIsMissing() {
        var treat = new ItemStack(Material.COD);
        player.getInventory().setItemInMainHand(treat);
        assertDoesNotThrow(() -> actions.useOnPet(player, body, treat));
        assertEquals(0, headTiltPlays);
        assertTrue(trainingGoal().shouldActivate());
    }

    @Test void trainingAttentionPausesForComeAndEndsWithTheSession() {
        visualGround = true; headTiltAvailable = true;
        testConfig.set("pets.wolf.appearance.type", "modelengine");
        testConfig.set("pets.wolf.appearance.model", "beagle");
        runtime.config(CompanionConfig.load(runtime.plugin(), testConfig));
        var treat = new ItemStack(Material.COD);
        player.getInventory().setItemInMainHand(treat);
        actions.useOnPet(player, body, treat);
        var ticker = new net.tfminecraft.companionpets.visual.PetVisualTicker(runtime);
        ticker.run();
        body.setTicksLived(body.getTicksLived() + 1);
        body.teleport(body.getLocation().add(.4, 0, 0));
        ticker.run();
        assertEquals(net.tfminecraft.companionpets.visual.PetAnimation.IDLE, visualPose);
        assertTrue(trainingAttention);
        pet.activity(Activity.ATTENDING); // Come must be allowed to run during training.
        body.setTicksLived(body.getTicksLived() + 1);
        body.teleport(body.getLocation().add(.4, 0, 0));
        ticker.run();
        assertEquals(net.tfminecraft.companionpets.visual.PetAnimation.IDLE, visualPose, "ModelEngine animates the run itself");
        assertFalse(trainingAttention);
        pet.activity(Activity.NONE); ticker.run(); assertTrue(trainingAttention);
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        new PetTicker(runtime, actions).run();
        assertFalse(trainingAttention, "Clear the gesture immediately when the session ends");
        body.setTicksLived(body.getTicksLived() + 1);
        body.teleport(body.getLocation().add(.4, 0, 0)); ticker.run();
        assertEquals(net.tfminecraft.companionpets.visual.PetAnimation.IDLE, visualPose);
    }

    private com.destroystokyo.paper.entity.ai.Goal<org.bukkit.entity.Mob> trainingGoal() {
        return org.bukkit.Bukkit.getMobGoals().getGoal(body,
                com.destroystokyo.paper.entity.ai.GoalKey.of(org.bukkit.entity.Mob.class,
                        new NamespacedKey(runtime.plugin(), "training_navigation")));
    }

    private com.destroystokyo.paper.entity.ai.Goal<org.bukkit.entity.Mob> lookGoal() {
        return org.bukkit.Bukkit.getMobGoals().getGoal(body,
                com.destroystokyo.paper.entity.ai.GoalKey.of(org.bukkit.entity.Mob.class,
                        new NamespacedKey("companionpets", "look")));
    }

    @Test void treatTrainingHoldsMovementAndGazeUntilTreatIsPutAway() {
        var treat = new ItemStack(Material.COD);
        player.getInventory().setItemInMainHand(treat);
        assertTrue(actions.useOnPet(player, body, treat));
        assertNotNull(runtime.sessions().training(player.getUniqueId()));
        var goal = trainingGoal();
        assertNotNull(goal);
        assertTrue(goal.shouldActivate());
        assertTrue(goal.getTypes().contains(com.destroystokyo.paper.entity.ai.GoalType.MOVE));
        assertTrue(lookGoal().getTypes().contains(com.destroystokyo.paper.entity.ai.GoalType.LOOK));
        var ticker = new PetTicker(runtime, actions);
        body.setVelocity(new org.bukkit.util.Vector(.2, .3, .2));
        existingFollowPath = true;
        int requests = navigationRequests;
        ticker.run(); goal.tick(); lookGoal().tick();
        assertEquals(requests, navigationRequests);
        assertEquals(0, body.getVelocity().getX());
        assertEquals(0, body.getVelocity().getZ());
        assertEquals(.3, body.getVelocity().getY(), "Training must not cancel a jump trick");
        assertEquals(player.getEyeLocation(), lookedAt);
        assertTrue(body.isAware(), "Keep native look control running during training");
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        ticker.run();
        assertNull(runtime.sessions().training(player.getUniqueId()));
        assertFalse(goal.shouldStayActive());
        int stops = navigationStops;
        ticker.run();
        assertEquals(stops, navigationStops, "Normal native roaming retains its route after training");
    }

    @Test void trainingOverridesRestingGazeReusesGoalAndReleasesForCome() {
        pet.order(PetOrder.SIT);
        PostureNavigationGoal.hold(runtime, pet, body);
        var posture = org.bukkit.Bukkit.getMobGoals().getGoal(body,
                com.destroystokyo.paper.entity.ai.GoalKey.of(org.bukkit.entity.Mob.class,
                        new NamespacedKey(runtime.plugin(), "posture_navigation")));
        assertTrue(posture.shouldActivate());
        var treat = new ItemStack(Material.COD);
        player.getInventory().setItemInMainHand(treat);
        actions.useOnPet(player, body, treat);
        var goal = trainingGoal();
        assertTrue(goal.shouldActivate());
        assertFalse(posture.shouldActivate(), "Resting look goals must yield to the trainer");
        body.setSitting(false); // A preempted native Sit goal clears the physical pose.
        goal.tick();
        assertTrue(body.isSitting(), "Retain the trained posture while movement is held");
        actions.endTraining(player, pet, "test end");
        assertFalse(goal.shouldActivate());
        assertTrue(posture.shouldActivate());
        actions.useOnPet(player, body, treat);
        assertSame(goal, trainingGoal(), "Do not register a new goal for each session");
        pet.bindWord("come", Trick.COME); pet.progress(Trick.COME, 100);
        player.teleport(player.getLocation().add(5, 0, 0));
        actions.onChat(player, pet.name() + " come");
        assertEquals(Activity.ATTENDING, pet.activity());
        assertFalse(goal.shouldActivate(), "Come must move during training");
        new PetTicker(runtime, actions).run();
        assertTrue(navigationRequests > 0);
        body.teleport(player.getLocation().add(1, 0, 0));
        new PetTicker(runtime, actions).run();
        assertEquals(PetOrder.SIT, pet.order());
        assertTrue(goal.shouldActivate(), "Regain attention after Come arrives");
        goal.tick(); lookGoal().tick();
        assertTrue(headHolds > 0, "Sitting training keeps head tracking bounded");
        assertNotNull(lookedAt);
    }

    @Test void trainingFocusReleasesForWaterAndOwnerDeparture() {
        var treat = new ItemStack(Material.COD);
        player.getInventory().setItemInMainHand(treat);
        actions.useOnPet(player, body, treat);
        var goal = trainingGoal();
        assertTrue(goal.shouldActivate());
        inWater = true;
        assertFalse(goal.shouldActivate(), "Training must not stop swimming to safety");
        inWater = false;
        assertTrue(goal.shouldActivate());
        player.teleport(player.getLocation().add(20, 0, 0));
        assertFalse(goal.shouldActivate());
        new PetTicker(runtime, actions).run();
        assertNull(runtime.sessions().training(player.getUniqueId()));
    }

    @Test void partialSitExpiresWithoutEndingTrainingAttention() {
        var treat = new ItemStack(Material.COD);
        player.getInventory().setItemInMainHand(treat);
        actions.useOnPet(player, body, treat);
        var goal = trainingGoal();
        pet.forcedSitUntilMillis(System.currentTimeMillis() + 1000);
        goal.tick();
        assertTrue(body.isSitting());
        pet.forcedSitUntilMillis(0);
        goal.tick();
        assertFalse(body.isSitting());
        assertTrue(goal.shouldActivate());
    }

    @Test void configuredVoiceAppliesToCareToyAndGreetingAndCanBeSilencedOnReload() {
        testConfig.set("pets.wolf.sounds.preset", "cat");
        testConfig.set("pets.wolf.sounds.ambient-interval-seconds", 0);
        runtime.config(CompanionConfig.load(runtime.plugin(), testConfig));
        assertTrue(body.isSilent());
        runtime.voice().happy(body, false); runtime.voice().hurt(body);
        runtime.voice().play(body, net.tfminecraft.companionpets.config.PetSounds.Event.TOY);
        runtime.voice().play(body, net.tfminecraft.companionpets.config.PetSounds.Event.GREETING);
        assertEquals(java.util.List.of(org.bukkit.Sound.ENTITY_CAT_PURR, org.bukkit.Sound.ENTITY_CAT_HURT,
                org.bukkit.Sound.ENTITY_CAT_AMBIENT, org.bukkit.Sound.ENTITY_CAT_AMBIENT), greetingSounds);
        testConfig.set("pets.wolf.sounds", false); runtime.config(CompanionConfig.load(runtime.plugin(), testConfig));
        runtime.voice().happy(body, true); runtime.voice().hurt(body); runtime.voice().ambient(body);
        assertEquals(4, greetingSounds.size()); assertTrue(body.isSilent());
        testConfig.set("pets.wolf.sounds", null); runtime.config(CompanionConfig.load(runtime.plugin(), testConfig));
        assertFalse(body.isSilent(), "Removing overrides restores vanilla sounds");
    }

    @Test void customVoicesHonorPerPetIntervalsAndReloadClearsPreviousThrottle() {
        testConfig.set("pets.wolf.sounds.preset", "none");
        testConfig.set("pets.wolf.sounds.greeting.sounds", java.util.List.of("tfmc:pet.welcome"));
        testConfig.set("pets.wolf.sounds.greeting.min-interval-seconds", 3);
        runtime.config(CompanionConfig.load(runtime.plugin(), testConfig));
        var event = net.tfminecraft.companionpets.config.PetSounds.Event.GREETING;
        assertTrue(runtime.voice().play(body, event, 1, 1, 1000));
        assertFalse(runtime.voice().play(body, event, 1, 1, 3999));
        assertTrue(runtime.voice().play(body, event, 1, 1, 4000));
        assertEquals(java.util.List.of("tfmc:pet.welcome", "tfmc:pet.welcome"), customSounds);
        runtime.config(CompanionConfig.load(runtime.plugin(), testConfig));
        assertTrue(runtime.voice().play(body, event, 1, 1, 4001));
    }

    @Test void excludedTypeKeepsItsRecordAndLoadedBodyWithoutLosingLearning() {
        pet.bindWord("stay", Trick.STAY); pet.progress(Trick.STAY, 71);
        testConfig.set("pets.wolf.entity", "FOX");
        runtime.config(CompanionConfig.load(runtime.plugin(), testConfig));
        actions.reattach(body);
        var ticker = new PetTicker(runtime, actions); ticker.run(); ticker.run();
        assertSame(pet, runtime.store().get(pet.id())); assertSame(body, runtime.entity(pet));
        assertFalse(body.isAware()); assertEquals(71, pet.progress(Trick.STAY));
        assertEquals(Trick.STAY, pet.trickFor("stay")); assertFalse(body.isDead());
        testConfig.set("pets.wolf.entity", "WOLF");
        runtime.config(CompanionConfig.load(runtime.plugin(), testConfig)); actions.reattach(body); ticker.run();
        assertTrue(body.isAware()); assertSame(body, runtime.entity(pet));
    }

    @Test void nativeCombatGuardKeepsOneRegistrationAndReleasesOnConfigurationChange() {
        runtime.bodies().configure(body, runtime.config().type("wolf"));
        var key = com.destroystokyo.paper.entity.ai.GoalKey.of(org.bukkit.entity.Mob.class,
                new NamespacedKey(runtime.plugin(), "native_combat_guard"));
        var goal = org.bukkit.Bukkit.getMobGoals().getGoal(body, key);
        assertTrue(goal.shouldActivate());
        testConfig.set("pets.wolf.native-combat", true); runtime.config(CompanionConfig.load(runtime.plugin(), testConfig));
        assertSame(goal, org.bukkit.Bukkit.getMobGoals().getGoal(body, key)); assertFalse(goal.shouldActivate());
        testConfig.set("pets.wolf.native-combat", false); runtime.config(CompanionConfig.load(runtime.plugin(), testConfig));
        assertSame(goal, org.bukkit.Bukkit.getMobGoals().getGoal(body, key)); assertTrue(goal.shouldActivate());
    }

    @Test void failedBodyReplacementPausesTheOldBodyAndKeepsItsRecord() {
        greetingSpecies = org.bukkit.entity.EntityType.CAT; // Simulate an old body incompatible with WOLF.
        pet.progress(Trick.SPEAK, 61); pet.bindWord("hello", Trick.SPEAK);
        actions.reattach(body); // The fixture also supplies the wrong type for the attempted replacement.
        assertSame(body, runtime.entity(pet)); assertTrue(body.isValid());
        new PetTicker(runtime, actions).run(); assertFalse(body.isAware());
        assertSame(pet, runtime.store().get(pet.id())); assertEquals(61, pet.progress(Trick.SPEAK));
        assertEquals(Trick.SPEAK, pet.trickFor("hello"));
    }
    private void assertCircleRadius(double base) {
        double radius = navigationTarget.distance(player.getLocation());
        assertTrue(radius >= base * 0.85 && radius <= base * 1.15, "Organic radius: " + radius);
    }

    @Test void unlistedSpeciesUsesItsNativeVoiceAndSilentMobsDoNotMakeFoxOrPlayerSounds() {
        greetingSpecies = org.bukkit.entity.EntityType.PIG; nativeSound = org.bukkit.Sound.ENTITY_PIG_AMBIENT;
        net.tfminecraft.companionpets.fx.PetFx.happy(body, true); net.tfminecraft.companionpets.fx.PetFx.ambient(body);
        net.tfminecraft.companionpets.fx.PetFx.sad(body); net.tfminecraft.companionpets.fx.PetFx.hurt(body);
        assertEquals(java.util.List.of(org.bukkit.Sound.ENTITY_PIG_AMBIENT, org.bukkit.Sound.ENTITY_PIG_AMBIENT,
                org.bukkit.Sound.ENTITY_PIG_AMBIENT, org.bukkit.Sound.ENTITY_PIG_HURT), greetingSounds);
        nativeSound = null; net.tfminecraft.companionpets.fx.PetFx.ambient(body); net.tfminecraft.companionpets.fx.PetFx.happy(body, true);
        assertEquals(4, greetingSounds.size());
    }

    @Test void BellyRollKeepsBodyFixedAndHeadBoundedUntilTheWholeMomentEnds() {
        bellyAvailable = true; body.setOnGround(true); body.setBodyYaw(0);
        assertTrue(actions.moments().triggerBelly(pet, body, player));
        var hold = org.bukkit.Bukkit.getMobGoals().getGoal(body, com.destroystokyo.paper.entity.ai.GoalKey.of(
                org.bukkit.entity.Mob.class, new NamespacedKey(runtime.plugin(), "posture_navigation")));
        var look = org.bukkit.Bukkit.getMobGoals().getGoal(body, com.destroystokyo.paper.entity.ai.GoalKey.of(
                org.bukkit.entity.Mob.class, new NamespacedKey("companionpets", "look")));
        assertNotNull(hold); assertTrue(hold.shouldActivate()); assertNotNull(look);
        player.teleport(body.getLocation().add(3, 0, 3));
        new PetTicker(runtime, actions).run();
        for (int i = 0; i < 20; i++) { if (i % 10 == 0) actions.moments().tickBelly(pet, body, player); hold.tick(); look.tick(); }
        assertTrue(headHolds > 0); assertEquals(0, heldBodyYaw); assertEquals(0, body.getBodyYaw());
        assertTrue(Math.abs(heldHeadYaw) <= 50); assertTrue(Math.abs(heldPitch) <= 30);
        assertTrue(Math.abs(heldHeadYaw) > 10, "Head can follow a visible front-side target");
        player.teleport(body.getLocation().add(0, 0, -3));
        for (int i = 0; i < 20; i++) { if (i % 10 == 0) actions.moments().tickBelly(pet, body, player); hold.tick(); look.tick(); }
        assertEquals(0, heldHeadYaw, 0.001, "A target behind cannot twist the resting head");
        bellyActive = false; assertFalse(actions.moments().tickBelly(pet, body, player));
        assertTrue(headReleases > 0); assertFalse(hold.shouldActivate());
    }

    @Test void favoriteToyInEitherHandPromptsInterestButDoesNotInterruptOrdersOrLowNeeds() {
        pet.favoriteToy("STICK"); greetingGround = true; long now = System.currentTimeMillis();
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
        actions.anticipation().tick(now); assertEquals(0, pet.toyExcitedUntilMillis());
        assertEquals(PetSpacing.toyFront(runtime, pet, player), navigationTarget);
        body.teleport(navigationTarget); actions.anticipation().tick(now + 250);
        assertTrue(pet.toyExcitedUntilMillis() > now);
        assertNotNull(org.bukkit.Bukkit.getMobGoals().getGoal(body, com.destroystokyo.paper.entity.ai.GoalKey.of(
                org.bukkit.entity.Mob.class, new NamespacedKey("companionpets", "look"))));
        actions.anticipation().tick(now + 1500);
        int sounds = greetingSounds.size(); actions.anticipation().tick(now + 1550);
        assertEquals(1, sounds); assertEquals(sounds, greetingSounds.size(), "No repeating sound on every tick");
        assertEquals(Activity.TOY_FOCUS, pet.activity());
        actions.anticipation().tick(now + 60_000);
        assertFalse(actions.anticipation().active(pet), "Even a favorite becomes boring without play or owner movement");
        assertEquals(Activity.NONE, pet.activity());
        assertEquals(sounds, greetingSounds.size(), "Boredom does not restart favorite noises");
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR)); actions.anticipation().tick(now + 61_000);
        assertEquals(0, pet.toyExcitedUntilMillis());
        player.getInventory().setItemInOffHand(new ItemStack(Material.STICK)); actions.anticipation().tick(now + 62_000);
        assertTrue(pet.toyExcitedUntilMillis() > now + 62_000);
        pet.order(PetOrder.LAY); actions.anticipation().tick(now + 63_000); assertEquals(0, pet.toyExcitedUntilMillis());
        pet.order(PetOrder.FOLLOW); pet.need(Need.ENERGY, 10); actions.anticipation().tick(now + 64_000);
        assertEquals(0, pet.toyExcitedUntilMillis());
    }

    @Test void ordinaryHeldToyTakesPriorityOverGreetingsBellyAndExplorationUntilPutAway() {
        testConfig.set("items.toys", java.util.List.of("STICK", "BONE"));
        runtime.config(CompanionConfig.load(runtime.plugin(), testConfig));
        long now = System.currentTimeMillis(); greetingGround = true; bellyAvailable = true; body.setOnGround(true);
        pet.favoriteToy("BONE");
        assertTrue(actions.moments().triggerBelly(pet, body, player));
        assertTrue(bellyActive);
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
        new PetTicker(runtime, actions).run();
        assertFalse(bellyActive); assertEquals(Activity.TOY_FOCUS, pet.activity());
        assertEquals(0, pet.toyExcitedUntilMillis());
        assertFalse(actions.greetings().trigger(pet, player, now));
        assertEquals(Activity.TOY_FOCUS, pet.activity());
        org.bukkit.Bukkit.getMobGoals().getGoal(body, com.destroystokyo.paper.entity.ai.GoalKey.of(
                org.bukkit.entity.Mob.class, new NamespacedKey("companionpets", "look"))).tick();
        assertEquals(player.getEyeLocation().subtract(0, 0.55, 0), lookedAt);
        var goalKey = com.destroystokyo.paper.entity.ai.GoalKey.of(org.bukkit.entity.Mob.class,
                new NamespacedKey(runtime.plugin(), "toy_navigation"));
        assertTrue(org.bukkit.Bukkit.getMobGoals().getGoal(body, goalKey).shouldActivate());
        assertEquals(0, greetingSounds.size(), "Normal toy has quiet attention");
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        actions.anticipation().tick(now + 200);
        assertEquals(Activity.NONE, pet.activity()); assertFalse(actions.anticipation().active(pet));
        assertFalse(org.bukkit.Bukkit.getMobGoals().getGoal(body, goalKey).shouldActivate());
        assertTrue(actions.greetings().trigger(pet, player, now + 400));
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
        actions.anticipation().tick(now + 600);
        assertFalse(actions.greeting(pet)); assertEquals(Activity.TOY_FOCUS, pet.activity());
    }

    @Test void heldFavoriteHasTwoInitialSafeHopsThenWaitsBeforeAnotherAndKeepsQuiet() {
        pet.favoriteToy("STICK"); greetingGround = true; body.setOnGround(true); long now = System.currentTimeMillis();
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
        actions.anticipation().tick(now);
        body.teleport(navigationTarget); actions.anticipation().tick(now + 250);
        actions.anticipation().tick(now + 1500);
        assertTrue(body.getVelocity().getY() >= 0.30 && body.getVelocity().getY() <= 0.36);
        double energy = pet.need(Need.ENERGY); assertEquals(99.8, energy, 0.001);
        body.setOnGround(false); body.setVelocity(new org.bukkit.util.Vector(0, -0.1, 0));
        actions.anticipation().tick(now + 2000); assertEquals(-0.1, body.getVelocity().getY());
        body.setOnGround(true); blockedHop = true;
        actions.anticipation().tick(now + 4000); assertEquals(-0.1, body.getVelocity().getY());
        blockedHop = false; actions.anticipation().tick(now + 4500);
        assertTrue(body.getVelocity().getY() >= 0.30); assertEquals(99.6, pet.need(Need.ENERGY), 0.001);
        body.setVelocity(new org.bukkit.util.Vector()); actions.anticipation().tick(now + 5000);
        assertEquals(0, body.getVelocity().getY(), "Pause after the two initial hops");
        int sounds = greetingSounds.size(); actions.anticipation().tick(now + 8000);
        assertEquals(sounds, greetingSounds.size()); assertTrue(sounds <= 2);
        assertEquals(0, body.getVelocity().getY());
        pet.need(Need.ENERGY, 10); actions.anticipation().tick(now + 10_000);
        assertFalse(actions.anticipation().active(pet)); assertEquals(Activity.NONE, pet.activity());
    }

    @Test void heldToyFollowsMovingOwnerBeyondAcquisitionRadiusWithoutHoppingOrDestroyingGoals() {
        greetingGround = true; body.setOnGround(true); pet.favoriteToy("STICK"); long now = System.currentTimeMillis();
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
        actions.anticipation().tick(now);
        var key = com.destroystokyo.paper.entity.ai.GoalKey.of(org.bukkit.entity.Mob.class,
                new NamespacedKey(runtime.plugin(), "toy_navigation"));
        var goal = org.bukkit.Bukkit.getMobGoals().getGoal(body, key);
        UUID entityId = body.getUniqueId();
        body.setVelocity(new org.bukkit.util.Vector());
        player.teleport(player.getLocation().add(8, 0, 0));
        actions.anticipation().tick(now + 2000);
        assertTrue(actions.anticipation().active(pet)); assertEquals(PetSpacing.toyFront(runtime, pet, player), navigationTarget);
        assertEquals(0, body.getVelocity().getY(), "Approach the toy before hopping");
        assertEquals(net.tfminecraft.companionpets.behavior.Locomotion.speed(pet.illness(), pet.bond(), pet.need(Need.CLEANLINESS), false), navigationSpeed);
        int stops = navigationStops; actions.anticipation().advance(pet, now + 2050);
        assertEquals(stops, navigationStops, "Native navigation is not stopped between path decisions");
        player.teleport(player.getLocation().add(1, 0, 0)); actions.anticipation().advance(pet, now + 2300);
        assertEquals(PetSpacing.toyFront(runtime, pet, player), navigationTarget, "Route follows the front of the moving owner");
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        actions.anticipation().advance(pet, now + 2400);
        assertFalse(actions.anticipation().active(pet)); assertEquals(Activity.NONE, pet.activity());
        assertSame(goal, org.bukkit.Bukkit.getMobGoals().getGoal(body, key)); assertFalse(goal.shouldActivate());
        assertTrue(body.isValid()); assertEquals(entityId, pet.entityId());
        body.teleport(player.getLocation().add(1, 0, 0));
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
        actions.anticipation().tick(now + 3000);
        assertSame(goal, org.bukkit.Bukkit.getMobGoals().getGoal(body, key), "Reuse one bounded goal per body");
    }

    @Test void heldToySwitchingPrefersFavoriteInOffhandAndConfigurationCanDisableGestures() {
        testConfig.set("items.toys", java.util.List.of("STICK", "BONE"));
        testConfig.set("pets.wolf.behaviors", java.util.List.of("toy-anticipation"));
        runtime.config(CompanionConfig.load(runtime.plugin(), testConfig));
        pet.favoriteToy("BONE"); greetingGround = true; body.setOnGround(true); long now = System.currentTimeMillis();
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
        actions.anticipation().tick(now); assertEquals(0, pet.toyExcitedUntilMillis());
        body.teleport(navigationTarget);
        player.getInventory().setItemInOffHand(new ItemStack(Material.BONE));
        actions.anticipation().tick(now + 200);
        assertTrue(pet.toyExcitedUntilMillis() > now); assertEquals(0, greetingSounds.size());
        assertEquals(0, body.getVelocity().getY(), "Attention without enabled jumps");
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        actions.anticipation().tick(now + 400);
        assertTrue(actions.anticipation().active(pet)); assertEquals(0, pet.toyExcitedUntilMillis());
        pet.stored(true); actions.anticipation().tick(now + 600); assertFalse(actions.anticipation().active(pet));
    }

    @Test void favoriteCatToyProducesOnlyTwoMeowsWithoutDogHops() {
        greetingSpecies(org.bukkit.entity.EntityType.CAT); pet.favoriteToy("STICK");
        greetingGround = true; body.setOnGround(true); long now = System.currentTimeMillis();
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
        actions.anticipation().tick(now); body.teleport(navigationTarget);
        actions.anticipation().tick(now + 250);
        for (int i = 1; i <= 16; i++) actions.anticipation().tick(now + i * 1000);
        assertEquals(2, greetingSounds.size());
        assertTrue(greetingSounds.stream().allMatch(s -> s == org.bukkit.Sound.ENTITY_CAT_AMBIENT));
        assertEquals(0, body.getVelocity().getY());
    }

    @Test void toyAttentionUsesSeparateFrontPositionsRegardlessOfYawOrPitchAndSharesSoundBudget() {
        greetingGround = true; body.setOnGround(true); pet.favoriteToy("STICK");
        var others = java.util.List.of(secondPet(new Location(player.getWorld(), -3, 64, -2)),
                secondPet(new Location(player.getWorld(), 3, 64, -2)), secondPet(new Location(player.getWorld(), 4, 64, -2)));
        var pets = new java.util.ArrayList<Pet>(); pets.add(pet); pets.addAll(others);
        for (var p : others) p.favoriteToy("STICK");
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK)); long now = System.currentTimeMillis();
        actions.anticipation().tick(now);
        for (float yaw : new float[]{0, 90, 180, -90}) {
            player.teleport(new Location(player.getWorld(), 0, 64, 0, yaw, 85));
            var targets = new java.util.ArrayList<Location>();
            for (var p : pets) {
                Location at = PetSpacing.toyFront(runtime, p, player); assertNotNull(at); targets.add(at);
                double angle = Math.toRadians(yaw);
                assertTrue(-Math.sin(angle) * at.getX() + Math.cos(angle) * at.getZ() >= 2.3,
                        "Every pet stays in front even when the player looks almost straight down");
            }
            for (int i = 0; i < targets.size(); i++) for (int j = i + 1; j < targets.size(); j++)
                assertTrue(targets.get(i).distance(targets.get(j)) >= 0.9, "Fallback front positions still leave body clearance");
        }
        player.teleport(new Location(player.getWorld(), 0, 64, 0));
        for (var p : pets) runtime.entity(p).teleport(PetSpacing.toyFront(runtime, p, player));
        actions.anticipation().tick(now + 250);
        int lastCount = greetingSounds.size(); long lastVoice = Long.MIN_VALUE;
        for (int i = 1; i <= 120; i++) {
            long at = now + 250 + i * 100;
            actions.anticipation().tick(at);
            int count = greetingSounds.size();
            if (count > lastCount) {
                assertEquals(lastCount + 1, count, "The whole group cannot bark together");
                if (lastVoice != Long.MIN_VALUE) assertTrue(at - lastVoice >= 2800);
                lastVoice = at; lastCount = count;
            }
        }
        assertTrue(greetingSounds.size() > 0); assertTrue(greetingSounds.size() <= 3);
        assertTrue(pets.stream().allMatch(p -> actions.anticipation().active(p)));
        assertTrue(pet.need(Need.ENERGY) >= 99.4 - 0.001, "Repeated waiting hops stay spaced out");
    }

    @Test void dogsShuffleAndOccasionallyHopForOrdinaryToyWithoutSoundsOrGroupMessage() {
        greetingGround = true; body.setOnGround(true); pet.favoriteToy("BONE"); long now = System.currentTimeMillis();
        var other = secondPet(new Location(player.getWorld(), -4, 64, 0));
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
        actions.anticipation().tick(now);
        assertTrue(actions.anticipation().active(pet)); assertTrue(actions.anticipation().active(other));
        assertFalse(net.tfminecraft.companionpets.fx.PetFx.refreshHeld(player), "Toy attention adds no message naming one pet");
        body.teleport(PetSpacing.toyFront(runtime, pet, player));
        actions.anticipation().advance(pet, now + 250); Location first = navigationTarget;
        actions.anticipation().advance(pet, now + 1250); Location second = navigationTarget;
        assertTrue(first.distance(second) > 1, "The dog steps sideways and then comes a little closer");
        assertTrue(second.getZ() > 1.8, "Playful movement stays visible in front");
        actions.anticipation().advance(pet, now + 3000);
        assertTrue(body.getVelocity().getY() >= 0.30); assertEquals(99.8, pet.need(Need.ENERGY), 0.001);
        body.setVelocity(new org.bukkit.util.Vector());
        actions.anticipation().advance(pet, now + 6000);
        assertEquals(0, body.getVelocity().getY(), "Waiting hops have a real pause");
        actions.anticipation().advance(pet, now + 14000);
        assertTrue(body.getVelocity().getY() >= 0.30); assertEquals(99.6, pet.need(Need.ENERGY), 0.001);
        assertEquals(0, greetingSounds.size());
        pet.need(Need.ENERGY, 30); body.setVelocity(new org.bukkit.util.Vector());
        actions.anticipation().advance(pet, now + 30000);
        assertEquals(0, body.getVelocity().getY(), "A tired dog can watch without jumping");
    }

    @Test void dogTailStaysFastForEntireToyWaitAndRespectsBehaviorSettingAndRelease() {
        testConfig.set("pets.wolf.appearance.type", "modelengine"); testConfig.set("pets.wolf.appearance.model", "beagle");
        runtime.config(CompanionConfig.load(runtime.plugin(), testConfig)); eagerGreeting(); greetingGround = true;
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
        actions.anticipation().tick(System.currentTimeMillis());
        var ticker = new net.tfminecraft.companionpets.visual.PetVisualTicker(runtime);
        ticker.run(); assertEquals(4.5, tailHz, 0.001, "Ordinary toys also get fast tail motion");
        pet.toyExcitedUntilMillis(System.currentTimeMillis() + 6000); ticker.run(); assertEquals(5.3, tailHz, 0.001);
        pet.toyExcitedUntilMillis(0); ticker.run(); assertEquals(4.5, tailHz, 0.001, "Tail keeps moving after the initial excitement");
        testConfig.set("pets.wolf.behaviors", java.util.List.of("toy-anticipation"));
        runtime.config(CompanionConfig.load(runtime.plugin(), testConfig)); ticker.run(); assertEquals(0, tailHz);
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        actions.anticipation().advance(pet, System.currentTimeMillis() + 1000);
        assertEquals(0, tailHz); assertFalse(actions.anticipation().active(pet));
    }

    @Test void brieflyPuttingAwayFavoriteDoesNotRestartItsNoiseAndHops() {
        greetingGround = true; pet.favoriteToy("STICK"); long now = System.currentTimeMillis();
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
        actions.anticipation().tick(now); body.teleport(navigationTarget); actions.anticipation().tick(now + 250);
        actions.anticipation().tick(now + 1500); int sounds = greetingSounds.size();
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR)); actions.anticipation().tick(now + 2000);
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK)); actions.anticipation().tick(now + 2250);
        actions.anticipation().tick(now + 3500);
        assertTrue(actions.anticipation().active(pet)); assertEquals(0, pet.toyExcitedUntilMillis());
        assertEquals(sounds, greetingSounds.size());
    }

    @Test void boringHeldToyReleasesNativeGoalAndStaysIgnoredUntilOwnerMoves() {
        greetingGround = true; pet.favoriteToy("BONE"); long now = System.currentTimeMillis();
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
        actions.roaming().tickOwners(now); actions.anticipation().tick(now);
        var key = com.destroystokyo.paper.entity.ai.GoalKey.of(org.bukkit.entity.Mob.class,
                new NamespacedKey(runtime.plugin(), "toy_navigation"));
        var goal = org.bukkit.Bukkit.getMobGoals().getGoal(body, key); assertTrue(goal.shouldActivate());
        actions.anticipation().advance(pet, now + 19_999); assertTrue(actions.anticipation().active(pet));
        actions.anticipation().advance(pet, now + 20_000);
        assertFalse(actions.anticipation().active(pet)); assertEquals(Activity.NONE, pet.activity());
        assertFalse(goal.shouldActivate()); assertEquals(0, tailHz); assertEquals(0, pet.toyExcitedUntilMillis());
        for (int i = 1; i <= 10; i++) actions.anticipation().tick(now + 20_000 + i * 500);
        assertFalse(actions.anticipation().active(pet), "The next manager ticks cannot reacquire the boring toy");
        assertTrue(body.isAware(), "Native exploration can resume");
        player.teleport(player.getLocation().add(0.6, 0, 0)); actions.anticipation().tick(now + 27_000);
        assertTrue(actions.anticipation().active(pet)); assertSame(goal, org.bukkit.Bukkit.getMobGoals().getGoal(body, key));
        actions.anticipation().tick(now + 46_999); assertTrue(actions.anticipation().active(pet));
        actions.anticipation().tick(now + 47_000); assertFalse(actions.anticipation().active(pet));
    }

    @Test void favoriteHoldsAttentionLongerAndPetHopsOrOwnerJitterDoNotRenewInterest() {
        greetingGround = true; pet.favoriteToy("STICK"); long now = System.currentTimeMillis();
        var other = secondPet(new Location(player.getWorld(), -2, 64, 0)); other.favoriteToy("BONE");
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
        actions.anticipation().tick(now);
        for (int i = 1; i <= 40; i++) {
            player.teleport(new Location(player.getWorld(), i % 2 == 0 ? 0.1 : -0.1, 64, 0));
            body.teleport(new Location(player.getWorld(), 0, 64 + (i % 2 == 0 ? 0.4 : 0), 2.4));
            actions.anticipation().tick(now + i * 500);
        }
        assertFalse(actions.anticipation().active(other)); assertTrue(actions.anticipation().active(pet));
        actions.anticipation().tick(now + 34_999); assertTrue(actions.anticipation().active(pet));
        actions.anticipation().tick(now + 35_000); assertFalse(actions.anticipation().active(pet));
    }

    @Test void puttingAwayOrChangingToyRenewsInterestAndBoredPetsCanStillFetch() {
        testConfig.set("items.toys", java.util.List.of("STICK", "BONE"));
        testConfig.set("play.toy-attention-seconds", 2); testConfig.set("play.favorite-toy-attention-seconds", 4);
        runtime.config(CompanionConfig.load(runtime.plugin(), testConfig));
        greetingGround = true; pet.favoriteToy("BONE"); long now = System.currentTimeMillis();
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK)); actions.anticipation().tick(now);
        actions.anticipation().tick(now + 2000); assertFalse(actions.anticipation().active(pet));
        assertTrue(actions.fetchActions().canChase(pet), "Waiting boredom does not disable actual toy play");
        player.getInventory().setItemInMainHand(new ItemStack(Material.BONE)); actions.anticipation().tick(now + 2500);
        assertTrue(actions.anticipation().active(pet));
        actions.anticipation().tick(now + 6500); assertFalse(actions.anticipation().active(pet));
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR)); actions.anticipation().tick(now + 7000);
        player.getInventory().setItemInMainHand(new ItemStack(Material.BONE)); actions.anticipation().tick(now + 7500);
        assertTrue(actions.anticipation().active(pet));
        actions.anticipation().tick(now + 11500); assertFalse(actions.anticipation().active(pet));
        actions.anticipation().ownerThrew(player); actions.anticipation().tick(now + 12000);
        assertTrue(actions.anticipation().active(pet), "Throwing renews interest even with more of the same toy in hand");
    }

    @Test void movingWithToyKeepsRenewingInterestUntilOwnerStops() {
        greetingGround = true; pet.favoriteToy("BONE"); long now = System.currentTimeMillis();
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK)); actions.anticipation().tick(now);
        for (int i = 1; i <= 8; i++) {
            player.teleport(player.getLocation().add(0.6, 0, 0)); actions.anticipation().tick(now + i * 10_000);
            assertTrue(actions.anticipation().active(pet), "Moving owner remains interesting beyond the initial duration");
        }
        actions.anticipation().tick(now + 100_000); assertFalse(actions.anticipation().active(pet));
    }

    @Test void behaviorAllowListDisablesOptionalActionsWhileCareAndLearnedOrdersStillWork() {
        testConfig.set("pets.wolf.behaviors", java.util.List.of()); runtime.config(CompanionConfig.load(runtime.plugin(), testConfig));
        assertFalse(actions.greetings().trigger(pet, player, System.currentTimeMillis()));
        assertFalse(actions.fetchActions().canChase(pet)); assertFalse(actions.moments().triggerDig(pet, body, player));
        pet.need(Need.HUNGER, 10); var food = new ItemStack(Material.BEEF, 2);
        assertTrue(actions.useOnPet(player, body, food)); assertTrue(pet.need(Need.HUNGER) > 10);
        pet.bindWord("sit", Trick.SIT); pet.progress(Trick.SIT, 100); actions.onChat(player, "Toby sit");
        assertEquals(PetOrder.SIT, pet.order());
    }

    @Test void configuredToyVoiceDoesNotDependOnMovementBody() {
        testConfig.set("pets.wolf.sounds.preset", "frog");
        runtime.config(CompanionConfig.load(runtime.plugin(), testConfig));
        pet.favoriteToy("STICK"); greetingGround = true; body.setOnGround(true);
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
        long now = System.currentTimeMillis();
        actions.anticipation().tick(now); body.teleport(navigationTarget);
        actions.anticipation().tick(now + 250); actions.anticipation().tick(now + 1500);
        assertTrue(actions.anticipation().active(pet)); assertTrue(actions.fetchActions().canChase(pet));
        assertEquals(java.util.List.of(org.bukkit.Sound.ENTITY_FROG_AMBIENT), greetingSounds);
        assertTrue(body.getVelocity().getY() > 0); assertEquals(Activity.TOY_FOCUS, pet.activity());
    }

    @Test void speciesWithoutFetchDoNotShowMisleadingToyHint() {
        greetingSpecies(org.bukkit.entity.EntityType.PIG);
        assertFalse(actions.useOnPet(player, body, new ItemStack(Material.STICK)));
        assertEquals(Activity.NONE, pet.activity());
    }

    @Test void pettingBuildsFamiliarityEvenWhenAffectionGesturesAreDisabled() {
        testConfig.set("pets.wolf.behaviors", java.util.List.of("recognize-carers"));
        runtime.config(CompanionConfig.load(runtime.plugin(), testConfig));
        var other = org.bukkit.Bukkit.getServer().getPlayer(player.getUniqueId());
        var visitor = ((org.mockbukkit.mockbukkit.ServerMock) org.bukkit.Bukkit.getServer()).addPlayer();
        visitor.teleport(player.getLocation());
        assertTrue(actions.useOnPet(visitor, body, new ItemStack(Material.AIR)));
        assertEquals(4, pet.carers().trust(visitor.getUniqueId()));
        assertEquals(other.getUniqueId(), pet.ownerId());
        assertEquals(PetOrder.FOLLOW, pet.order());
    }

    @Test void carersAreRememberedThroughRealCareAndRecognizedWithoutChangingOwnershipOrPosture() {
        var other = MockBukkit.getMock().addPlayer(); other.teleport(player.getLocation());
        long now = System.currentTimeMillis(); var care = new PetCareActions(runtime);
        assertTrue(care.groom(other, pet, body, new ItemStack(Material.FEATHER), runtime.config().type("wolf"), now));
        assertTrue(care.groom(other, pet, body, new ItemStack(Material.FEATHER), runtime.config().type("wolf"), now + 500));
        assertEquals(6, pet.carers().trust(other.getUniqueId()));
        care.groom(other, pet, body, new ItemStack(Material.FEATHER), runtime.config().type("wolf"), now + 60000);
        assertTrue(pet.carers().familiar(other.getUniqueId()));
        pet.order(PetOrder.SIT); body.setSitting(true);
        actions.greetings().tick(now + 61000); assertFalse(actions.greeting(pet));
        other.teleport(player.getLocation().add(20, 0, 0)); actions.greetings().tick(now + 120000);
        other.teleport(player.getLocation()); actions.greetings().tick(now + 122000);
        assertTrue(actions.greeting(pet)); assertEquals(PetOrder.SIT, pet.order()); assertEquals(Activity.NONE, pet.activity());
        assertTrue(body.isSitting()); assertEquals(player.getUniqueId(), pet.ownerId()); assertEquals(0, pet.lastGreetingMillis());
        assertTrue(pet.carers().get(other.getUniqueId()).greetedAt() > 0);
        actions.greetings().advance(pet, now + 125000); assertFalse(actions.greeting(pet));
        other.getInventory().setItemInMainHand(new ItemStack(Material.COD));
        actions.useOnPet(other, body, other.getInventory().getItemInMainHand()); assertNull(runtime.sessions().training(other.getUniqueId()));
    }

    private Pet secondPet(Location at) {
        var other = new Pet(UUID.randomUUID(), player.getUniqueId(), "wolf", "Luna", PetSex.FEMALE);
        var otherBody = at.getWorld().spawn(at, org.bukkit.entity.Wolf.class);
        otherBody.getPersistentDataContainer().set(runtime.petKey(), org.bukkit.persistence.PersistentDataType.STRING, other.id().toString());
        runtime.store().add(other); runtime.remember(other, otherBody); other.bond(100); other.personality(PetPersonality.FRIENDLY);
        return other;
    }

    @Test void positivePetEncountersBuildMutualFamiliarityWhileInterruptedOnesDoNot() {
        var other = secondPet(body.getLocation().add(0, 0, 1.2)); long now = System.currentTimeMillis();
        assertTrue(actions.social().trigger(player, pet, "sniff"));
        actions.social().tick(now + 1000); actions.social().tick(now + 6000);
        assertEquals(6, pet.friends().trust(other.id())); assertEquals(6, other.friends().trust(pet.id()));
        assertTrue(actions.social().trigger(player, pet, "sniff")); actions.social().cancel(pet);
        assertEquals(6, pet.friends().trust(other.id()), "Interrupted meeting gives no extra trust");
        assertTrue(actions.social().trigger(player, pet, "sniff", now + 62000));
        actions.social().tick(now + 63000); actions.social().tick(now + 67000);
        assertEquals(12, actions.social().friendship(pet, other));
    }

    @Test void normalFollowingLeavesNativeNavigationUntouchedAndDoesNotTeleport() {
        testConfig.set("pets.wolf.behaviors", java.util.List.of());
        runtime.config(CompanionConfig.load(runtime.plugin(), testConfig));
        greetingGround = true; pet.bond(100);
        body.teleport(player.getLocation().add(40, 0, 0));
        existingFollowPath = true;
        var ticker = new PetTicker(runtime, actions);
        int stops = navigationStops, requests = navigationRequests;
        var at = body.getLocation();
        ticker.run(); ticker.run();
        assertEquals(requests, navigationRequests, "No custom follow or stroll path requests");
        assertEquals(stops, navigationStops, "Existing native paths are preserved");
        assertEquals(at, body.getLocation(), "The plugin no longer teleports normal followers");
        assertTrue(body.isAware());
        var key = com.destroystokyo.paper.entity.ai.GoalKey.of(org.bukkit.entity.Mob.class,
                new NamespacedKey(runtime.plugin(), "follow_navigation"));
        assertNull(MockBukkit.getMock().getMobGoals().getGoal(body, key));
    }

    @Test void restSuspendsNavigationAndFollowingResumesWithoutReplacingNativePaths() {
        testConfig.set("pets.wolf.behaviors", java.util.List.of());
        runtime.config(CompanionConfig.load(runtime.plugin(), testConfig));
        var ticker = new PetTicker(runtime, actions);
        pet.order(PetOrder.LAY); ticker.run(); assertTrue(body.isSitting());
        pet.order(PetOrder.FOLLOW); ticker.run(); assertFalse(body.isSitting()); assertTrue(body.isAware());
        int stops = navigationStops;
        body.setSitting(true); // A native cat's bed/block pose must not be cleared every behavior tick.
        ticker.run(); assertTrue(body.isSitting()); assertEquals(stops, navigationStops);
        pet.need(Need.ENERGY, 10); ticker.run(); assertTrue(body.isSitting());
        pet.need(Need.ENERGY, 100); pet.activity(Activity.NONE); ticker.run();
        assertFalse(body.isSitting()); assertTrue(body.isAware());
        pet.order(PetOrder.STAY); pet.staying(true); ticker.run(); assertFalse(body.isAware());
    }
    @Test void changingOwnershipEndsBothSidesOfAnActivePetMeeting() {
        greetingGround = true; eagerGreeting();
        var other = secondPet(body.getLocation().add(0, 0, 1.2));
        assertTrue(actions.social().trigger(player, pet, "greeting"));
        pet.ownerId(UUID.randomUUID()); actions.social().advance(other, System.currentTimeMillis() + 500);
        assertFalse(actions.social().engaged(pet)); assertFalse(actions.social().engaged(other));
        assertEquals(0, pet.socialTailHz()); assertEquals(0, other.socialTailHz());
        assertEquals(0, actions.social().friendship(pet, other));
    }

    @Test void initialPetGreetingStartsWhileOwnersMoveAndDoesNotRepeatWhileTogether() {
        greetingGround = true; eagerGreeting();
        var other = secondPet(body.getLocation().add(0, 0, 1.2)); long now = System.currentTimeMillis();
        actions.roaming().tickOwners(now); player.teleport(player.getLocation().add(0.5, 0, 0));
        actions.roaming().tickOwners(now + 100); actions.social().tick(now + 100);
        assertTrue(actions.social().engaged(pet)); assertTrue(actions.social().engaged(other));
        var key = com.destroystokyo.paper.entity.ai.GoalKey.of(org.bukkit.entity.Mob.class,
                new NamespacedKey(runtime.plugin(), "social_navigation"));
        var goal = org.bukkit.Bukkit.getMobGoals().getGoal(body, key);
        assertNotNull(goal); assertTrue(goal.shouldActivate());
        assertTrue(pet.socialTailHz() > 0); assertTrue(other.socialTailHz() > 0);
        var teleport = new org.bukkit.event.entity.EntityTeleportEvent(body, body.getLocation(), player.getLocation());
        new net.tfminecraft.companionpets.listen.PetListener(runtime, actions).onTeleport(teleport);
        assertTrue(teleport.isCancelled(), "Native follow cannot teleport a pet during its meeting");
        actions.social().tick(now + 500); actions.social().tick(now + 3300);
        assertFalse(actions.social().engaged(pet)); assertFalse(goal.shouldActivate());
        assertEquals(2, pet.friends().trust(other.id())); assertEquals(2, other.friends().trust(pet.id()));
        assertEquals(0, pet.socialTailHz()); assertEquals(0, other.socialTailHz());
        int voices = greetingSounds.size();
        actions.social().tick(now + 3400); assertFalse(actions.social().engaged(pet));
        assertEquals(voices, greetingSounds.size());
        // Keep the owners moving so a later stationary sniff cannot obscure the initial greeting check.
        player.teleport(player.getLocation().add(0.5, 0, 0)); actions.roaming().tickOwners(now + 23000);
        actions.social().tick(now + 23000); assertFalse(actions.social().engaged(pet));
        assertSame(goal, org.bukkit.Bukkit.getMobGoals().getGoal(body, key));
    }

    @Test void newPetMeetingsAreSearchedOnlyAtTheConfiguredInterval() {
        greetingGround = true; eagerGreeting(); var other = secondPet(body.getLocation().add(9, 0, 0));
        long now = System.currentTimeMillis();
        actions.social().tick(now); assertFalse(actions.social().engaged(pet));
        runtime.entity(other).teleport(body.getLocation().add(0, 0, 1.2));
        actions.social().tick(now + 500); actions.social().tick(now + 1500);
        assertFalse(actions.social().engaged(pet), "Pets that just met wait for the next search");
        actions.social().tick(now + 2000); assertTrue(actions.social().engaged(pet));
    }

    @Test void separationRenewsGreetingButBriefDistanceAndInterruptionsDoNotAddFriendship() {
        greetingGround = true; eagerGreeting(); var other = secondPet(body.getLocation().add(0, 0, 1.2));
        var otherBody = runtime.entity(other); long now = System.currentTimeMillis();
        actions.social().tick(now); actions.social().tick(now + 500); actions.social().tick(now + 3100);
        assertEquals(2, actions.social().friendship(pet, other));
        otherBody.teleport(body.getLocation().add(9, 0, 0)); actions.social().tick(now + 4000);
        actions.social().tick(now + 11000); otherBody.teleport(body.getLocation().add(0, 0, 1.2));
        // New meetings are searched every two seconds; this tick is a search.
        actions.social().tick(now + 13000); assertFalse(actions.social().engaged(pet));
        otherBody.teleport(body.getLocation().add(9, 0, 0)); actions.social().tick(now + 14000);
        actions.social().tick(now + 25000); otherBody.teleport(body.getLocation().add(0, 0, 1.2));
        actions.social().tick(now + 27000); assertTrue(actions.social().engaged(pet));
        pet.order(PetOrder.LAY); actions.social().advance(pet, now + 27500);
        assertFalse(actions.social().engaged(other)); assertEquals(2, actions.social().friendship(pet, other));
        assertEquals(0, other.socialTailHz());
    }

    @Test void eachCompletedPositiveInteractionAddsFriendshipWithinTheRollingMinuteBudget() {
        eagerGreeting(); var other = secondPet(body.getLocation().add(0, 0, 1.2)); long now = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            long start = now + i * 6000;
            assertTrue(actions.social().trigger(player, pet, "sniff", start));
            actions.social().advance(pet, start + 100); actions.social().advance(pet, start + 5100);
            assertEquals(Math.min(12, (i + 1) * 6), actions.social().friendship(pet, other));
        }
        assertTrue(actions.social().trigger(player, pet, "sniff", now + 66000));
        actions.social().advance(pet, now + 66100); actions.social().advance(pet, now + 71100);
        assertEquals(18, actions.social().friendship(pet, other));
    }

    @Test void shyPetsKeepDistanceUntilFamiliarAndOnlyMutuallyAcceptedPlayCanStart() {
        greetingGround = true; pet.personality(PetPersonality.PLAYFUL);
        var other = secondPet(body.getLocation().add(0, 0, 4)); other.personality(PetPersonality.SHY);
        long now = System.currentTimeMillis();
        assertFalse(actions.social().trigger(player, pet, "chase", now));
        assertTrue(actions.social().trigger(player, pet, "greeting", now));
        actions.social().advance(pet, now + 500);
        assertNotNull(navigationTarget); assertTrue(navigationTarget.distance(runtime.entity(other).getLocation()) >= 2.3,
                "The invitation respects the stranger's personal space");
        assertEquals(0, other.socialTailHz(), "Shy observer has no excited tail cue");
        actions.social().cancel(pet);
        pet.friends().reinforce(other.id(), 20, now, 0); other.friends().reinforce(pet.id(), 20, now, 0);
        assertTrue(actions.social().trigger(player, pet, "greeting", now + 6000));
        assertTrue(other.socialTailHz() > 0, "The shy pet responds warmly to its friend");
        actions.social().cancel(pet);
        assertTrue(actions.social().trigger(player, pet, "chase", now + 10000));
        actions.social().cancel(pet); other.need(Need.ENERGY, 30);
        assertFalse(actions.social().trigger(player, pet, "chase", now + 11000));
    }

    @Test void warningsUnreachableGreetingsAndMotionlessChasesDoNotBuildFriendship() {
        greetingGround = true; eagerGreeting(); var other = secondPet(body.getLocation().add(0, 0, 1));
        other.personality(PetPersonality.TERRITORIAL); long now = System.currentTimeMillis();
        assertTrue(actions.social().trigger(player, pet, "greeting", now));
        actions.social().advance(pet, now + 100); actions.social().advance(pet, now + 3100);
        assertEquals(0, actions.social().friendship(pet, other));
        assertEquals(1, greetingSounds.stream().filter(s -> s == org.bukkit.Sound.ENTITY_WOLF_GROWL).count());
        other.personality(PetPersonality.FRIENDLY); runtime.entity(other).teleport(body.getLocation().add(0, 0, 4));
        reachableGreetingPath = false; navigationTarget = null;
        assertTrue(actions.social().trigger(player, pet, "greeting", now + 6000));
        actions.social().advance(pet, now + 6100); actions.social().advance(pet, now + 9100);
        assertNull(navigationTarget); assertEquals(0, actions.social().friendship(pet, other));
        reachableGreetingPath = true;
        assertTrue(actions.social().trigger(player, pet, "chase", now + 12000));
        actions.social().advance(pet, now + 12100); actions.social().advance(pet, now + 18100);
        assertEquals(0, actions.social().friendship(pet, other), "The pets must actually play together");
    }

    @Test void friendshipRewardsAnActualChaseAndFamiliarTerritorialPetsDoNotProtest() {
        greetingGround = true; eagerGreeting(); var other = secondPet(body.getLocation().add(0, 0, 2));
        long now = System.currentTimeMillis();
        assertTrue(actions.social().trigger(player, pet, "chase", now));
        actions.social().advance(pet, now + 100);
        body.teleport(body.getLocation().add(1, 0, 0)); runtime.entity(other).teleport(runtime.entity(other).getLocation().add(1, 0, 0));
        actions.social().advance(pet, now + 2000); actions.social().advance(pet, now + 6100);
        assertEquals(8, actions.social().friendship(pet, other));
        pet.friends().reinforce(other.id(), 20, now + 7000, 0); other.friends().reinforce(pet.id(), 20, now + 7000, 0);
        pet.personality(PetPersonality.TERRITORIAL); other.personality(PetPersonality.TERRITORIAL);
        assertFalse(actions.social().trigger(player, pet, "bark", now + 8000));
        int sounds = greetingSounds.size();
        assertTrue(actions.social().trigger(player, pet, "greeting", now + 9000));
        assertTrue(greetingSounds.subList(sounds, greetingSounds.size()).stream().noneMatch(s -> s == org.bukkit.Sound.ENTITY_WOLF_GROWL));
    }

    @Test void socialGreetingRespectsVisibilityCapabilitiesTrainingAndToyPriority() {
        greetingGround = true; eagerGreeting(); var other = secondPet(body.getLocation().add(0, 0, 1.2));
        blockedSocialSight = true; assertFalse(actions.social().trigger(player, pet, "greeting"));
        blockedSocialSight = false; pet.order(PetOrder.SIT); assertFalse(actions.social().trigger(player, pet, "greeting"));
        pet.order(PetOrder.FOLLOW); pet.illness(Illness.SICK); assertFalse(actions.social().trigger(player, pet, "greeting"));
        pet.illness(Illness.NONE);
        runtime.sessions().training(player.getUniqueId(), new net.tfminecraft.companionpets.session.TrainingSession(pet.id()));
        assertFalse(actions.social().trigger(player, pet, "greeting")); runtime.sessions().clearTraining(player.getUniqueId());
        assertTrue(actions.social().trigger(player, pet, "greeting"));
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK)); actions.anticipation().tick(System.currentTimeMillis());
        assertFalse(actions.social().engaged(pet)); assertEquals(Activity.TOY_FOCUS, pet.activity());
        assertEquals(0, pet.socialTailHz()); assertEquals(0, other.socialTailHz()); assertEquals(0, actions.social().friendship(pet, other));
        actions.clearInteractions(); player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        testConfig.set("pets.wolf.behaviors", java.util.List.of("social-sniff", "pet-friendships"));
        runtime.config(CompanionConfig.load(runtime.plugin(), testConfig));
        assertFalse(actions.social().trigger(player, pet, "greeting"));
        assertTrue(actions.social().trigger(player, pet, "sniff"));
    }

    @Test void catMeetingUsesMeowsWithoutDogHopsAndStaffCanTriggerTheMeeting() {
        greetingGround = true; greetingSpecies(org.bukkit.entity.EntityType.CAT); eagerGreeting();
        var other = secondPet(body.getLocation().add(0, 0, 1.2)); pet.personality(PetPersonality.PLAYFUL);
        body.setOnGround(true); ((WolfMock) runtime.entity(other)).setOnGround(true); lookingAtPet = true;
        assertTrue(actions.triggerTestMoment(player, "pet-greeting"));
        actions.social().advance(pet, System.currentTimeMillis() + 700);
        assertEquals(java.util.List.of(org.bukkit.Sound.ENTITY_CAT_AMBIENT, org.bukkit.Sound.ENTITY_CAT_AMBIENT), greetingSounds);
        assertEquals(0, body.getVelocity().getY()); assertEquals(0, pet.socialTailHz());
    }

    @Test void severalPetsHaveSeparatedGreetingDestinations() {
        greetingGround = true; eagerGreeting(); var other = secondPet(player.getLocation().add(-4, 0, 0));
        long now = System.currentTimeMillis(); assertTrue(actions.greetings().trigger(pet, player, now));
        assertTrue(actions.greetings().trigger(other, player, now));
        actions.greetings().advance(pet, now + 3400); var first = navigationTarget.clone();
        actions.greetings().advance(other, now + 3400); var second = navigationTarget.clone();
        assertTrue(first.distance(second) >= 1, "Front slots stay separated");
    }

    @Test void reunionAfterDistanceAbsenceWakesHeldPetsAndConsumesReturnOnce() {
        greetingGround = true;
        long now = System.currentTimeMillis();
        actions.greetings().tick(now);
        pet.order(PetOrder.LAY); pet.activity(Activity.SLEEPING); pet.staying(true);
        pet.forcedSitUntilMillis(now + 1_000_000); body.setSitting(true); body.setAware(false);
        var home = player.getLocation();
        player.teleport(home.clone().add(40, 0, 0));
        actions.greetings().tick(now + 299_000);
        assertFalse(actions.greeting(pet));
        player.teleport(home);
        actions.greetings().tick(now + 301_000);
        assertTrue(actions.greeting(pet)); assertEquals(Activity.GREETING, pet.activity());
        assertEquals(PetOrder.FOLLOW, pet.order()); assertFalse(pet.staying());
        assertEquals(0, pet.forcedSitUntilMillis()); assertFalse(body.isSitting()); assertTrue(body.isAware());
        assertNotNull(navigationTarget);
        var event = new org.bukkit.event.entity.EntityTeleportEvent(body, body.getLocation(), player.getLocation());
        new net.tfminecraft.companionpets.listen.PetListener(runtime, actions).onTeleport(event);
        assertTrue(event.isCancelled(), "Vanilla follow cannot teleport during the circle");
        actions.greetings().tick(now + 302_000);
        assertEquals(now + 301_000, pet.lastGreetingMillis());
        actions.greetings().tick(now + 310_000);
        assertFalse(actions.greeting(pet)); assertEquals(Activity.NONE, pet.activity());
        assertEquals(PetOrder.FOLLOW, pet.order());
    }

    @Test void shortAbsencesAndCooldownDoNotCauseRepeatedGreetings() {
        long now = System.currentTimeMillis();
        pet.lastOwnerNearbyMillis(now - 299_000);
        actions.greetings().tick(now);
        assertFalse(actions.greeting(pet)); assertEquals(now, pet.lastOwnerNearbyMillis());
        pet.lastOwnerNearbyMillis(now - 301_000); pet.lastGreetingMillis(now - 1000);
        actions.greetings().tick(now);
        assertFalse(actions.greeting(pet)); assertEquals(now - 1000, pet.lastGreetingMillis());
        pet.lastOwnerNearbyMillis(0); pet.lastGreetingMillis(0);
        actions.greetings().tick(now);
        assertFalse(actions.greeting(pet), "Old saves start tracking without a false reunion");
    }

    @Test void reunionHistorySurvivesReconnectAndWaitsForUnloadedBody() {
        greetingGround = true;
        long now = System.currentTimeMillis();
        actions.greetings().ownerDeparted(player, now - 301_000);
        actions.ownerSessionChanged(player);
        UUID realBody = pet.entityId();
        pet.entityId(UUID.randomUUID());
        actions.greetings().tick(now);
        assertFalse(actions.greeting(pet)); assertEquals(now - 301_000, pet.lastOwnerNearbyMillis());
        pet.entityId(realBody);
        actions.greetings().tick(now + 500);
        assertTrue(actions.greeting(pet)); assertTrue(runtime.followingAllowed(pet, player));
        var restored = loadedStore(runtime.plugin()).get(pet.id());
        assertEquals(now + 500, restored.lastGreetingMillis());
    }

    @Test void testGreetingBypassesAbsenceFromAllHeldOrdersAndNewOrderCancelsIt() {
        greetingGround = true;
        lookingAtPet = true;
        long now = System.currentTimeMillis();
        for (PetOrder order : new PetOrder[]{PetOrder.SIT, PetOrder.LAY, PetOrder.STAY}) {
            pet.order(order); pet.staying(order == PetOrder.STAY); body.setSitting(true); body.setAware(false);
            assertTrue(actions.triggerTestMoment(player, "greeting"));
            assertTrue(actions.greeting(pet)); assertFalse(body.isSitting());
            assertEquals(PetOrder.FOLLOW, pet.order());
            pet.order(PetOrder.SIT);
            actions.greetings().advance(pet, now + 200);
            assertFalse(actions.greeting(pet)); assertEquals(PetOrder.SIT, pet.order());
            assertEquals(Activity.NONE, pet.activity());
        }
        pet.order(PetOrder.FOLLOW);
        assertTrue(actions.greetings().trigger(pet, player, now));
        actions.clearInteractions(pet);
        assertFalse(actions.greeting(pet)); assertEquals(Activity.NONE, pet.activity());
    }

    @Test void greetingRoutesCircleAroundMovingOwnerAndSkipsUnsafeOrUnreachableGround() {
        eagerGreeting();
        greetingGround = true;
        long now = System.currentTimeMillis();
        assertTrue(actions.greetings().trigger(pet, player, now));
        Location first = navigationTarget.clone();
        assertCircleRadius(2);
        player.teleport(player.getLocation().add(3, 0, 0));
        actions.greetings().advance(pet, now + 200);
        assertEquals(first.getX() + 3, navigationTarget.getX(), 0.001);
        assertCircleRadius(2);
        body.teleport(navigationTarget);
        actions.greetings().advance(pet, now + 400);
        assertTrue(body.getLocation().distance(navigationTarget) > 0.8, "Reaching a waypoint advances around the circle");
        Location reachable = navigationTarget.clone();
        reachableGreetingPath = false;
        actions.greetings().advance(pet, now + 600);
        assertEquals(reachable, navigationTarget);
        greetingGround = false;
        actions.greetings().advance(pet, now + 800);
        assertEquals(reachable, navigationTarget, "No path into unsupported ground");
    }

    @Test void sickAndHungryPetsRecognizeQuietlyWhileBusyAndSwimmingPetsDoNotGreet() {
        long now = System.currentTimeMillis();
        pet.illness(Illness.SICK); assertTrue(actions.greetings().trigger(pet, player, now));
        assertEquals(Activity.NONE, pet.activity()); assertNull(navigationTarget);
        assertTrue(greetingSounds.contains(org.bukkit.Sound.ENTITY_WOLF_WHINE));
        actions.clearInteractions(pet);
        pet.illness(Illness.NONE); pet.need(Need.HUNGER, 10);
        assertTrue(actions.greetings().trigger(pet, player, now)); assertEquals(Activity.NONE, pet.activity());
        actions.clearInteractions(pet);
        pet.need(Need.HUNGER, 100); pet.activity(Activity.ATTENDING);
        assertFalse(actions.greetings().trigger(pet, player, now));
        pet.activity(Activity.NONE); inWater = true;
        assertFalse(actions.greetings().trigger(pet, player, now));
        inWater = false; assertTrue(actions.greetings().trigger(pet, player, now));
        inWater = true; actions.greetings().advance(pet, now + 200);
        assertFalse(actions.greeting(pet)); assertEquals(Activity.NONE, pet.activity());
    }

    @Test void greetingMovesInFrontOfOwnerAndHopsTowardThemOnlyAfterLanding() {
        eagerGreeting();
        greetingGround = true;
        body.setOnGround(true);
        long now = System.currentTimeMillis();
        assertTrue(actions.greetings().trigger(pet, player, now));
        actions.greetings().advance(pet, now + 3400);
        assertEquals(player.getLocation().clone().add(0, 0, 1.8), navigationTarget);
        body.teleport(navigationTarget);
        actions.greetings().advance(pet, now + 3600);
        assertEquals(0.36, body.getVelocity().getY(), 0.0001);
        assertTrue(body.getVelocity().getZ() < 0, "The hop approaches the owner from the front");
        var lookGoal = org.bukkit.Bukkit.getMobGoals().getGoal(body, com.destroystokyo.paper.entity.ai.GoalKey.of(
                org.bukkit.entity.Mob.class, new NamespacedKey("companionpets", "look")));
        assertNotNull(lookGoal); lookGoal.tick();
        assertEquals(player.getEyeLocation(), lookedAt);
        assertNull(body.getTarget(), "The affectionate hop never attacks");
        body.setOnGround(false); body.setVelocity(new org.bukkit.util.Vector(0, -0.2, 0));
        actions.greetings().advance(pet, now + 4600);
        assertEquals(-0.2, body.getVelocity().getY(), 0.0001, "No repeated impulse in midair");
        body.setOnGround(true); body.setVelocity(new org.bukkit.util.Vector());
        actions.greetings().advance(pet, now + 4800);
        assertEquals(0.36, body.getVelocity().getY(), 0.0001);
        actions.greetings().advance(pet, now + 5600);
        assertCircleRadius(2);
    }

    @Test void catGreetingMeowsFrequentlyAndWalksSmallerCirclesWithoutDogHops() {
        greetingSpecies(org.bukkit.entity.EntityType.CAT); eagerGreeting();
        greetingGround = true; body.setOnGround(true);
        long now = System.currentTimeMillis();
        assertTrue(actions.greetings().trigger(pet, player, now));
        assertCircleRadius(1.4);
        assertEquals(0.8, navigationSpeed);
        for (int elapsed = 200; elapsed < 8000; elapsed += 200) {
            actions.greetings().advance(pet, now + elapsed);
            assertCircleRadius(1.4);
            assertEquals(0, body.getVelocity().getY(), "Cats never enter the dog's bounce phase");
        }
        assertTrue(greetingSounds.size() >= 8, "Repeated conversational meows throughout the welcome");
        assertTrue(greetingSounds.stream().allMatch(s -> s == org.bukkit.Sound.ENTITY_CAT_AMBIENT));
        assertTrue(greetingPitches.stream().allMatch(p -> p >= 0.95 && p <= 1.15));
        actions.greetings().advance(pet, now + 8000);
        int sounds = greetingSounds.size();
        actions.greetings().advance(pet, now + 10000);
        assertEquals(sounds, greetingSounds.size(), "Meowing stops with the greeting");
    }

    @Test void catPausesToLookAtOwnerThenContinuesAroundTheirCurrentPosition() {
        eagerGreeting();
        greetingSpecies(org.bukkit.entity.EntityType.CAT); greetingGround = true;
        long now = System.currentTimeMillis();
        assertTrue(actions.greetings().trigger(pet, player, now));
        body.teleport(navigationTarget); actions.greetings().advance(pet, now + 200);
        body.teleport(navigationTarget); int stops = navigationStops;
        actions.greetings().advance(pet, now + 400);
        assertTrue(navigationStops > stops);
        var look = org.bukkit.Bukkit.getMobGoals().getGoal(body, com.destroystokyo.paper.entity.ai.GoalKey.of(
                org.bukkit.entity.Mob.class, new NamespacedKey("companionpets", "look")));
        assertNotNull(look); look.tick(); assertEquals(player.getEyeLocation(), lookedAt);
        var paused = navigationTarget.clone();
        player.teleport(player.getLocation().add(2, 0, 0));
        actions.greetings().advance(pet, now + 600); assertEquals(paused, navigationTarget);
        actions.greetings().advance(pet, now + 1000);
        assertCircleRadius(1.4);
        assertEquals(0.8, navigationSpeed);
    }

    @Test void configuredGreetingVoiceAndBehaviorsAreIndependentOfBaseEntity() {
        testConfig.set("pets.wolf.sounds.preset", "frog"); greetingGround = true; body.setOnGround(true);
        testConfig.set("pets.wolf.behaviors", java.util.List.of("greeting", "greeting-approach"));
        runtime.config(CompanionConfig.load(runtime.plugin(), testConfig)); eagerGreeting();
        long now = System.currentTimeMillis();
        assertTrue(actions.greetings().trigger(pet, player, now));
        actions.greetings().advance(pet, now + 3400);
        assertEquals(1, navigationSpeed);
        assertEquals(1.8, navigationTarget.distance(player.getLocation()), 0.001);
        assertEquals(0, body.getVelocity().getY());
        assertTrue(greetingSounds.stream().allMatch(s -> s == org.bukkit.Sound.ENTITY_FROG_AMBIENT));
    }

    @Test void greetingSkipsHopsUnderLowCeilingsOrWithUnreachableLandingAndReactsToOwnerFacing() {
        eagerGreeting();
        greetingGround = true;
        body.setOnGround(true);
        long now = System.currentTimeMillis();
        assertTrue(actions.greetings().trigger(pet, player, now));
        var turned = player.getLocation(); turned.setYaw(90); player.teleport(turned);
        actions.greetings().advance(pet, now + 3400);
        assertEquals(-1.8, navigationTarget.getX(), 0.0001);
        assertEquals(0, navigationTarget.getZ(), 0.0001);
        body.teleport(navigationTarget); body.setVelocity(new org.bukkit.util.Vector());
        blockedHop = true;
        actions.greetings().advance(pet, now + 3600);
        assertEquals(0, body.getVelocity().getY());
        blockedHop = false; reachableGreetingPath = false;
        actions.greetings().advance(pet, now + 3800);
        assertEquals(0, body.getVelocity().getY());
        reachableGreetingPath = true;
        body.teleport(player.getLocation());
        actions.greetings().advance(pet, now + 4000);
        assertEquals(0.36, body.getVelocity().getY(), 0.0001);
        assertTrue(Double.isFinite(body.getVelocity().getX()) && Double.isFinite(body.getVelocity().getZ()));
    }

    @Test void offlineAbsenceTriggersOnReconnectButAnUnloadedNearbyPetHasNoFalseAbsence() {
        long now = System.currentTimeMillis();
        actions.greetings().tick(now);
        player.disconnect();
        actions.greetings().tick(now + 301_000);
        assertFalse(actions.greeting(pet)); assertEquals(now, pet.lastOwnerNearbyMillis());
        player.reconnect();
        actions.ownerSessionChanged(player);
        actions.greetings().tick(now + 302_000);
        assertTrue(actions.greeting(pet));
        actions.clearInteractions(pet);
        UUID original = pet.entityId(); pet.entityId(UUID.randomUUID());
        actions.greetings().tick(now + 303_000);
        assertEquals(now + 303_000, pet.lastOwnerNearbyMillis(), "Physical presence is tracked while the body is unloaded");
        pet.entityId(original);
    }

    @Test void anotherPlayerCanReadLearnedTricksAndReturnWithoutTrainingOrChangingThePet() {
        var other = MockBukkit.getMock().addPlayer();
        pet.progress(Trick.SIT, 100); pet.bindWord("sit", Trick.SIT);
        actions.menus().openCare(other, pet);
        var care = (net.tfminecraft.companionpets.gui.MenuHolder) other.getOpenInventory().getTopInventory().getHolder();
        actions.clickMenu(other, care, net.tfminecraft.companionpets.gui.PetMenus.TRICKS_SLOT, null, false, false, false);
        var learned = (net.tfminecraft.companionpets.gui.MenuHolder) other.getOpenInventory().getTopInventory().getHolder();
        assertEquals(net.tfminecraft.companionpets.gui.MenuHolder.Kind.LEARNED, learned.kind());
        assertEquals(pet.id(), learned.petId());
        actions.clickMenu(other, learned, 0, learned.getInventory().getItem(0), false, false, false);
        assertNull(runtime.sessions().training(other.getUniqueId()));
        assertEquals(100, pet.progress(Trick.SIT)); assertEquals(Trick.SIT, pet.trickFor("sit"));
        assertEquals(player.getUniqueId(), pet.ownerId()); assertFalse(pet.stored());
        actions.clickMenu(other, learned, net.tfminecraft.companionpets.gui.PetMenus.TRICKS_BACK_SLOT, null, false, false, false);
        var returned = (net.tfminecraft.companionpets.gui.MenuHolder) other.getOpenInventory().getTopInventory().getHolder();
        assertEquals(net.tfminecraft.companionpets.gui.MenuHolder.Kind.CARE, returned.kind());
        assertEquals(Material.BOOK, returned.getInventory().getItem(net.tfminecraft.companionpets.gui.PetMenus.TRICKS_SLOT).getType());
    }

    @Test void anotherPlayerCannotTrainStoreOrReleasePetEvenWithOwnerMenuOrForgedConfirmation() {
        var other = MockBukkit.getMock().addPlayer();
        other.teleport(player.getLocation());
        var holder = new net.tfminecraft.companionpets.gui.MenuHolder(net.tfminecraft.companionpets.gui.MenuHolder.Kind.CARE, pet.id(), null);
        actions.menus().openCare(other, pet);
        var inventory = other.getOpenInventory().getTopInventory();
        assertEquals(Material.BOOK, inventory.getItem(net.tfminecraft.companionpets.gui.PetMenus.TRICKS_SLOT).getType());
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, inventory.getItem(net.tfminecraft.companionpets.gui.PetMenus.STORE_SLOT).getType());
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, inventory.getItem(net.tfminecraft.companionpets.gui.PetMenus.RELEASE_SLOT).getType());
        actions.clickMenu(other, holder, net.tfminecraft.companionpets.gui.PetMenus.STORE_SLOT, null, false, false, false);
        actions.clickMenu(other, holder, net.tfminecraft.companionpets.gui.PetMenus.RELEASE_SLOT, null, false, false, false);
        assertNull(runtime.sessions().release(other.getUniqueId()));
        runtime.sessions().release(other.getUniqueId(), new net.tfminecraft.companionpets.session.ReleasePrompt(pet.id(), System.currentTimeMillis() + 30_000));
        actions.onChat(other, "yes");
        assertNull(runtime.sessions().release(other.getUniqueId()));
        assertFalse(pet.stored()); assertTrue(body.isValid()); assertEquals(0, bodyRemovals);
        assertSame(pet, runtime.store().get(pet.id())); assertFalse(runtime.store().isDeleted(pet.id()));
        other.getInventory().setItemInMainHand(new ItemStack(Material.COD));
        actions.useOnPet(other, body, other.getInventory().getItemInMainHand());
        assertNull(runtime.sessions().training(other.getUniqueId()));
        lookingAtPet = true;
        actions.onChat(other, "new word"); actions.bindTrick(other, pet, "new word", Trick.SIT);
        assertNull(pet.trickFor("new word")); assertNull(runtime.sessions().training(other.getUniqueId()));
        assertEquals(player.getUniqueId(), pet.ownerId());
    }

    @Test void ownershipChangeInvalidatesReleaseConfirmationAndRefreshHidesManagement() {
        actions.menus().openCare(player, pet);
        runtime.sessions().release(player.getUniqueId(), new net.tfminecraft.companionpets.session.ReleasePrompt(pet.id(), System.currentTimeMillis() + 30_000));
        pet.ownerId(UUID.randomUUID());
        actions.menus().refreshCare(player, pet);
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, player.getOpenInventory().getTopInventory().getItem(net.tfminecraft.companionpets.gui.PetMenus.RELEASE_SLOT).getType());
        actions.onChat(player, "yes");
        assertNull(runtime.sessions().release(player.getUniqueId()));
        assertSame(pet, runtime.store().get(pet.id())); assertTrue(body.isValid()); assertEquals(0, bodyRemovals);
    }

    @Test void restartingWithADistantFollowingPetKeepsItsSavedPositionAndBlocksNativeTeleport() {
        body.teleport(player.getLocation().add(40, 0, 0)); runtime.remember(pet, body);
        var at = body.getLocation().clone();
        var recovered = new PetRuntime(runtime.plugin(), runtime.config(), runtime.store(), new Sessions(),
                runtime.bodies(), runtime.visual(), runtime.petKey(), runtime.toyKey());
        var recoveredActions = new PetActions(recovered);
        recoveredActions.reattach(body);
        new PetTicker(recovered, recoveredActions).run();
        assertEquals(at, body.getLocation()); assertFalse(body.isAware());
        assertEquals(PetOrder.FOLLOW, pet.order(), "The saved order is retained while its session is paused");
        var event = new org.bukkit.event.entity.EntityTeleportEvent(body, at, player.getLocation());
        new net.tfminecraft.companionpets.listen.PetListener(recovered, recoveredActions).onTeleport(event);
        assertTrue(event.isCancelled());
        player.teleport(at.clone().add(1, 0, 0));
        new PetTicker(recovered, recoveredActions).run();
        assertTrue(body.isAware()); assertTrue(recovered.followingAllowed(pet, player));
    }

    @Test void reconnectDoesNotSummonADistantPetAndExplicitFollowResumesIt() {
        var listener = new net.tfminecraft.companionpets.listen.PetListener(runtime, actions);
        listener.onQuit(new org.bukkit.event.player.PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null));
        player.teleport(player.getLocation().add(40, 0, 0));
        listener.onJoin(new org.bukkit.event.player.PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
        var at = body.getLocation().clone();
        new PetTicker(runtime, actions).run();
        assertEquals(at, body.getLocation()); assertFalse(body.isAware());
        var event = new org.bukkit.event.entity.EntityTeleportEvent(body, at, player.getLocation());
        listener.onTeleport(event); assertTrue(event.isCancelled());
        player.teleport(at.clone().add(8, 0, 0));
        actions.onChat(player, "Toby follow");
        assertTrue(body.isAware()); assertTrue(runtime.followingAllowed(pet, player));
        player.teleport(at.clone().add(40, 0, 0));
        new PetTicker(runtime, actions).run();
        assertEquals(at, body.getLocation(), "Normal catching up is delegated to native AI");
        assertTrue(body.isAware());
        event = new org.bukkit.event.entity.EntityTeleportEvent(body, at, player.getLocation());
        listener.onTeleport(event); assertFalse(event.isCancelled(), "Native teleport is allowed after explicit Follow");
    }

    @Test void hungryWeakenedOrRestingPetStaysMobileAndFloatsInWaterWithoutChangingItsOrder() {
        inWater = true;
        for (PetOrder order : PetOrder.values()) {
            pet.order(order); pet.staying(order == PetOrder.STAY);
            pet.activity(order == PetOrder.LAY ? Activity.SLEEPING : Activity.NONE);
            pet.illness(Illness.WEAKENED); pet.need(Need.HEALTH, 0); pet.need(Need.ENERGY, 0); pet.need(Need.HUNGER, 0);
            body.setAware(false); body.setSitting(true); body.setVelocity(new org.bukkit.util.Vector(0, -0.3, 0));
            new PetTicker(runtime, actions).run();
            assertTrue(body.isAware()); assertFalse(body.isSitting()); assertTrue(body.getVelocity().getY() > 0);
            assertEquals(order, pet.order());
            var key = com.destroystokyo.paper.entity.ai.GoalKey.of(org.bukkit.entity.Mob.class,
                    new NamespacedKey(runtime.plugin(), "water_navigation"));
            var goal = org.bukkit.Bukkit.getMobGoals().getGoal(body, key);
            assertNotNull(goal); assertTrue(goal.shouldActivate());
            goal.tick(); assertTrue(body.getVelocity().getY() > 0);
            net.tfminecraft.companionpets.integration.PetMotion.hold(body);
            net.tfminecraft.companionpets.fx.PetFx.sit(body, true);
            net.tfminecraft.companionpets.fx.PetFx.lie(body, true);
            assertTrue(body.isAware()); assertFalse(body.isSitting()); assertTrue(body.getVelocity().getY() > 0);
            inWater = false;
            new PetTicker(runtime, actions).run();
            assertTrue(body.isAware(), "Sleeping pets keep native AI awake while sitting");
            assertEquals(order, pet.order()); assertFalse(goal.shouldActivate());
            inWater = true;
        }
    }

    @Test void followInWaterAndTemporaryComeBothRespectWaterSafety() {
        inWater = true; pet.order(PetOrder.LAY); pet.activity(Activity.SLEEPING); body.setAware(false);
        actions.onChat(player, "Toby follow");
        new PetTicker(runtime, actions).run();
        assertEquals(PetOrder.FOLLOW, pet.order()); assertTrue(body.isAware()); assertFalse(body.isSitting());
        pet.order(PetOrder.SIT); pet.bindWord("come", Trick.COME); pet.progress(Trick.COME, 100);
        actions.onChat(player, "Toby come"); assertEquals(Activity.ATTENDING, pet.activity());
        new PetTicker(runtime, actions).run();
        assertEquals(PetOrder.FOLLOW, pet.order(), "Come keeps moving toward its owner across water");
        assertEquals(Activity.ATTENDING, pet.activity());
        assertTrue(body.isAware()); assertFalse(body.isSitting());
        inWater = false; body.teleport(player.getLocation());
        new PetTicker(runtime, actions).run();
        assertEquals(PetOrder.SIT, pet.order(), "Come restores the original posture after arrival");
    }

    private ItemStack hold(Material material, int amount) {
        player.getInventory().setItemInMainHand(new ItemStack(material, amount));
        return player.getInventory().getItemInMainHand();
    }

    @Test void feedingConsumesOneItemAndUsesConfiguredGain() {
        pet.need(Need.HUNGER, 20);
        var hand = hold(Material.BEEF, 2);
        assertTrue(actions.useOnPet(player, body, hand));
        assertEquals(55, pet.need(Need.HUNGER));
        assertEquals(1, hand.getAmount());
    }

    @Test void creativeFeedingPreservesStack() {
        player.setGameMode(GameMode.CREATIVE);
        pet.need(Need.HUNGER, 20);
        var hand = hold(Material.BEEF, 2);
        assertTrue(actions.useOnPet(player, body, hand));
        assertEquals(55, pet.need(Need.HUNGER));
        assertEquals(2, hand.getAmount());
    }

    @Test void overfeedingConsumesFoodAndAppliesConfiguredPenalties() {
        pet.need(Need.HUNGER, 100);
        pet.need(Need.HEALTH, 100);
        pet.need(Need.MOOD, 100);
        var hand = hold(Material.BEEF, 2);
        assertTrue(actions.useOnPet(player, body, hand));
        assertEquals(100, pet.need(Need.HUNGER));
        assertEquals(100 - runtime.config().care().overfeedHealthPenalty(), pet.need(Need.HEALTH));
        assertEquals(100 - runtime.config().care().overfeedMoodPenalty(), pet.need(Need.MOOD));
        assertEquals(1, hand.getAmount());
    }

    @Test void eitherTreatFeedsAHungryPetWithoutStartingTraining() {
        for (var material : new Material[] {Material.COD, Material.SALMON}) {
            pet.need(Need.HUNGER, 20);
            var hand = hold(material, 2);
            assertTrue(actions.useOnPet(player, body, hand));
            assertEquals(50, pet.need(Need.HUNGER));
            assertEquals(1, hand.getAmount());
            assertNull(runtime.sessions().training(player.getUniqueId()));
        }
    }

    @Test void eitherMedicineTreatsSickPetsAndConsumesOneItem() {
        for (var material : new Material[] {Material.MILK_BUCKET, Material.HONEY_BOTTLE}) {
            pet.illness(Illness.SICK);
            pet.treated(false);
            pet.need(Need.HEALTH, 20);
            var hand = hold(material, 2);
            assertTrue(actions.useOnPet(player, body, hand));
            assertTrue(pet.treated());
            assertEquals(20 + runtime.config().care().medicineHealthBump(), pet.need(Need.HEALTH));
            assertEquals(1, hand.getAmount());
        }
    }

    @Test void healthyPetsDoNotConsumeMedicine() {
        var hand = hold(Material.MILK_BUCKET, 2);
        assertFalse(actions.useOnPet(player, body, hand));
        assertEquals(2, hand.getAmount());
        assertFalse(pet.treated());
    }

    @Test void brushingRestoresCleanlinessWithoutConsumingTheBrush() {
        pet.need(Need.CLEANLINESS, 1);
        var hand = hold(Material.FEATHER, 1);
        assertTrue(actions.useOnPet(player, body, hand));
        assertEquals(100, pet.need(Need.CLEANLINESS));
        assertEquals(1, hand.getAmount());
    }

    @Test void toyHintDoesNotConsumeTheToy() {
        var hand = hold(Material.STICK, 2);
        assertTrue(actions.useOnPet(player, body, hand));
        assertEquals(2, hand.getAmount());
        assertNull(pet.fetch());
    }

    @Test void storedPetDoesNotConsumeFoodOrChangeNeeds() {
        pet.stored(true);
        pet.need(Need.HUNGER, 20);
        var hand = hold(Material.BEEF, 2);
        assertFalse(actions.useOnPet(player, body, hand));
        assertEquals(20, pet.need(Need.HUNGER));
        assertEquals(2, hand.getAmount());
    }

    @Test void explicitEmptyPetListsDisableAllGlobalCareItems() {
        var selective = new Pet(UUID.randomUUID(), player.getUniqueId(), "selective", "Other", PetSex.FEMALE);
        runtime.store().add(selective);
        selective.stored(false);
        runtime.remember(selective, body);
        body.getPersistentDataContainer().set(runtime.petKey(), org.bukkit.persistence.PersistentDataType.STRING, selective.id().toString());
        selective.need(Need.HUNGER, 20);
        selective.need(Need.CLEANLINESS, 20);
        selective.need(Need.HEALTH, 20);
        selective.illness(Illness.SICK);
        for (var material : new Material[] {Material.BEEF, Material.COD, Material.MILK_BUCKET, Material.FEATHER, Material.STICK}) {
            var hand = hold(material, 2);
            assertFalse(actions.useOnPet(player, body, hand));
            assertEquals(2, hand.getAmount());
        }
        assertEquals(20, selective.need(Need.HUNGER));
        assertEquals(20, selective.need(Need.CLEANLINESS));
        assertEquals(20, selective.need(Need.HEALTH));
    }

    private void startHatch() {
        var hand = hold(Material.WOLF_SPAWN_EGG, 2);
        actions.useWorld(player, hand, null, null, false, true);
        assertNotNull(runtime.sessions().hatch(player.getUniqueId()));
        assertEquals(2, hand.getAmount());
    }

    @Test void confirmedReleaseDeletesVisualAndBodyAndRecordsDeletion() {
        assertTrue(runtime.store().load());
        net.tfminecraft.companionpets.training.DefaultTricks.apply(runtime.config(), pet); runtime.store().add(pet);
        runtime.sessions().release(player.getUniqueId(), new net.tfminecraft.companionpets.session.ReleasePrompt(pet.id(), System.currentTimeMillis() + 30_000));
        actions.onChat(player, "yes");
        assertEquals(1, bodyRemovals, "Release must use body deletion, which does not reveal the vanilla mob");
        assertFalse(body.isValid());
        assertNull(runtime.store().get(pet.id()));
        assertTrue(runtime.store().isDeleted(pet.id()));
        assertNull(runtime.sessions().release(player.getUniqueId()));
    }

    @Test void cancelledReleaseRetainsBodyAndRecord() {
        runtime.sessions().release(player.getUniqueId(), new net.tfminecraft.companionpets.session.ReleasePrompt(pet.id(), System.currentTimeMillis() + 30_000));
        actions.onChat(player, "no");
        assertEquals(0, bodyRemovals);
        assertTrue(body.isValid());
        assertSame(pet, runtime.store().get(pet.id()));
        assertFalse(runtime.store().isDeleted(pet.id()));
    }

    @Test void petHouseDeletesVisualAndBodyButPreservesLearningAndNeeds() {
        pet.progress(Trick.FOLLOW, 73); pet.bindWord("here", Trick.FOLLOW); pet.need(Need.HUNGER, 55);
        var holder = new net.tfminecraft.companionpets.gui.MenuHolder(net.tfminecraft.companionpets.gui.MenuHolder.Kind.CARE, pet.id(), null);
        actions.clickMenu(player, holder, net.tfminecraft.companionpets.gui.PetMenus.careSlot(
                net.tfminecraft.companionpets.gui.PetMenus.STORE_SLOT, false), null, false, false, false);
        assertEquals(1, bodyRemovals, "Pet House must not detach by revealing the vanilla mob");
        assertFalse(body.isValid());
        assertTrue(pet.stored()); assertNull(pet.entityId());
        assertEquals(55, pet.need(Need.HUNGER)); assertEquals(73, pet.progress(Trick.FOLLOW)); assertEquals(Trick.FOLLOW, pet.trickFor("here"));
        assertSame(pet, runtime.store().get(pet.id())); assertFalse(runtime.store().isDeleted(pet.id()));
    }

    @Test void defaultFollowResumesEveryPosture() {
        assertEquals(Trick.FOLLOW, pet.trickFor("follow")); assertEquals(100, pet.progress(Trick.FOLLOW));
        for (PetOrder order : new PetOrder[]{PetOrder.SIT, PetOrder.STAY, PetOrder.LAY}) {
            pet.order(order); pet.staying(true); pet.activity(Activity.SLEEPING); body.setSitting(true); body.setAware(false);
            actions.onChat(player, "Toby follow");
            assertEquals(PetOrder.FOLLOW, pet.order()); assertFalse(pet.staying()); assertEquals(Activity.NONE, pet.activity());
            assertFalse(body.isSitting()); assertTrue(body.isAware());
            assertEquals(100, pet.progress(Trick.FOLLOW), "Follow is a learned foundation");
        }
    }

    @Test void namedOrdersWorkInEitherOrderWithoutAiming() {
        pet.bindWord("sit down", Trick.SIT); pet.progress(Trick.SIT, 100);
        actions.onChat(player, "Toby, sit down!"); assertEquals(PetOrder.SIT, pet.order()); assertTrue(body.isSitting()); assertTrue(body.isAware());
        actions.onChat(player, "follow Toby"); assertEquals(PetOrder.FOLLOW, pet.order());
        actions.onChat(player, "sit down Toby"); assertEquals(PetOrder.SIT, pet.order());
        pet.bindWord("stay", Trick.STAY); pet.progress(Trick.STAY, 100);
        actions.onChat(player, "Toby stay"); assertEquals(PetOrder.STAY, pet.order()); assertFalse(body.isSitting()); assertFalse(body.isAware());
    }

    @Test void remoteOtherOwnerAndStoredPetsCannotHearNamedOrders() {
        pet.order(PetOrder.SIT);
        body.teleport(player.getLocation().add(13, 0, 0)); actions.onChat(player, "Toby follow"); assertEquals(PetOrder.SIT, pet.order());
        body.teleport(player.getLocation().add(1, 0, 0));
        pet.stored(true); actions.onChat(player, "Toby follow"); assertEquals(PetOrder.SIT, pet.order());
        pet.stored(false); pet.ownerId(UUID.randomUUID()); actions.onChat(player, "Toby follow"); assertEquals(PetOrder.SIT, pet.order());
    }

    @Test void stayRemainsStandingAndRejectsNativeTeleportsWhileFollowingAndComeCanMove() {
        var listener = new net.tfminecraft.companionpets.listen.PetListener(runtime, actions);
        pet.bindWord("stay", Trick.STAY); pet.progress(Trick.STAY, 100);
        actions.onChat(player, "Toby stay");
        assertEquals(PetOrder.STAY, pet.order()); assertFalse(body.isSitting());
        var at = body.getLocation().clone();
        player.teleport(player.getLocation().add(100, 0, 0));
        new PetTicker(runtime, actions).run();
        assertEquals(at, body.getLocation()); assertFalse(body.isSitting()); assertFalse(body.isAware());
        for (PetOrder order : new PetOrder[]{PetOrder.STAY, PetOrder.SIT, PetOrder.LAY}) {
            pet.order(order); pet.staying(order == PetOrder.STAY);
            var event = new org.bukkit.event.entity.EntityTeleportEvent(body, at, player.getLocation());
            listener.onTeleport(event); assertTrue(event.isCancelled(), order + " blocks native teleports");
        }
        pet.order(PetOrder.FOLLOW); pet.staying(false); pet.activity(Activity.ATTENDING);
        var event = new org.bukkit.event.entity.EntityTeleportEvent(body, at, player.getLocation());
        listener.onTeleport(event); assertFalse(event.isCancelled(), "Come temporarily uses Follow and may reach its owner");
        var ordinary = new org.bukkit.event.entity.EntityTeleportEvent(player, player.getLocation(), at);
        listener.onTeleport(ordinary); assertFalse(ordinary.isCancelled());
    }

    @Test void ambiguousPetNamesDoNotExecuteCommandsOnEitherPet() {
        pet.order(PetOrder.SIT);
        var other = new Pet(UUID.randomUUID(), player.getUniqueId(), "wolf", "Toby", PetSex.FEMALE);
        other.order(PetOrder.SIT); runtime.store().add(other);
        var otherBody = body.getWorld().spawn(player.getLocation().add(2, 0, 0), org.bukkit.entity.Wolf.class); runtime.remember(other, otherBody);
        actions.onChat(player, "Toby follow");
        assertEquals(PetOrder.SIT, pet.order()); assertEquals(PetOrder.SIT, other.order());
        assertTrue(player.nextMessage().contains("More than one"));
    }

    @Test void hearingANameHoldsAttentionButDoesNotCancelAnExplicitPosture() {
        actions.onChat(player, "Toby"); assertEquals(Activity.ATTENDING, pet.activity());
        pet.order(PetOrder.STAY); pet.staying(true); pet.activity(Activity.NONE);
        actions.onChat(player, "Toby"); assertEquals(PetOrder.STAY, pet.order()); assertTrue(pet.staying()); assertEquals(Activity.NONE, pet.activity());
    }

    @Test void attentionWaitStartsAfterArrivalAndPreventsWanderingForTenSeconds() {
        long now = System.currentTimeMillis();
        actions.roaming().attend(pet, player, now);
        assertTrue(actions.roaming().tickAttention(pet, body, now + 29_000));
        assertFalse(body.isAware()); assertTrue(navigationStops > 0);
        assertTrue(actions.roaming().tickAttention(pet, body, now + 38_999));
        assertFalse(actions.roaming().tickAttention(pet, body, now + 39_000));
        assertEquals(Activity.NONE, pet.activity());
    }

    @Test void nameCallTracksCurrentOwnerAndEveryPostureCancelsItsRouteAndHorizontalVelocity() {
        for (Trick posture : new Trick[]{Trick.SIT, Trick.STAY, Trick.LAY}) {
            pet.bindWord(posture.name().toLowerCase(), posture); pet.progress(posture, 100);
            actions.onChat(player, "Toby follow");
            body.teleport(player.getLocation().add(8, 0, 0));
            actions.onChat(player, "Toby");
            long now = System.currentTimeMillis();
            assertTrue(actions.roaming().tickAttention(pet, body, now));
            assertEquals(player.getLocation(), navigationTarget);
            player.teleport(player.getLocation().add(1, 0, 1));
            assertTrue(actions.roaming().tickAttention(pet, body, now + 1));
            assertEquals(player.getLocation(), navigationTarget, "A name call uses the owner's current position");
            body.setVelocity(new org.bukkit.util.Vector(0.4, -0.2, 0.3));
            actions.onChat(player, "Toby " + posture.name().toLowerCase());
            assertEquals(posture.name(), pet.order().name()); assertEquals(posture != Trick.STAY, body.isAware());
            assertEquals(0, body.getVelocity().getX()); assertEquals(0, body.getVelocity().getZ());
            assertEquals(-0.2, body.getVelocity().getY(), "Holding keeps gravity");
            assertFalse(actions.roaming().tickAttention(pet, body, now + 2), "The previous call must be cancelled");
            var goal = org.bukkit.Bukkit.getMobGoals().getGoal(body,
                    com.destroystokyo.paper.entity.ai.GoalKey.of(org.bukkit.entity.Mob.class,
                            new NamespacedKey(runtime.plugin(), "call_navigation")));
            assertNotNull(goal); assertFalse(goal.shouldActivate(), "The call cannot take over a posture");
        }
    }

    @Test void comeGetsUpWalksToCurrentOwnerAndRestoresEachPostureOnlyAfterArrival() {
        pet.bindWord("come", Trick.COME); pet.progress(Trick.COME, 100);
        for (PetOrder previous : PetOrder.values()) {
            actions.onChat(player, "Toby follow");
            if (previous != PetOrder.FOLLOW) {
                Trick posture = Trick.valueOf(previous.name()); pet.bindWord(previous.name().toLowerCase(), posture); pet.progress(posture, 100);
                actions.onChat(player, "Toby " + previous.name().toLowerCase());
            }
            body.teleport(player.getLocation().add(8, 0, 0));
            actions.onChat(player, "come Toby");
            assertEquals(PetOrder.FOLLOW, pet.order()); assertEquals(Activity.ATTENDING, pet.activity());
            assertTrue(body.isAware()); assertFalse(body.isSitting());
            long now = System.currentTimeMillis();
            assertTrue(actions.roaming().tickAttention(pet, body, now)); assertEquals(player.getLocation(), navigationTarget);
            player.teleport(player.getLocation().add(1, 0, 1));
            assertTrue(actions.roaming().tickAttention(pet, body, now + 1)); assertEquals(player.getLocation(), navigationTarget);
            assertEquals(PetOrder.FOLLOW, pet.order(), "The old posture must not return while walking");
            body.teleport(player.getLocation().add(1, 0, 0)); body.setVelocity(new org.bukkit.util.Vector(0.3, 0, 0.2));
            assertTrue(actions.roaming().tickAttention(pet, body, now + 2));
            assertEquals(previous, pet.order()); assertEquals(previous == PetOrder.STAY, pet.staying());
            assertEquals(Activity.NONE, pet.activity());
            assertEquals(previous != PetOrder.STAY, body.isAware());
            assertEquals(previous == PetOrder.SIT || previous == PetOrder.LAY, body.isSitting());
            assertEquals(0, body.getVelocity().getX()); assertEquals(0, body.getVelocity().getZ());
            assertFalse(actions.roaming().tickAttention(pet, body, now + 3));
        }
    }

    @Test void repeatedComeKeepsItsOriginalPostureAndNewOrdersCancelItsReturn() {
        pet.bindWord("come", Trick.COME); pet.progress(Trick.COME, 100); pet.bindWord("lay", Trick.LAY); pet.progress(Trick.LAY, 100);
        actions.onChat(player, "Toby lay"); body.teleport(player.getLocation().add(8, 0, 0));
        actions.onChat(player, "Toby come"); actions.onChat(player, "Toby come"); actions.onChat(player, "Toby");
        body.teleport(player.getLocation().add(1, 0, 0));
        assertTrue(actions.roaming().tickAttention(pet, body, System.currentTimeMillis())); assertEquals(PetOrder.LAY, pet.order());
        actions.onChat(player, "Toby come"); actions.onChat(player, "Toby follow");
        assertFalse(actions.roaming().tickAttention(pet, body, System.currentTimeMillis()));
        assertEquals(PetOrder.FOLLOW, pet.order()); assertTrue(body.isAware());
    }

    @Test void sittingLyingAndSleepingStayAwakeWithNativeLookingWithoutWalking() {
        for (Trick posture : new Trick[]{Trick.SIT, Trick.LAY}) {
            pet.bindWord(posture.name().toLowerCase(), posture); pet.progress(posture, 100);
            actions.onChat(player, "Toby " + posture.name().toLowerCase());
            assertEquals(Activity.NONE, pet.activity()); assertTrue(body.isAware()); assertTrue(body.isSitting());
            var hold = org.bukkit.Bukkit.getMobGoals().getGoal(body, com.destroystokyo.paper.entity.ai.GoalKey.of(
                    org.bukkit.entity.Mob.class, new NamespacedKey(runtime.plugin(), "posture_navigation")));
            assertTrue(hold.shouldActivate());
            assertFalse(hold.getTypes().contains(com.destroystokyo.paper.entity.ai.GoalType.LOOK));
            var position = body.getLocation();
            body.setVelocity(new org.bukkit.util.Vector(0.3, -0.2, 0.4)); hold.tick();
            var sleepingLook = org.bukkit.Bukkit.getMobGoals().getGoal(body, com.destroystokyo.paper.entity.ai.GoalKey.of(
                    org.bukkit.entity.Mob.class, new NamespacedKey(runtime.plugin(), "sleeping_look")));
            assertNotNull(sleepingLook); assertFalse(sleepingLook.shouldActivate(), "Awake postures look around natively");
            assertEquals(position, body.getLocation());
            assertEquals(0, body.getVelocity().getX()); assertEquals(0, body.getVelocity().getZ());
            assertFalse(body.getWorld().getEntities().stream().anyMatch(e ->
                    e.customName() != null && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                            .serialize(e.customName()).equals("Sleeping")));
            inWater = true; assertFalse(hold.shouldActivate()); inWater = false;
            pet.activity(Activity.SLEEPING); assertTrue(hold.shouldActivate());
            assertTrue(sleepingLook.shouldActivate(), "A sleeping head ignores native look goals");
            assertTrue(sleepingLook.getTypes().contains(com.destroystokyo.paper.entity.ai.GoalType.LOOK));
            pet.activity(Activity.NONE);
            actions.onChat(player, "Toby follow"); assertFalse(hold.shouldActivate());
        }
    }

    @Test void expiredComeAndReloadRestoreTheHeldPostureInsteadOfLeavingThePetFollowing() {
        pet.bindWord("come", Trick.COME); pet.progress(Trick.COME, 100); pet.bindWord("stay", Trick.STAY); pet.progress(Trick.STAY, 100);
        actions.onChat(player, "Toby stay"); body.teleport(player.getLocation().add(8, 0, 0));
        long now = System.currentTimeMillis(); actions.onChat(player, "Toby come");
        assertTrue(actions.roaming().tickAttention(pet, body, now + 31_000));
        assertEquals(PetOrder.STAY, pet.order()); assertFalse(body.isAware());
        actions.onChat(player, "Toby come"); actions.clearInteractions();
        assertEquals(PetOrder.STAY, pet.order()); assertFalse(body.isAware());
    }

    @Test void profileHasNoDedicatedFollowButton() {
        actions.menus().openCare(player, pet);
        var holder = (net.tfminecraft.companionpets.gui.MenuHolder) player.getOpenInventory().getTopInventory().getHolder();
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, holder.getInventory().getItem(40).getType());
        pet.order(PetOrder.SIT); actions.clickMenu(player, holder, 40, null, false, false, false);
        assertEquals(PetOrder.SIT, pet.order());
    }

    @SuppressWarnings("deprecation")
    private org.bukkit.event.player.AsyncPlayerChatEvent chatEvent(String text) {
        return new org.bukkit.event.player.AsyncPlayerChatEvent(false, player, text, new java.util.HashSet<>(org.bukkit.Bukkit.getOnlinePlayers()));
    }

    @SuppressWarnings("deprecation")
    public static class PublicChatProbe implements org.bukkit.event.Listener {
        private final PlayerMock observer;
        PublicChatProbe(PlayerMock observer) { this.observer = observer; }
        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
        public void onChat(org.bukkit.event.player.AsyncPlayerChatEvent event) { observer.sendMessage(event.getMessage()); }
    }

    private PlayerMock registerChatListeners() {
        var server = (org.mockbukkit.mockbukkit.ServerMock) org.bukkit.Bukkit.getServer();
        PlayerMock observer = server.addPlayer();
        var plugin = runtime.plugin();
        server.getPluginManager().registerEvents(new net.tfminecraft.companionpets.listen.PetListener(runtime, actions), plugin);
        server.getPluginManager().registerEvents(new PublicChatProbe(observer), plugin);
        return observer;
    }

    @Test void privateReleaseConfirmationIsConsumedBeforeRoleplayBroadcast() {
        assertTrue(runtime.store().load()); net.tfminecraft.companionpets.training.DefaultTricks.apply(runtime.config(), pet); runtime.store().add(pet);
        PlayerMock observer = registerChatListeners();
        runtime.sessions().release(player.getUniqueId(), new net.tfminecraft.companionpets.session.ReleasePrompt(pet.id(), System.currentTimeMillis() + 30_000));
        var event = chatEvent("yes"); org.bukkit.Bukkit.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled()); assertNull(observer.nextMessage());
        ((org.mockbukkit.mockbukkit.ServerMock) org.bukkit.Bukkit.getServer()).getScheduler().performTicks(1);
        assertNull(runtime.store().get(pet.id())); assertTrue(player.nextMessage().contains("is gone")); assertNull(observer.nextMessage());
    }

    @Test void eggAndRenameInputsStayPrivateAndOrdinaryChatStillBroadcasts() {
        PlayerMock observer = registerChatListeners();
        runtime.sessions().hatch(player.getUniqueId(), new net.tfminecraft.companionpets.session.HatchPrompt("wolf", System.currentTimeMillis() + 30_000));
        var hatch = chatEvent("female"); org.bukkit.Bukkit.getPluginManager().callEvent(hatch); assertTrue(hatch.isCancelled());
        runtime.sessions().clearHatch(player.getUniqueId());
        runtime.sessions().rename(player.getUniqueId(), new net.tfminecraft.companionpets.session.RenamePrompt(pet.id(), System.currentTimeMillis() + 30_000));
        var rename = chatEvent("Secret name"); org.bukkit.Bukkit.getPluginManager().callEvent(rename); assertTrue(rename.isCancelled());
        assertNull(observer.nextMessage());
        ((org.mockbukkit.mockbukkit.ServerMock) org.bukkit.Bukkit.getServer()).getScheduler().performTicks(1);
        assertEquals("Secret name", runtime.sessions().rename(player.getUniqueId()).name()); assertNull(observer.nextMessage());
        runtime.sessions().clearRename(player.getUniqueId());
        var normal = chatEvent("Good morning"); org.bukkit.Bukkit.getPluginManager().callEvent(normal); assertFalse(normal.isCancelled()); assertEquals("Good morning", observer.nextMessage());
    }

    @Test void queuedConfirmationCannotConfirmAReplacementPrompt() {
        registerChatListeners();
        runtime.sessions().release(player.getUniqueId(), new net.tfminecraft.companionpets.session.ReleasePrompt(pet.id(), System.currentTimeMillis() + 30_000));
        var event = chatEvent("yes"); org.bukkit.Bukkit.getPluginManager().callEvent(event); assertTrue(event.isCancelled());
        var replacement = new net.tfminecraft.companionpets.session.ReleasePrompt(pet.id(), System.currentTimeMillis() + 30_000);
        runtime.sessions().release(player.getUniqueId(), replacement);
        ((org.mockbukkit.mockbukkit.ServerMock) org.bukkit.Bukkit.getServer()).getScheduler().performTicks(1);
        assertSame(replacement, runtime.sessions().release(player.getUniqueId())); assertSame(pet, runtime.store().get(pet.id())); assertTrue(body.isValid());
    }

    private void confirmHatch() {
        actions.onChat(player, "female");
        actions.onChat(player, " Luna ");
        actions.onChat(player, "yes");
    }

    @Test void hatchConsumesOnlyAfterConfirmationAndCreatesConfiguredPet() {
        startHatch();
        confirmHatch();
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
        var hatched = runtime.store().all().stream().filter(p -> !p.id().equals(pet.id())).findFirst().orElseThrow();
        assertEquals("wolf", hatched.typeId());
        assertEquals("Luna", hatched.name());
        assertEquals(PetSex.FEMALE, hatched.sex());
        assertEquals(player.getUniqueId(), hatched.ownerId());
        assertNotNull(runtime.entity(hatched));
        assertNull(runtime.sessions().hatch(player.getUniqueId()));
    }

    @Test void cancellingHatchKeepsEggAndCreatesNothing() {
        startHatch();
        actions.onChat(player, "cancel");
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertEquals(1, runtime.store().all().size());
        assertNull(runtime.sessions().hatch(player.getUniqueId()));
    }

    @Test void removingEggBeforeConfirmationCreatesNothing() {
        startHatch();
        hold(Material.STONE, 2);
        confirmHatch();
        assertEquals(1, runtime.store().all().size());
        assertEquals(Material.STONE, player.getInventory().getItemInMainHand().getType());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertNull(runtime.sessions().hatch(player.getUniqueId()));
    }

    @Test void reachingPetLimitDuringPromptKeepsEggAtConfirmation() {
        startHatch();
        assertTrue(actions.spawnTestPet(player, "wolf", "Second"));
        confirmHatch();
        assertEquals(2, runtime.store().all().size());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertNull(runtime.sessions().hatch(player.getUniqueId()));
    }

    @Test void fullPetLimitDoesNotStartAHatchPrompt() {
        assertTrue(actions.spawnTestPet(player, "wolf", "Second"));
        var hand = hold(Material.WOLF_SPAWN_EGG, 2);
        actions.useWorld(player, hand, null, null, false, true);
        assertNull(runtime.sessions().hatch(player.getUniqueId()));
        assertEquals(2, hand.getAmount());
        assertEquals(2, runtime.store().all().size());
    }

    @Test void expiredHatchPromptKeepsEggAndCreatesNothing() {
        hold(Material.WOLF_SPAWN_EGG, 2);
        runtime.sessions().hatch(player.getUniqueId(), new net.tfminecraft.companionpets.session.HatchPrompt("wolf", 0));
        actions.onChat(player, "yes");
        assertNull(runtime.sessions().hatch(player.getUniqueId()));
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertEquals(1, runtime.store().all().size());
    }
}
