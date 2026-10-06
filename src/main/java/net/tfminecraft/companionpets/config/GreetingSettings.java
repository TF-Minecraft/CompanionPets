package net.tfminecraft.companionpets.config;

import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;

/** Reunion settings are separate from the random spontaneous moment intervals. */
public record GreetingSettings(boolean enabled, double absenceSeconds, double nearRadius,
        double cooldownSeconds, double durationSeconds, double circleRadius, double speed,
        double soundIntervalSeconds, boolean jumpEnabled, double tailWagHz,
        double catCircleRadius, double catSpeed, double catSoundIntervalSeconds) {
    public static GreetingSettings read(ConfigurationSection section, Logger logger) {
        if (section == null) return new GreetingSettings(true, 300, 8, 300, 8, 2, 1.35, 1.2, true, 3, 1.4, 0.8, 0.65);
        return new GreetingSettings(section.getBoolean("enabled", true),
                number(section, "absence-seconds", 300, 1, 86400, logger),
                number(section, "near-radius", 8, 2, 32, logger),
                number(section, "cooldown-seconds", 300, 1, 86400, logger),
                number(section, "duration-seconds", 8, 2, 30, logger),
                number(section, "circle-radius", 2, 1, 4, logger),
                number(section, "speed", 1.35, 0.5, 2, logger),
                number(section, "sound-interval-seconds", 1.2, 0.5, 10, logger),
                section.getBoolean("jump-enabled", true),
                number(section, "tail-wag-hz", 3, 1, 6, logger),
                number(section, "cat-circle-radius", 1.4, 1, 4, logger),
                number(section, "cat-speed", 0.8, 0.5, 1, logger),
                number(section, "cat-sound-interval-seconds", 0.65, 0.5, 10, logger));
    }

    private static double number(ConfigurationSection section, String key, double fallback,
            double min, double max, Logger logger) {
        if (!section.contains(key)) return fallback;
        Object raw = section.get(key);
        if (raw instanceof Number n && Double.isFinite(n.doubleValue())
                && n.doubleValue() >= min && n.doubleValue() <= max) return n.doubleValue();
        logger.warning("Invalid moments.greeting." + key + "; using " + fallback);
        return fallback;
    }
}
