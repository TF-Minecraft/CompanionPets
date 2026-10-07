package net.tfminecraft.companionpets.integration;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TailWagTest {
    @Test void confinesGestureToTailBonesAndWagsAtRequestedFrequencyWithoutAngleDrift() {
        assertTrue(TailWag.tail("tail")); assertTrue(TailWag.tail("Tail2")); assertTrue(TailWag.tail("tail_tip"));
        assertFalse(TailWag.tail("body")); assertFalse(TailWag.tail("h_head")); assertFalse(TailWag.tail("detail"));
        assertEquals(0, TailWag.angle(0, 3), 0.0001);
        assertEquals(Math.toRadians(25), TailWag.angle(125_000_000, 2), 0.0001);
        assertEquals(-Math.toRadians(25), TailWag.angle(375_000_000, 2), 0.0001);
        assertEquals(0, TailWag.angle(500_000_000, 2), 0.0001);
    }

    @Test void changingWagSpeedContinuesFromTheCurrentTailPosition() {
        assertEquals(0.75, TailWag.advance(0.5, 125_000_000, 2), 1e-9);
        assertEquals(0.1, TailWag.advance(0.9, 100_000_000, 2), 1e-9);
        assertEquals(TailWag.angle(0.25), TailWag.angle(125_000_000, 2), 1e-6);
    }
}
