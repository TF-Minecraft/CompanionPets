package net.tfminecraft.companionpets.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import net.tfminecraft.companionpets.behavior.Locomotion.Mode;

class VisualPoseTest {
    @Test
    void movingAndStandingPetsLeaveTheirPoseToModelEngine() {
        for (Mode mode : new Mode[]{Mode.FOLLOW, Mode.FETCH, Mode.PLAY, Mode.STAY})
            assertTrue(AnimationController.NATIVE.contains(VisualPose.select(mode, false, false)));
        assertEquals(PetAnimation.IDLE, VisualPose.select(Mode.STAY, true, false), "Stay stands even if vanilla sat");
    }

    @Test
    void ordersAndRestSelectPostures() {
        assertEquals(PetAnimation.SIT, VisualPose.select(Mode.SIT, false, false));
        assertEquals(PetAnimation.SIT, VisualPose.select(Mode.FOLLOW, true, false), "A natively sitting pet");
        assertEquals(PetAnimation.LIE, VisualPose.select(Mode.LIE, true, false));
        assertEquals(PetAnimation.SLEEP, VisualPose.select(Mode.SLEEP, true, false));
    }

    @Test
    void waterOverridesPostures() {
        assertEquals(PetAnimation.SWIM, VisualPose.select(Mode.SIT, true, true));
    }
}
