package net.tfminecraft.companionpets.play;

public record PlaySettings(double throwSpeedLow, double throwSpeedHigh,
        double toyAttentionSeconds, double favoriteToyAttentionSeconds,
        double fetchSpeedMultiplier) {
    public PlaySettings {
        if (!Double.isFinite(throwSpeedLow) || !Double.isFinite(throwSpeedHigh)
                || throwSpeedLow <= 0 || throwSpeedHigh < throwSpeedLow) {
            throw new IllegalArgumentException("play throw speeds must satisfy 0 < throw-speed-low <= throw-speed-high");
        }
        if (!Double.isFinite(toyAttentionSeconds) || !Double.isFinite(favoriteToyAttentionSeconds)
                || toyAttentionSeconds < 1 || favoriteToyAttentionSeconds < 1) {
            throw new IllegalArgumentException("play toy attention durations must be finite and at least one second");
        }
        if (!Double.isFinite(fetchSpeedMultiplier) || fetchSpeedMultiplier <= 0 || fetchSpeedMultiplier > 3) {
            throw new IllegalArgumentException("play fetch-speed-multiplier must be finite and within (0, 3]");
        }
    }
    public PlaySettings(double throwSpeedLow, double throwSpeedHigh,
            double toyAttentionSeconds, double favoriteToyAttentionSeconds) {
        this(throwSpeedLow, throwSpeedHigh, toyAttentionSeconds, favoriteToyAttentionSeconds, 1.2);
    }
    public PlaySettings(double throwSpeedLow, double throwSpeedHigh) {
        this(throwSpeedLow, throwSpeedHigh, 20, 35);
    }
    public static PlaySettings defaults() {
        return new PlaySettings(0.6, 1.1, 20, 35, 1.2);
    }
}
