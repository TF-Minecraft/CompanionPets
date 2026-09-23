package net.tfminecraft.companionpets.play;

public final class ThrowSpeed {
    private ThrowSpeed() {
    }

    public static double aim(float pitch) {
        if (pitch <= -90.0f) {
            return 0.0;
        }
        if (pitch >= 30.0f) {
            return 0.15;
        }
        if (pitch <= -25.0f) {
            return (pitch + 90.0f) / 65.0f;
        }
        if (pitch <= 0.0f) {
            return 1.0 + (pitch + 25.0f) / 25.0f * (0.4 - 1.0);
        }
        return 0.4 + pitch / 30.0f * (0.15 - 0.4);
    }

    public static double speed(float pitch, boolean sneaking, double low, double high) {
        double weight = aim(pitch);
        if (sneaking) {
            weight *= 0.35;
        }
        double min = Math.min(low, high);
        double max = Math.max(low, high);
        return min + (max - min) * weight;
    }
}
