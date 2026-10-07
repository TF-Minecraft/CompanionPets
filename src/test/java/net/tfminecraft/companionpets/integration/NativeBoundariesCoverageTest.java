package net.tfminecraft.companionpets.integration;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.bukkit.Location;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.WolfMock;

class NativeBoundariesCoverageTest {
    ServerMock server;
    @BeforeEach void start() { server=MockBukkit.mock(); server.addSimpleWorld("native"); }
    @AfterEach void stop() { MockBukkit.unmock(); }
    @Test void nativeMotionClearsTravelInputsWithoutMovingTheBody() {
        NativeWolf body=new NativeWolf(server); body.teleport(new Location(server.getWorlds().getFirst(),2,65,4));
        assertTrue(PetMotion.resetNative(body));
        assertEquals(List.of(2D,65D,4D,0D),body.nativeHandle.control.target);
        assertEquals(1,body.nativeHandle.control.ticks);
        assertEquals(List.of(0F,0F,0F,0F),body.nativeHandle.inputs);
        assertEquals(2,body.getLocation().getX());
        body.fail=true; assertFalse(PetMotion.resetNative(body));
        assertFalse(PetMotion.resetNative(new WolfMock(server,UUID.randomUUID())));
    }
    @Test void headRotationSupportsInheritedControlFieldsAndFailsClosedWhenNativeApiChanges() {
        HeadWolf body=new HeadWolf(server);
        assertTrue(PetHeadRotation.apply(body,20,40,15));
        assertEquals(0,((Control)body.nativeHandle.bodyRotationControl).headStableTime);
        assertEquals(List.of(20F,15F,40F),body.nativeHandle.rotations);
        assertEquals(20,body.getBodyYaw());
        body.nativeHandle.bodyRotationControl=new Object();
        assertFalse(PetHeadRotation.apply(body,30,50,10));
        body.fail=true; assertFalse(PetHeadRotation.apply(body,30,50,10));
        assertFalse(PetHeadRotation.apply(new WolfMock(server,UUID.randomUUID()),30,50,10));
    }
    @Test void partialWolfApiCanReportShakingWithoutClaimingToDeferIt() {
        MinimalWolf body=new MinimalWolf(server);
        assertEquals(1F, WolfShake.progress(body));
        assertTrue(WolfShake.shaking(body)); assertFalse(WolfShake.defer(body));
        body.fail=true; assertEquals(0F, WolfShake.progress(body)); assertFalse(WolfShake.shaking(body));
    }
    @Test void nativeShakeFailuresDoNotLeakDeferredStateOrEscapeCleanup() {
        ShakeWolf body=new ShakeWolf(server,UUID.randomUUID());
        body.nativeHandle.wet(true); body.nativeHandle.progress=.4F;
        assertEquals(.4F, WolfShake.progress(body));
        assertTrue(WolfShake.defer(body)); assertFalse(body.nativeHandle.isWet());
        assertEquals(0F, body.nativeHandle.progress);
        assertEquals(1F, body.nativeHandle.partialTick);
        assertEquals(List.of((byte)56), body.nativeHandle.level.events);
        body.fail=true;
        assertFalse(WolfShake.shaking(body)); assertFalse(WolfShake.defer(body));
        assertDoesNotThrow(()->WolfShake.restore(body));
        body.fail=false; WolfShake.restore(body); assertFalse(body.nativeHandle.isWet());
        body.nativeHandle.wet(true); assertTrue(WolfShake.defer(body));
        // A reloaded body with the same identity may come from an unsupported server implementation.
        WolfShake.restore(new WolfMock(server,body.getUniqueId()));
        WolfShake.restore(body); assertFalse(body.nativeHandle.isWet());
    }
    public static class NativeWolf extends WolfMock {
        final MoveHandle nativeHandle=new MoveHandle(); boolean fail;
        NativeWolf(ServerMock server) { super(server,UUID.randomUUID()); }
        public MoveHandle getHandle() { if(fail) throw new IllegalStateException("unloaded"); return nativeHandle; }
    }
    public static class MoveHandle {
        final MoveControl control=new MoveControl(); final List<Float> inputs=new ArrayList<>();
        public MoveControl getMoveControl() { return control; }
        public void setSpeed(float value) { inputs.add(value); }
        public void setXxa(float value) { inputs.add(value); }
        public void setYya(float value) { inputs.add(value); }
        public void setZza(float value) { inputs.add(value); }
    }
    public static class MoveControl {
        List<Double> target; int ticks;
        public void setWantedPosition(double x,double y,double z,double speed) { target=List.of(x,y,z,speed); }
        public void tick() { ticks++; }
    }
    public static class HeadWolf extends WolfMock {
        final HeadHandle nativeHandle=new HeadHandle(); boolean fail; float bodyYaw;
        @Override public void setBodyYaw(float value) { bodyYaw=value; }
        @Override public float getBodyYaw() { return bodyYaw; }
        HeadWolf(ServerMock server) { super(server,UUID.randomUUID()); }
        public HeadHandle getHandle() { if(fail) throw new IllegalStateException("unloaded"); return nativeHandle; }
    }
    public static class HeadBase { Object bodyRotationControl=new Control(); }
    public static class HeadHandle extends HeadBase {
        final List<Float> rotations=new ArrayList<>();
        public void setYRot(float value) { rotations.add(value); }
        public void setXRot(float value) { rotations.add(value); }
        public void setYHeadRot(float value) { rotations.add(value); }
    }
    public static class Control { int headStableTime=30; }
    public static class MinimalWolf extends WolfMock {
        final ProgressClock nativeHandle=new ProgressClock(); boolean fail;
        MinimalWolf(ServerMock server) { super(server,UUID.randomUUID()); }
        public ProgressClock getHandle() { if(fail) throw new IllegalStateException("unloaded"); return nativeHandle; }
    }
    public static class ProgressClock { public float getShakeAnim(float partialTick) { return 1; } }
    public static class ShakeWolf extends WolfMock {
        final NativeClock nativeHandle=new NativeClock(); boolean fail;
        ShakeWolf(ServerMock server,UUID id) { super(server,id); }
        public NativeClock getHandle() { if(fail) throw new IllegalStateException("unloaded"); return nativeHandle; }
    }
    public static class NativeClock {
        private boolean isWet;
        float progress, partialTick;
        final NativeLevel level=new NativeLevel();
        public boolean isWet() { return isWet; }
        public void wet(boolean value) { isWet=value; }
        public float getShakeAnim(float partialTick) { this.partialTick=partialTick; return progress; }
        public void handleEntityEvent(byte event) { if(event==56) progress=0; }
        public NativeLevel level() { return level; }
    }
    public static class NativeLevel {
        final List<Byte> events=new ArrayList<>();
        public void broadcastEntityEvent(NativeClock wolf,byte event) { events.add(event); }
    }
}
