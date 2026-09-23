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

    public static TrainingSettings defaults() {
        return new TrainingSettings(6, 20, 2, 3, 40, 80, 8, 8, 5, 4, 3, 90);
    }
}
