package net.tfminecraft.companionpets.training;

public record TrainingSettings(
        int attemptsBeforeBored,
        double rewardGain,
        double failGain,
        double rewardWindowSeconds,
        double sometimesAt,
        double learnedAt,
        double sessionDistance) {

    public static TrainingSettings defaults() {
        return new TrainingSettings(6, 20, 2, 3, 40, 80, 8);
    }
}
