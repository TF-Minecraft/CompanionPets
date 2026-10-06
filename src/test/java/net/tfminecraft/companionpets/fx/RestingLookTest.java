package net.tfminecraft.companionpets.fx;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

class RestingLookTest {
    private static final Location EYES = new Location(null, 0, 0, 0);

    private Location target(float yaw, double height) {
        double angle = Math.toRadians(yaw);
        return new Location(null, -4 * Math.sin(angle), height, 4 * Math.cos(angle));
    }

    private RestingLook.Angles settle(float bodyYaw, Location target) {
        var angles = new RestingLook.Angles(bodyYaw, 0);
        for (int i = 0; i < 80; i++) angles = RestingLook.next(bodyYaw, angles, EYES, target);
        return angles;
    }

    @Test void limitsHeadAtBothShouldersAndVertically() {
        assertEquals(50, settle(0, target(85, 0)).yaw(), 0.001);
        assertEquals(-50, settle(0, target(-85, 0)).yaw(), 0.001);
        assertEquals(-30, settle(0, target(0, 100)).pitch(), 0.001);
        assertEquals(30, settle(0, target(0, -100)).pitch(), 0.001);
    }

    @Test void rearTargetsAndLostTargetsReturnToFrontGradually() {
        var current = settle(0, target(60, 3));
        var next = RestingLook.next(0, current, EYES, target(179, 3));
        assertEquals(46, next.yaw());
        assertTrue(Math.abs(next.pitch() - current.pitch()) <= 4);
        assertEquals(0, settle(0, target(-179, 3)).yaw());
        assertEquals(0, settle(0, target(179, 3)).pitch());
        assertEquals(46, RestingLook.next(0, current, EYES, null).yaw());
    }

    @Test void crossesGlobalAngleBoundaryWithoutSpinning() {
        assertEquals(190, settle(170, target(-170, 0)).yaw(), 0.001);
        assertEquals(-190, settle(-170, target(170, 0)).yaw(), 0.001);
        var current = settle(0, target(85, 0));
        for (int i = 0; i < 40; i++) {
            var next = RestingLook.next(0, current, EYES, target(i % 2 == 0 ? 179 : -179, 0));
            assertTrue(Math.abs(next.yaw() - current.yaw()) <= 4);
            assertTrue(Math.abs(next.yaw()) <= 50);
            current = next;
        }
        assertEquals(0, current.yaw());
    }

    @Test void switchesAcrossFrontWithoutTakingShortestRouteThroughBack() {
        var current = settle(170, target(-105, 0));
        for (int i = 0; i < 40; i++) {
            var next = RestingLook.next(170, current, EYES, target(85, 0));
            assertTrue(Math.abs(next.yaw() - current.yaw()) <= 4);
            assertTrue(Math.abs(next.yaw() - 170) <= 50);
            current = next;
        }
        assertEquals(120, current.yaw(), 0.001);
    }
}
