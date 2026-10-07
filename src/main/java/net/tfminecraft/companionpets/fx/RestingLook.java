package net.tfminecraft.companionpets.fx;

import org.bukkit.Location;

/** Angles stay relative to the resting body, including across the +/-180 boundary. */
final class RestingLook {
    private RestingLook() { }
    static final float MAX_YAW = 50;
    static final float MAX_PITCH = 30;
    static final float STEP = 4;

    record Angles(float yaw, float pitch) { }

    static Angles next(float bodyYaw, Angles current, Location eyes, Location target) {
        float offset = 0, pitch = 0;
        if (target != null) {
            double dx = target.getX() - eyes.getX(), dz = target.getZ() - eyes.getZ();
            double horizontal = Math.hypot(dx, dz);
            if (horizontal > 1.0e-6) {
                float relative = wrap((float) Math.toDegrees(Math.atan2(-dx, dz)) - bodyYaw);
                // A resting pet does not try to watch someone behind its shoulders.
                if (Math.abs(relative) <= 90) {
                    offset = clamp(relative, MAX_YAW);
                    pitch = clamp((float) -Math.toDegrees(Math.atan2(target.getY() - eyes.getY(), horizontal)), MAX_PITCH);
                }
            }
        }
        float previous = clamp(wrap(current.yaw - bodyYaw), MAX_YAW);
        return new Angles(bodyYaw + approach(previous, offset), approach(current.pitch, pitch));
    }

    static float wrap(float angle) {
        return (angle % 360 + 540) % 360 - 180;
    }

    private static float clamp(float angle, float limit) { return Math.max(-limit, Math.min(limit, angle)); }
    private static float approach(float current, float desired) { return current + clamp(desired - current, STEP); }
}
