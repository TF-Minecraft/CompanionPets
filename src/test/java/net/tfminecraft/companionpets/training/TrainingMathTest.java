package net.tfminecraft.companionpets.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class TrainingMathTest {
    private final TrainingSettings settings = TrainingSettings.defaults();

    @Test
    void understandingFollowsTheTwoThresholds() {
        assertEquals(TrainingMath.Understanding.CLUELESS, TrainingMath.understanding(0, settings));
        assertEquals(TrainingMath.Understanding.SOMETIMES, TrainingMath.understanding(40, settings));
        assertEquals(TrainingMath.Understanding.OBEYS, TrainingMath.understanding(80, settings));
    }

    @Test
    void aLearnedTrickSucceedsAndANewTrickDoesNotAlways() {
        assertEquals(TrainingMath.Attempt.SUCCESS, TrainingMath.attempt(90, new Random(1), settings));
        TrainingMath.Attempt fresh = TrainingMath.attempt(0, new Random(2), settings);
        assertTrue(fresh == TrainingMath.Attempt.FAIL || fresh == TrainingMath.Attempt.PARTIAL);
    }

    @Test
    void successWithoutARewardDoesNotStickAndAFailedRewardBarelyMoves() {
        assertEquals(30.0, TrainingMath.afterReward(10, true, 0, 0, settings), 0.001);
        assertEquals(12.0, TrainingMath.afterReward(10, false, 100, 100, settings), 0.001);
    }

    @Test
    void percentLearnedIsMeasuredAgainstTheLearnedThreshold() {
        assertEquals(0, TrainingMath.percentLearned(0, settings));
        assertEquals(50, TrainingMath.percentLearned(40, settings));
        assertEquals(100, TrainingMath.percentLearned(80, settings));
        assertEquals(100, TrainingMath.percentLearned(95, settings));
    }

    @Test
    void criticalNeedsAndSicknessBlockAttention() {
        assertTrue(TrainingMath.attentionBlocked(10, 100, false));
        assertTrue(TrainingMath.attentionBlocked(100, 10, false));
        assertTrue(TrainingMath.attentionBlocked(100, 100, true));
        assertFalse(TrainingMath.attentionBlocked(60, 60, false));
    }
}
