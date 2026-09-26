package net.tfminecraft.companionpets.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.tfminecraft.companionpets.care.Feeding;
import net.tfminecraft.companionpets.pet.Activity;

class RestAndFeedingTest {
    @Test
    void aWornOutPetLiesDownUnlessItIsAlreadyBusy() {
        assertFalse(Rest.shouldLieDown(59, Activity.NONE, false, 1_000L, 0L));
        assertTrue(Rest.shouldLieDown(24, Activity.NONE, false, 1_000L, 0L));
        assertFalse(Rest.shouldLieDown(25, Activity.NONE, false, 1_000L, 0L));
        assertFalse(Rest.shouldLieDown(10, Activity.PLAYING, false, 1_000L, 0L));
        assertFalse(Rest.shouldLieDown(24, Activity.NONE, true, 1_000L, 0L));
        assertFalse(Rest.shouldLieDown(24, Activity.SLEEPING, false, 1_000L, 0L));
        assertFalse(Rest.shouldLieDown(24, Activity.NONE, false, 1_000L, 2_000L));
        assertTrue(Rest.shouldLieDown(24, Activity.NONE, false, 2_000L, 2_000L));
    }

    @Test
    void foodOnAFullStomachIsTooMuch() {
        assertEquals(Feeding.Outcome.ATE, Feeding.outcome(99));
        assertEquals(Feeding.Outcome.OVERATE, Feeding.outcome(100));
    }
}
