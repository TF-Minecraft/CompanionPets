package net.tfminecraft.companionpets.fx;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import org.bukkit.Location;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import com.destroystokyo.paper.entity.ai.GoalType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;

class PetLookGoalTest {
    private ServerMock server;
    private WatchingWolf body;
    private LivingEntity target;
    private PetLookGoal look;

    @BeforeEach void mock() {
        server = MockBukkit.mock();
        var world = server.addSimpleWorld("world");
        body = new WatchingWolf(server);
        body.teleport(new Location(world, 0, 64, 0));
        look = new PetLookGoal(body);
        target = server.addPlayer();
        target.teleport(new Location(world, 2, 64, 2));
    }

    @AfterEach void unmock() { MockBukkit.unmock(); }

    private PetLookGoal goal() { return look; }

    @Test void tracksMovingTargetBetweenBehaviorDecisionsUsingNativeController() {
        look.track(target);
        PetLookGoal goal = goal();
        assertTrue(goal.shouldActivate());
        assertEquals(java.util.EnumSet.of(GoalType.LOOK), goal.getTypes());
        for (int tick = 1; tick <= 10; tick++) {
            body.setTicksLived(tick);
            target.teleport(target.getLocation().add(0.1, 0, 0));
            goal.tick();
            assertEquals(target.getEyeLocation(), body.lookedAt);
        }
        assertEquals(10, body.calls);
        assertEquals(40, body.speed);
        assertEquals(30, body.pitch);
    }

    @Test void expiresAndReleasesVanillaAttentionWithoutMoreRequests() {
        look.track(target);
        body.setTicksLived(14);
        assertFalse(goal().shouldStayActive());
        goal().tick();
        assertEquals(0, body.calls);
    }

    @Test void switchesTargetsAndProtectsFixedLocationFromMutation() {
        look.track(target);
        PetLookGoal existing = goal();
        Location point = new Location(body.getWorld(), 4, 65, 3);
        look.track(point);
        point.add(100, 0, 0);
        existing.tick();
        assertEquals(4, body.lookedAt.getX());
        look.track(target);
        existing.tick();
        assertEquals(target.getEyeLocation(), body.lookedAt);
    }

    @Test void stopsForCombatRemovedTargetsOtherWorldsAndExplicitCancellation() {
        look.track(target);
        body.setTarget(target);
        assertFalse(goal().shouldActivate());
        body.setTarget(null);
        look.track(target);
        target.teleport(new Location(server.addSimpleWorld("other"), 0, 64, 0));
        assertFalse(goal().shouldActivate());
        var otherPet = body.getWorld().spawn(body.getLocation(), org.bukkit.entity.Pig.class);
        look.track(otherPet);
        otherPet.remove();
        assertFalse(goal().shouldActivate());
        look.track(body.getLocation());
        look.stop();
        assertFalse(goal().shouldActivate());
    }

    @Test void restingBodyKeepsItsHeadingDuringLongLooksAndReleasesItOnStop() {
        var held = new java.util.ArrayList<float[]>();
        int[] released = {0};
        var visual = new net.tfminecraft.companionpets.visual.PetVisual() {
            @Override public void apply(Entity entity, net.tfminecraft.companionpets.config.PetTypeDef type) { }
            @Override public void holdHeadLook(Entity entity, float yaw, float head, float pitch) {
                held.add(new float[]{yaw, head, pitch});
            }
            @Override public void releaseHeadLook(Entity entity) { released[0]++; }
        };
        body.setBodyYaw(170);
        body.setRotation(170, 0);
        var position = body.getLocation().toVector();
        target.teleport(new Location(body.getWorld(), 1, 67, -4));
        for (int tick = 1; tick <= 100; tick++) {
            body.setTicksLived(tick);
            look.hold(visual); look.track(target); look.tick();
            assertEquals(170, body.getBodyYaw());
            assertEquals(170, body.getLocation().getYaw());
            assertEquals(position, body.getLocation().toVector());
            assertEquals(0, body.handle.bodyRotationControl.headStableTime);
            assertTrue(Math.abs(body.handle.headYaw - 170) <= 50);
        }
        assertEquals(100, held.size());
        assertNotEquals(170, body.handle.headYaw);
        assertEquals(0, body.speed);
        assertTrue(body.pitch <= 30);
        look.stop(); look.stop();
        assertEquals(1, released[0]);
        look.track(target); look.tick();
        assertEquals(40, body.speed, "Follow returns to native unrestricted looking");
    }

    @Test void restingWithoutTargetStaysActiveAndExpiredHoldUnlocksModel() {
        int[] releases = {0};
        look.hold(new net.tfminecraft.companionpets.visual.PetVisual() {
            @Override public void apply(Entity entity, net.tfminecraft.companionpets.config.PetTypeDef type) { }
            @Override public void releaseHeadLook(Entity entity) { releases[0]++; }
        });
        assertTrue(look.shouldActivate()); look.tick();
        body.setTicksLived(14);
        assertFalse(look.shouldActivate()); assertEquals(1, releases[0]);
    }

    @Test void postureRefreshDoesNotKeepAnOldAttentionTargetForever() {
        var visual = new net.tfminecraft.companionpets.visual.IdleVisual();
        target.teleport(new Location(body.getWorld(), -4, 64, 2));
        look.hold(visual); look.track(target);
        for (int tick = 1; tick < 40; tick++) {
            body.setTicksLived(tick); look.hold(visual); look.tick();
        }
        assertTrue(look.shouldActivate());
        assertEquals(0, body.handle.headYaw, 0.001, "Expired attention returns the held head to the front");
    }

    public static class WatchingWolf extends WolfMock {
        private final NativeHead handle = new NativeHead();
        private float bodyYaw;
        @Override public float getBodyYaw() { return bodyYaw; }
        @Override public void setBodyYaw(float yaw) { bodyYaw = yaw; }
        public NativeHead getHandle() { return handle; }
        Location lookedAt;
        int calls;
        float speed, pitch;
        WatchingWolf(ServerMock server) { super(server, UUID.randomUUID()); }
        @Override public int getHeadRotationSpeed() { return 40; }
        @Override public int getMaxHeadPitch() { return 30; }
        @Override public void lookAt(Entity entity, float speed, float pitch) {
            record(entity instanceof LivingEntity living ? living.getEyeLocation() : entity.getLocation(), speed, pitch);
        }
        @Override public void lookAt(Location point, float speed, float pitch) { record(point, speed, pitch); }
        private void record(Location point, float speed, float pitch) {
            lookedAt = point.clone(); calls++; this.speed = speed; this.pitch = pitch;
        }
    }

    public static class NativeHead {
        private final BodyControl bodyRotationControl = new BodyControl();
        float headYaw;
        public void setYRot(float yaw) { }
        public void setXRot(float pitch) { }
        public void setYHeadRot(float yaw) { headYaw = yaw; }
    }
    public static class BodyControl {
        private int headStableTime = 30;
    }
}
