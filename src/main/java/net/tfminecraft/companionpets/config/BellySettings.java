package net.tfminecraft.companionpets.config;

import org.bukkit.configuration.ConfigurationSection;
import java.util.logging.Logger;

public record BellySettings(boolean enabled, double chance, double idleSeconds, double cooldownSeconds, double minMood) {
    public static BellySettings read(ConfigurationSection section, Logger logger) {
        if (section == null) return new BellySettings(true, 25, 5, 60, 70);
        return new BellySettings(section.getBoolean("enabled", true),
                number(section, "chance", 25, 0, 100, logger), number(section, "idle-seconds", 5, 1, 60, logger),
                number(section, "cooldown-seconds", 60, 1, 3600, logger), number(section, "min-mood", 70, 0, 100, logger));
    }
    private static double number(ConfigurationSection section, String key, double fallback, double min, double max, Logger logger) {
        if (!section.contains(key)) return fallback;
        Object raw = section.get(key);
        if (raw instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() >= min && n.doubleValue() <= max) return n.doubleValue();
        logger.warning("Invalid moments.belly-up." + key + "; using " + fallback);
        return fallback;
    }
}
