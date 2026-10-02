package net.tfminecraft.companionpets.config;

import org.bukkit.configuration.ConfigurationSection;

public record RoamSettings(boolean enabled, double stationarySeconds, double radius,
        double choiceMinSeconds, double choiceMaxSeconds, double playerRadius,
        double petRadius, double nameAttentionSeconds) {
    public static RoamSettings defaults() {
        return new RoamSettings(true, 2, 4, 3, 6, 6, 6, 10);
    }

    public static RoamSettings load(ConfigurationSection section, java.util.logging.Logger logger) {
        RoamSettings d = defaults();
        if (section == null) return d;
        return new RoamSettings(section.getBoolean("enabled", d.enabled()),
                number(section, "stationary-seconds", d.stationarySeconds(), 0, 30),
                number(section, "radius", d.radius(), 1, 12),
                number(section, "choice-min-seconds", d.choiceMinSeconds(), 1, 60),
                number(section, "choice-max-seconds", d.choiceMaxSeconds(), 1, 60),
                number(section, "player-radius", d.playerRadius(), 1, 16),
                number(section, "pet-radius", d.petRadius(), 1, 16),
                number(section, "name-attention-seconds", d.nameAttentionSeconds(), 1, 15));
    }

    private static double number(ConfigurationSection section, String key, double fallback, double min, double max) {
        Object raw = section.get(key);
        return raw instanceof Number n && Double.isFinite(n.doubleValue())
                ? Math.max(min, Math.min(max, n.doubleValue())) : fallback;
    }
}
