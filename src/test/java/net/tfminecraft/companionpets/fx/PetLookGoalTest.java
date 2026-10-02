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

    private static class WatchingWolf extends WolfMock {
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
}
