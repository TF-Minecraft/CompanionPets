package net.tfminecraft.companionpets.care;

public record CareSettings(
        double hungerMinutesToCritical,
        double moodMinutesToCritical,
        double energyMinutesToCritical,
        double dirtyEveryMinutes,
        double dirtyLoss,
        double minutesUntilUnwell,
        double minutesUntilSick,
        boolean decayWhileStored,
        boolean deathOnNeglect,
        double playSeconds,
        double playMoodGain,
        double playEnergyCost,
        double favoriteMoodMultiplier,
        double favoriteFoodMood,
        double sleepMinutesToFull,
        double sleepingHungerMultiplier,
        double healthLossPerMinute,
        double healthRegenPerMinute,
        double medicineHealthBump,
        double bondGainPerMinute,
        double bondLossPerMinute,
        double wakeMoodPenalty,
        double criticalSoundSeconds) {

    public static CareSettings defaults() {
        return new CareSettings(
                30,
                30,
                40,
                20,
                30,
                3,
                3,
                false,
                false,
                6,
                22,
                10,
                1.5,
                8,
                8,
                0.5,
                12,
                20,
                20,
                0.4,
                0.8,
                8,
                8);
    }
}
