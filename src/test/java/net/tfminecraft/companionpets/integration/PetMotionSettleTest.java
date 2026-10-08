package net.tfminecraft.companionpets.integration;

import static org.mockito.Mockito.*;

import org.bukkit.entity.Wolf;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import com.destroystokyo.paper.entity.Pathfinder;

class PetMotionSettleTest {
    private Wolf body(Vector velocity, boolean routed) {
        Wolf body = mock(Wolf.class);
        Pathfinder path = mock(Pathfinder.class);
        when(body.getPathfinder()).thenReturn(path);
        when(path.hasPath()).thenReturn(routed);
        when(body.getVelocity()).thenAnswer(ignored -> velocity.clone());
        when(body.getLocation()).thenReturn(new org.bukkit.Location(null, 0, 64, 0));
        return body;
    }

    @Test void aStillBodySendsNoMotionUpdate() {
        Wolf body = body(new Vector(0, -0.08, 0), false);
        PetMotion.settle(body);
        verify(body, never()).setVelocity(any());
        verify(body.getPathfinder(), never()).stopPathfinding();
    }

    @Test void aRouteOrASlideIsStopped() {
        Wolf routed = body(new Vector(), true);
        PetMotion.settle(routed);
        verify(routed.getPathfinder()).stopPathfinding();
        verify(routed).setVelocity(new Vector());
        Wolf sliding = body(new Vector(0.2, 0, 0), false);
        PetMotion.settle(sliding);
        verify(sliding).setVelocity(new Vector());
    }

    @Test void aSwimmingBodyKeepsFloating() {
        Wolf swimming = body(new Vector(), false);
        when(swimming.isInWater()).thenReturn(true);
        PetMotion.settle(swimming);
        verify(swimming).setVelocity(new Vector(0, 0.16, 0));
    }
}
