package net.tfminecraft.companionpets.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import net.tfminecraft.companionpets.behavior.Locomotion.Mode;

class VisualPoseTest {
    @Test
    void followAndStayUseActualMovementInsteadOfCommandNames() {
        assertEquals(PetAnimation.IDLE, ground(Mode.FOLLOW, 0, null));
        assertEquals(PetAnimation.WALK, ground(Mode.FOLLOW, 0.1, null));
        assertEquals(PetAnimation.IDLE, ground(Mode.STAY, 0, null));
        assertEquals(PetAnimation.IDLE, VisualPose.select(Mode.STAY, true, false, true, false, 0, 0, 0.22, PetAnimation.SIT));
        assertEquals(PetAnimation.SLEEP, ground(Mode.SLEEP, 0, null));
        assertEquals(PetAnimation.LIE, ground(Mode.LIE, 0, null));
    }

    @Test
    void runThresholdHasHysteresis() {
        assertEquals(PetAnimation.WALK, ground(Mode.FETCH, 0.20, PetAnimation.WALK));
        assertEquals(PetAnimation.RUN, ground(Mode.FETCH, 0.23, PetAnimation.WALK));
        assertEquals(PetAnimation.RUN, ground(Mode.FETCH, 0.20, PetAnimation.RUN));
        assertEquals(PetAnimation.WALK, ground(Mode.FETCH, 0.17, PetAnimation.RUN));
    }

    @Test
    void physicalEnvironmentOverridesGroundPoses() {
        assertEquals(PetAnimation.SWIM, VisualPose.select(Mode.SIT, true, true, false, false, 0, 0.1, 0.22, null));
        assertEquals(PetAnimation.JUMP, VisualPose.select(Mode.FOLLOW, false, false, false, false, 0.2, 0, 0.22, null));
        assertEquals(PetAnimation.FALL, VisualPose.select(Mode.FOLLOW, false, false, false, false, -0.2, 0, 0.22, null));
        assertEquals(PetAnimation.HOVER, VisualPose.select(Mode.FOLLOW, false, false, false, true, 0, 0, 0.22, null));
        assertEquals(PetAnimation.FLY, VisualPose.select(Mode.FOLLOW, false, false, false, true, 0.1, 0, 0.22, null));
    }

    private PetAnimation ground(Mode mode, double speed, PetAnimation previous) {
        return VisualPose.select(mode, false, false, true, false, 0, speed, 0.22, previous);
    }
}
