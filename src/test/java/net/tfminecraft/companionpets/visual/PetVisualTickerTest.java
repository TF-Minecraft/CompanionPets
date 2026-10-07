package net.tfminecraft.companionpets.visual;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.*;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.testutil.GoalServerMock;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.entity.CatMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import com.destroystokyo.paper.entity.Pathfinder;

class PetVisualTickerTest {
    GoalServerMock server; PetRuntime runtime; PetStore store; Pet pet; ShakingWolf body;
    RecordingPlayer animations=new RecordingPlayer();
    Map<PetAnimation, PetAppearance.Clip> modelClips=new EnumMap<>(PetAnimation.class);
    List<PetAnimation> poses=new ArrayList<>();
    List<Double> shakeSamples=new ArrayList<>(); Set<UUID> retained=Set.of();
    int splashes; boolean hold, attached=true; double wagHz;
    @BeforeEach void start() throws Exception {
        server=MockBukkit.mock(new GoalServerMock());
        var world=new WorldMock() {
            @Override public void spawnParticle(Particle particle,Location at,int count,double x,double y,double z,double speed) {
                assertEquals(Particle.SPLASH,particle); assertEquals(24,count); splashes++;
            }
        };
        server.addWorld(world); var owner=server.addPlayer(); var plugin=MockBukkit.createMockPlugin();
        var yaml=new YamlConfiguration(); yaml.loadFromString("""
            pets:
              dog:
                species: dog
                egg: EGG
                appearance: {type: modelengine, model: beagle}
            """);
        modelClips.put(PetAnimation.SHAKE, new PetAppearance.Clip("shake", 1, .15));
        var controller=new AnimationController(animations, modelClips);
        var visual=new PetVisual() {
            @Override public void apply(Entity body,PetTypeDef type) { }
            @Override public void update(Entity body,PetTypeDef type,PetAnimation pose) { poses.add(pose); controller.update(pose); }
            @Override public void cancelAction(Entity body) { controller.cancelAction(); }
            @Override public Set<String> clips(PetTypeDef type) {
                return modelClips.values().stream().map(PetAppearance.Clip::name).collect(java.util.stream.Collectors.toSet());
            }
            @Override public boolean shake(Entity body,PetTypeDef type,double progress) {
                shakeSamples.add(progress); return controller.shake(progress);
            }
            @Override public void retain(Set<UUID> loaded) { retained=Set.copyOf(loaded); }
            @Override public boolean modelAvailable(PetTypeDef type) { return true; }
            @Override public boolean hasTail(PetTypeDef type) { return true; }
            @Override public void wagTail(Entity body,double hz) { wagHz=hz; }
            @Override public boolean attached(Entity body) { return attached; }
            @Override public boolean holdsMovement(Entity body) { return hold || controller.holdsMovement(); }
        };
        var key=new NamespacedKey(plugin,"pet"); store=new PetStore(plugin); assertTrue(store.load());
        runtime=new PetRuntime(plugin,CompanionConfig.load(plugin,yaml),store,new Sessions(),new Bodies(plugin,key,visual),visual,key,new NamespacedKey(plugin,"toy"));
        pet=new Pet(UUID.randomUUID(),owner.getUniqueId(),"dog","Toby",PetSex.MALE);
        pet.stored(false); store.add(pet); body=new ShakingWolf(server);
        body.teleport(new Location(world,0,64,0)); server.registerEntity(body); runtime.remember(pet,body);
        assertTrue(runtime.config().type("dog").appearance().modeled());
    }
    @AfterEach void stop() { try { if(store!=null) store.close(); } finally { MockBukkit.unmock(); } }
    @Test void nativeShakeStartsOneAnimationAndRendersWaterWhileTheBodyIsHidden() {
        modelClips.put(PetAnimation.SHAKE, new PetAppearance.Clip("shake", 1.6, .3));
        var ticker=new PetVisualTicker(runtime); body.clock.progress=.05F;
        ticker.run(); body.clock.progress=.6F; ticker.run();
        assertEquals(List.of("shake"),animations.played); assertEquals(2,splashes);
        assertEquals(List.of((double).05F,(double).6F),shakeSamples);
        assertEquals(modelClips.get(PetAnimation.SHAKE),animations.lastClip);
        assertFalse(animations.looped);
        verify(body.path,never()).stopPathfinding();
        body.clock.progress=0; ticker.run();
        assertEquals(3,splashes); assertTrue(animations.active.contains("shake"));
        assertEquals(List.of("shake"),animations.played);
        animations.active.remove("shake"); ticker.run();
        assertEquals(3,splashes); assertFalse(animations.active.contains("shake"));
        body.clock.progress=.05F; ticker.run();
        assertEquals(List.of("shake","shake"),animations.played); assertEquals(4,splashes);
        int samples=shakeSamples.size();
        pet.stored(true); ticker.run();
        assertEquals(4,splashes); assertEquals(samples,shakeSamples.size()); assertTrue(retained.isEmpty());
        animations.active.remove("shake");
        pet.stored(false); body.clock.progress=0; ticker.run();
        assertEquals(4,splashes);
        body.clock.progress=.05F; ticker.run();
        assertEquals(3,animations.played.size()); assertEquals(5,splashes);
        assertEquals(Set.of(body.getUniqueId()),retained);
    }
    @Test void lateNativeShakeSkipsTheCycleUntilANewShakeStarts() {
        var ticker=new PetVisualTicker(runtime);
        body.clock.progress=.7F; ticker.run(); body.clock.progress=1.4F; ticker.run();
        assertEquals(List.of((double).7F,(double)1.4F),shakeSamples);
        assertTrue(animations.played.isEmpty()); assertEquals(0,splashes);
        body.clock.progress=0; ticker.run(); body.clock.progress=.2F; ticker.run();
        assertEquals(List.of("shake"),animations.played); assertEquals(1,splashes);
        assertEquals(modelClips.get(PetAnimation.SHAKE),animations.lastClip);
        body.clock.progress=.8F; ticker.run();
        assertEquals(List.of("shake"),animations.played); assertEquals(2,splashes);
        animations.active.remove("shake"); ticker.run();
        assertEquals(2,splashes); assertFalse(animations.active.contains("shake"));
        body.clock.progress=1.4F; ticker.run();
        assertEquals(List.of("shake"),animations.played); assertEquals(2,splashes);
    }
    @Test void nativeProgressDoesNotCreateWaterUntilTheModelIsAttached() {
        var ticker=new PetVisualTicker(runtime); attached=false; body.clock.progress=.05F;
        ticker.run(); assertEquals(List.of("shake"),animations.played); assertEquals(0,splashes);
        attached=true; body.clock.progress=.8F; ticker.run();
        assertEquals(List.of("shake"),animations.played); assertEquals(1,splashes);
    }
    @Test void aMovementHoldingOverlayStopsTheNativePath() {
        hold=true; var ticker=new PetVisualTicker(runtime); ticker.run();
        verify(body.path).stopPathfinding(); assertEquals(0,body.getVelocity().lengthSquared());
        assertTrue(animations.played.isEmpty());
    }
    @Test void greetingStartsTailMotionOnceItsStandingPoseHasBlended() {
        pet.activity(Activity.GREETING); pet.lastGreetingMillis(System.currentTimeMillis()-1000);
        pet.bond(100); for(Need need:Need.values()) pet.need(need,100);
        new PetVisualTicker(runtime).run(); assertTrue(wagHz>1);
        assertTrue(animations.played.isEmpty());
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void modeledFetchStalkUsesCrouchOnlyWhenItsBlueprintProvidesTheClip(boolean crouchAvailable) throws Exception {
        catProfile(false);
        if (crouchAvailable) modelClips.put(PetAnimation.CROUCH, new PetAppearance.Clip("crouch", 1, .15));
        var item = body.getWorld().dropItem(body.getLocation().add(1.5, 0, 0), new org.bukkit.inventory.ItemStack(Material.STICK));
        var job = new net.tfminecraft.companionpets.play.FetchJob("STICK", pet.ownerId());
        job.phase(net.tfminecraft.companionpets.play.FetchPhase.GROUND); job.itemId(item.getUniqueId());
        job.stalk(pet.id(), System.currentTimeMillis());
        pet.fetch(job); pet.activity(Activity.PLAYING);
        var ticker = new PetVisualTicker(runtime);

        ticker.run(); ticker.run();

        assertEquals(crouchAvailable ? PetAnimation.CROUCH : PetAnimation.IDLE, poses.getLast());
        assertEquals(crouchAvailable ? Set.of("crouch") : Set.of(), animations.active);
        assertEquals(crouchAvailable ? List.of("crouch") : List.of(), animations.played);
        if (crouchAvailable) assertTrue(animations.looped);
        assertTrue(job.stalking(pet.id())); assertEquals(item.getUniqueId(), job.itemId());
        assertTrue(item.isValid()); assertEquals(0, splashes);
        assertEquals(Set.of(body.getUniqueId()), retained);
        verify(body.path, never()).stopPathfinding();

        job.stopStalk(pet.id(), System.currentTimeMillis());
        ticker.run();
        assertEquals(PetAnimation.IDLE, poses.getLast());
        assertTrue(animations.active.isEmpty(), "The crouch overlay must stop when stalking ends");
        assertSame(job, pet.fetch()); assertTrue(item.isValid());
    }

    @Test void modeledNativeCatCrouchDefersToItsSittingPostureAndStopsWhenStanding() throws Exception {
        catProfile(true);
        modelClips.put(PetAnimation.CROUCH, new PetAppearance.Clip("crouch", 1, .15));
        modelClips.put(PetAnimation.SIT, new PetAppearance.Clip("sit", 1, .15));
        var cat = new CatMock(server, UUID.randomUUID()) {
            @Override public boolean isInWater() { return false; }
        };
        server.registerEntity(cat); cat.teleport(body.getLocation()); body.remove();
        runtime.remember(pet, cat); cat.setSneaking(true);
        var ticker = new PetVisualTicker(runtime);
        ticker.run();
        assertEquals(PetAnimation.CROUCH, poses.getLast());
        assertEquals(Set.of("crouch"), animations.active);
        cat.setSitting(true); ticker.run();
        assertEquals(PetAnimation.SIT, poses.getLast()); assertEquals(Set.of("sit"), animations.active);
        cat.setSitting(false); cat.setSneaking(false); ticker.run();
        assertEquals(PetAnimation.IDLE, poses.getLast()); assertTrue(animations.active.isEmpty());
        assertEquals(List.of("crouch", "sit"), animations.played);
        assertEquals(Set.of(cat.getUniqueId()), retained); assertEquals(0, splashes);
    }

    private void catProfile(boolean nativeCat) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
                pets:
                  dog:
                    entity: %s
                    behavior: cat
                    egg: EGG
                    model: beagle
                """.formatted(nativeCat ? "CAT" : "WOLF"));
        runtime.config(CompanionConfig.load(runtime.plugin(), yaml));
        assertEquals(BehaviorProfile.CAT, runtime.config().type("dog").behavior());
    }

    private static final class RecordingPlayer implements AnimationPlayer {
        final List<String> played=new ArrayList<>(); final Set<String> active=new HashSet<>();
        PetAppearance.Clip lastClip; boolean looped;
        @Override public boolean play(PetAppearance.Clip clip,boolean loop) {
            played.add(clip.name()); active.add(clip.name()); lastClip=clip; looped=loop; return true;
        }
        @Override public void stop(String clip) { active.remove(clip); }
        @Override public boolean playing(String clip) { return active.contains(clip); }
        @Override public double length(String clip) { return 1.04; }
    }
    public static class ShakingWolf extends WolfMock {
        final Clock clock=new Clock(); final Pathfinder path=mock(Pathfinder.class);
        ShakingWolf(ServerMock server) { super(server,UUID.randomUUID()); }
        public Clock getHandle() { return clock; }
        @Override public boolean isInWater() { return false; }
        @Override public double getWidth() { return .6; }
        @Override public double getHeight() { return .85; }
        @Override public Pathfinder getPathfinder() { return path; }
    }
    public static class Clock { float progress; public float getShakeAnim(float partial) { return progress; } }
}
