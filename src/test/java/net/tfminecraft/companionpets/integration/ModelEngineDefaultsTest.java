package net.tfminecraft.companionpets.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import net.tfminecraft.companionpets.config.PetAppearance.Clip;
import net.tfminecraft.companionpets.visual.PetAnimation;

class ModelEngineDefaultsTest {
    private static final Clip IDLE = new Clip("idle", 1, 0.15), WALK = new Clip("walk", 1.2, 0.2),
            DEATH = new Clip("death", 1, 0.1), JUMP = new Clip("jump", 1, 0);

    @Test void modelEngineStatesUseConfiguredLocomotionClipsAndFallbacks() {
        var clips = Map.of(PetAnimation.IDLE, IDLE, PetAnimation.WALK, WALK, PetAnimation.DEATH, DEATH);
        assertEquals(IDLE, ModelEngineBridge.defaultClip("IDLE", clips));
        assertEquals(WALK, ModelEngineBridge.defaultClip("WALK", clips));
        assertEquals(WALK, ModelEngineBridge.defaultClip("STRAFE", clips));
        assertEquals(IDLE, ModelEngineBridge.defaultClip("JUMP", clips), "Models without a jump stay idle in the air");
        assertEquals(IDLE, ModelEngineBridge.defaultClip("HOVER", clips));
        assertEquals(WALK, ModelEngineBridge.defaultClip("FLY", clips));
        assertEquals(DEATH, ModelEngineBridge.defaultClip("DEATH", clips));
        assertNull(ModelEngineBridge.defaultClip("SPAWN", clips));
        assertNull(ModelEngineBridge.defaultClip("JUMP_START", clips));
        assertEquals(JUMP, ModelEngineBridge.defaultClip("JUMP", Map.of(PetAnimation.JUMP, JUMP)));
    }
}
