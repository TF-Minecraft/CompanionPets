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
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import com.destroystokyo.paper.entity.Pathfinder;

class PetVisualTickerTest {
    GoalServerMock server; PetRuntime runtime; PetStore store; Pet pet; ShakingWolf body;
    List<String> animations=new ArrayList<>(); int splashes; boolean hold; double wagHz;
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
        var visual=new PetVisual() {
            @Override public void apply(Entity body,PetTypeDef type) { }
            @Override public boolean play(Entity body,PetTypeDef type,String action) { animations.add(action); return true; }
            @Override public boolean modelAvailable(PetTypeDef type) { return true; }
            @Override public boolean hasTail(PetTypeDef type) { return true; }
            @Override public void wagTail(Entity body,double hz) { wagHz=hz; }
            @Override public boolean attached(Entity body) { return true; }
            @Override public boolean holdsMovement(Entity body) { return hold; }
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
        var ticker=new PetVisualTicker(runtime); body.clock.progress=1;
        ticker.run(); ticker.run(); assertEquals(List.of("SHAKE"),animations); assertEquals(2,splashes);
        body.clock.progress=0; ticker.run(); assertEquals(2,splashes);
        body.clock.progress=1; ticker.run(); assertEquals(List.of("SHAKE","SHAKE"),animations);
        assertEquals(3,splashes);
        pet.stored(true); ticker.run(); assertEquals(3,splashes);
        pet.stored(false); ticker.run(); assertEquals(3,animations.size(),"Stored bodies lose their old shake state");
    }
    @Test void aMovementHoldingOverlayStopsTheNativePath() {
        hold=true; var ticker=new PetVisualTicker(runtime); ticker.run();
        verify(body.path).stopPathfinding(); assertEquals(0,body.getVelocity().lengthSquared());
        assertTrue(animations.isEmpty());
    }
    @Test void greetingStartsTailMotionOnceItsStandingPoseHasBlended() {
        pet.activity(Activity.GREETING); pet.lastGreetingMillis(System.currentTimeMillis()-1000);
        pet.bond(100); for(Need need:Need.values()) pet.need(need,100);
        new PetVisualTicker(runtime).run(); assertTrue(wagHz>1);
        assertTrue(animations.isEmpty());
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
