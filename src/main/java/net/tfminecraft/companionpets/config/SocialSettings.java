package net.tfminecraft.companionpets.config;

import org.bukkit.configuration.ConfigurationSection;

public record SocialSettings(boolean enabled, double encounterRadius, double barkRadius,
        double ownerRadius, double encounterCooldownSeconds, double barkSeconds,
        double calmCooldownSeconds, double territorialBarkChance,
        double sameOwnerBarkChance, double chaseChance, int calmClicks,
        double greetingRadius, double greetingSeconds, double separationSeconds,
        double greetingFriendshipGain, double sniffFriendshipGain, double chaseFriendshipGain,
        double maxFriendshipGainPerMinute, double searchIntervalSeconds,
        double encounterChance) {

    public static SocialSettings defaults() {
        return new SocialSettings(true, 8, 6, 16, 60, 8, 30, 80, 10, 50, 3,
                5, 3, 10, 2, 6, 8, 12, 3, 20);
    }

    public static SocialSettings load(ConfigurationSection section, java.util.logging.Logger logger) {
        SocialSettings d = defaults();
        if (section == null) return d;
        double encounter = num(section, "encounter-radius", d.encounterRadius(), 2, 32);
        return new SocialSettings(section.getBoolean("enabled", d.enabled()), encounter,
                num(section, "bark-radius", d.barkRadius(), 1, encounter),
                num(section, "owner-radius", d.ownerRadius(), 0, 64),
                num(section, "encounter-cooldown-seconds", d.encounterCooldownSeconds(), 2, 3600),
                num(section, "bark-seconds", d.barkSeconds(), 2, 60),
                num(section, "calm-cooldown-seconds", d.calmCooldownSeconds(), 0, 3600),
                num(section, "territorial-bark-chance", d.territorialBarkChance(), 0, 100),
                num(section, "same-owner-bark-chance", d.sameOwnerBarkChance(), 0, 100),
                num(section, "chase-chance", d.chaseChance(), 0, 100),
                (int) num(section, "calm-clicks", d.calmClicks(), 2, 8),
                num(section, "greeting-radius", Math.min(encounter, d.greetingRadius()), 2, encounter),
                num(section, "greeting-seconds", d.greetingSeconds(), 2, 4),
                num(section, "separation-seconds", d.separationSeconds(), 2, 3600),
                num(section, "greeting-friendship-gain", d.greetingFriendshipGain(), 0, 100),
                num(section, "sniff-friendship-gain", d.sniffFriendshipGain(), 0, 100),
                num(section, "chase-friendship-gain", d.chaseFriendshipGain(), 0, 100),
                num(section, "max-friendship-gain-per-minute", d.maxFriendshipGainPerMinute(), 0, 100),
                num(section, "search-interval-seconds", d.searchIntervalSeconds(), 0.5, 30),
                num(section, "encounter-chance", d.encounterChance(), 0, 100));
    }

    private static double num(ConfigurationSection section, String key, double fallback, double min, double max) {
        Object raw = section.get(key);
        return raw instanceof Number n && Double.isFinite(n.doubleValue())
                ? Math.max(min, Math.min(max, n.doubleValue())) : fallback;
    }
}
