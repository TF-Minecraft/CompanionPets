package net.tfminecraft.companionpets.play;

public record PlaySettings(double throwSpeedLow, double throwSpeedHigh) {
    public static PlaySettings defaults() {
        return new PlaySettings(0.6, 1.1);
    }
}
