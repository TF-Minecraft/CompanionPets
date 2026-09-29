package net.tfminecraft.companionpets.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class SessionsTest {
    @Test
    void reloadClearsPromptsButPreservesTrainingRestDeadline() {
        Sessions sessions = new Sessions();
        UUID player = UUID.randomUUID();
        UUID pet = UUID.randomUUID();
        long now = System.currentTimeMillis();
        sessions.hatch(player, new HatchPrompt("beagle", now + 60_000));
        sessions.training(player, new TrainingSession(pet));
        sessions.rest(pet, now + 30_000);

        sessions.clearForReload();

        assertNull(sessions.hatch(player));
        assertNull(sessions.training(player));
        assertTrue(sessions.resting(pet, now + 10_000));
        assertFalse(sessions.resting(pet, now + 30_000));
    }
}
