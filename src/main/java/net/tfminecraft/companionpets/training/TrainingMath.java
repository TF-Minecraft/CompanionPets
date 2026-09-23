package net.tfminecraft.companionpets.training;

import java.util.Random;

public final class TrainingMath {
    private TrainingMath() {
    }

    public enum Understanding {
        CLUELESS,
        SOMETIMES,
        OBEYS
    }

    public enum Attempt {
        FAIL,
        PARTIAL,
        SUCCESS
    }

    public static Understanding understanding(double progress, TrainingSettings settings) {
        if (progress >= settings.learnedAt()) {
            return Understanding.OBEYS;
        }
        if (progress >= settings.sometimesAt()) {
            return Understanding.SOMETIMES;
        }
        return Understanding.CLUELESS;
    }

    public static Attempt attempt(double progress, Random random, TrainingSettings settings) {
        return switch (understanding(progress, settings)) {
            case OBEYS -> Attempt.SUCCESS;
            case SOMETIMES -> random.nextBoolean() ? Attempt.SUCCESS : Attempt.PARTIAL;
            case CLUELESS -> random.nextBoolean() ? Attempt.PARTIAL : Attempt.FAIL;
        };
    }

    public static double afterReward(double progress, boolean success, double mood, double bond, TrainingSettings settings) {
        double gain = success
                ? settings.rewardGain() * (1.0 + 0.15 * mood / 100.0 + 0.15 * bond / 100.0)
                : settings.failGain();
        return Math.min(100.0, progress + gain);
    }

    public static boolean attentionBlocked(double hunger, double energy, boolean sickOrWeak) {
        return hunger < 25.0 || energy < 25.0 || sickOrWeak;
    }
}
