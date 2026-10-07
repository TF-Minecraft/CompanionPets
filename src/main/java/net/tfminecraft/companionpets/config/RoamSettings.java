package net.tfminecraft.companionpets.config;

import org.bukkit.configuration.ConfigurationSection;

public record RoamSettings(double stationarySeconds, double nameAttentionSeconds) {
    public static RoamSettings defaults() {
        return new RoamSettings(2, 10);
    }

    public static RoamSettings load(ConfigurationSection section, java.util.logging.Logger logger) {
        RoamSettings d = defaults();
        if (section == null) return d;
        return new RoamSettings(number(section, "stationary-seconds", d.stationarySeconds(), 0, 30),
                number(section, "name-attention-seconds", d.nameAttentionSeconds(), 1, 15));
    }

    private static double number(ConfigurationSection section, String key, double fallback, double min, double max) {
        Object raw = section.get(key);
        return raw instanceof Number n && Double.isFinite(n.doubleValue())
                ? Math.max(min, Math.min(max, n.doubleValue())) : fallback;
    }
}
