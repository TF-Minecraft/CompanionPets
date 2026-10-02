package net.tfminecraft.companionpets.play;

public record PlaySettings(double throwSpeedLow, double throwSpeedHigh) {
    public PlaySettings {
        if (!Double.isFinite(throwSpeedLow) || !Double.isFinite(throwSpeedHigh)
                || throwSpeedLow <= 0 || throwSpeedHigh < throwSpeedLow) {
            throw new IllegalArgumentException("play throw speeds must satisfy 0 < throw-speed-low <= throw-speed-high");
        }
    }
    public static PlaySettings defaults() {
        return new PlaySettings(0.6, 1.1);
    }
}
