package net.tfminecraft.companionpets.training;

public record TrainingSettings(
        int attemptsBeforeBored,
        double rewardGain,
        double failGain,
        double rewardWindowSeconds,
        double sometimesAt,
        double learnedAt,
        double sessionDistance,
        double attemptEnergyCost,
        double attemptHungerCost,
        double attemptMoodCost,
        double treatHungerGain,
        double restSeconds) {

    public TrainingSettings {
        if (!Double.isFinite(learnedAt) || learnedAt <= 0 || learnedAt > 100
                || !Double.isFinite(sometimesAt) || sometimesAt < 0 || sometimesAt > learnedAt) {
            throw new IllegalArgumentException("training thresholds must satisfy 0 <= sometimes-at <= learned-at <= 100, with learned-at > 0");
        }
        if (attemptsBeforeBored < 1 || rewardWindowSeconds <= 0 || sessionDistance <= 0) {
            throw new IllegalArgumentException("training attempts-before-bored, reward-window-seconds and session-distance must be positive");
        }
    }

    public static TrainingSettings defaults() {
        return new TrainingSettings(6, 20, 2, 3, 40, 80, 8, 8, 5, 4, 3, 90);
    }
}
