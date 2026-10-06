package net.tfminecraft.companionpets.integration;

import java.util.Locale;

/** Tail-only motion in radians, relative to the model's existing resting transform. */
final class TailWag {
    static boolean tail(String bone) {
        String id = bone.toLowerCase(Locale.ROOT);
        return id.equals("tail") || id.matches("tail[0-9_].*");
    }

    static float angle(long elapsedNanos, double hz) {
        double phase = (elapsedNanos / 1_000_000_000.0 * hz) % 1;
        return (float) (Math.sin(phase * Math.PI * 2) * Math.toRadians(25));
    }
}
