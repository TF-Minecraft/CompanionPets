package net.tfminecraft.companionpets.config;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;

public record MomentSettings(
        boolean enabled,
        double juvenileHours,
        double initialDelayMinSeconds,
        double initialDelayMaxSeconds,
        double juvenileIntervalMinSeconds,
        double juvenileIntervalMaxSeconds,
        double adultIntervalMinSeconds,
        double adultIntervalMaxSeconds,
        double inactiveRetrySeconds,
        double ownerRadius,
        double targetRadius,
        double plantSearchRadius,
        double lowMoodThreshold,
        double lowMoodBarkChance,
        double mischiefMinEnergy,
        double juvenileMischiefChance,
        double adultMischiefChance,
        double juvenileBarkChance,
        double adultBarkChance,
        boolean plantBreakingEnabled,
        boolean respectMobGriefing,
        Set<Material> plants,
        Particle affectionParticle,
        int affectionParticleCount,
        Particle barkParticle,
        int barkParticleCount,
        Particle mischiefParticle,
        int mischiefParticleCount,
        boolean diggingEnabled,
        double juvenileDigChance,
        double adultDigChance,
        double digMinEnergy,
        Map<Material, Integer> digLoot) {

    private static final Set<Material> SAFE_PLANTS = Set.of(
            Material.SHORT_GRASS, Material.FERN, Material.DEAD_BUSH, Material.DANDELION, Material.POPPY);

    public MomentSettings {
        plants = Set.copyOf(plants);
        digLoot = Map.copyOf(digLoot);
    }

    public static MomentSettings load(ConfigurationSection section, Logger logger) {
        MomentSettings defaults = defaults();
        return new MomentSettings(
                bool(section, "enabled", defaults.enabled()),
                number(section, "juvenile-hours", defaults.juvenileHours(), 0, 87600, logger),
                number(section, "initial-delay-min-seconds", defaults.initialDelayMinSeconds(), 0, 86400, logger),
                number(section, "initial-delay-max-seconds", defaults.initialDelayMaxSeconds(), 0, 86400, logger),
                number(section, "juvenile-interval-min-seconds", defaults.juvenileIntervalMinSeconds(), 0, 86400, logger),
                number(section, "juvenile-interval-max-seconds", defaults.juvenileIntervalMaxSeconds(), 0, 86400, logger),
                number(section, "adult-interval-min-seconds", defaults.adultIntervalMinSeconds(), 0, 86400, logger),
                number(section, "adult-interval-max-seconds", defaults.adultIntervalMaxSeconds(), 0, 86400, logger),
                number(section, "inactive-retry-seconds", defaults.inactiveRetrySeconds(), 0, 86400, logger),
                number(section, "owner-radius", defaults.ownerRadius(), 0, 128, logger),
                number(section, "target-radius", defaults.targetRadius(), 0, 32, logger),
                number(section, "plant-search-radius", defaults.plantSearchRadius(), 0, 16, logger),
                number(section, "low-mood-threshold", defaults.lowMoodThreshold(), 0, 100, logger),
                number(section, "low-mood-bark-chance", defaults.lowMoodBarkChance(), 0, 100, logger),
                number(section, "mischief-min-energy", defaults.mischiefMinEnergy(), 0, 100, logger),
                number(section, "juvenile-mischief-chance", defaults.juvenileMischiefChance(), 0, 100, logger),
                number(section, "adult-mischief-chance", defaults.adultMischiefChance(), 0, 100, logger),
                number(section, "juvenile-bark-chance", defaults.juvenileBarkChance(), 0, 100, logger),
                number(section, "adult-bark-chance", defaults.adultBarkChance(), 0, 100, logger),
                bool(section, "plant-breaking-enabled", defaults.plantBreakingEnabled()),
                bool(section, "respect-mob-griefing", defaults.respectMobGriefing()),
                plants(section, logger, defaults.plants()),
                particle(section, "affection-particle", defaults.affectionParticle(), logger),
                integer(section, "affection-particle-count", defaults.affectionParticleCount(), 1, 32, logger),
                particle(section, "bark-particle", defaults.barkParticle(), logger),
                integer(section, "bark-particle-count", defaults.barkParticleCount(), 1, 32, logger),
                particle(section, "mischief-particle", defaults.mischiefParticle(), logger),
                integer(section, "mischief-particle-count", defaults.mischiefParticleCount(), 1, 32, logger),
                bool(section, "digging-enabled", defaults.diggingEnabled()),
                number(section, "juvenile-dig-chance", defaults.juvenileDigChance(), 0, 100, logger),
                number(section, "adult-dig-chance", defaults.adultDigChance(), 0, 100, logger),
                number(section, "dig-min-energy", defaults.digMinEnergy(), 0, 100, logger),
                loot(section, logger, defaults.digLoot()));
    }

    public static MomentSettings defaults() {
        return new MomentSettings(true, 24, 180, 420, 120, 360, 300, 720, 60, 8, 6, 2, 55, 35, 40,
                40, 8, 20, 47, true, true, SAFE_PLANTS,
                Particle.SPLASH, 3, Particle.ANGRY_VILLAGER, 2, Particle.POOF, 5,
                true, 15, 10, 40,
                Map.of(Material.STICK, 45, Material.BONE, 30, Material.RABBIT, 15, Material.LEATHER_BOOTS, 10));
    }

    private static boolean bool(ConfigurationSection section, String key, boolean fallback) {
        return section != null && section.contains(key) ? section.getBoolean(key) : fallback;
    }

    private static double number(ConfigurationSection section, String key, double fallback, double min, double max, Logger logger) {
        if (section == null || !section.contains(key)) {
            return fallback;
        }
        Object raw = section.get(key);
        if (!(raw instanceof Number number) || Double.isNaN(number.doubleValue()) || Double.isInfinite(number.doubleValue())) {
            logger.warning("Config value moments." + key + " is not a finite number; using " + fallback);
            return fallback;
        }
        double value = number.doubleValue();
        double bounded = Math.max(min, Math.min(max, value));
        if (bounded != value) {
            logger.warning("Config value moments." + key + " is outside " + min + ".." + max + "; clamped to " + bounded);
        }
        return bounded;
    }

    private static int integer(ConfigurationSection section, String key, int fallback, int min, int max, Logger logger) {
        return (int) Math.round(number(section, key, fallback, min, max, logger));
    }

    private static Particle particle(ConfigurationSection section, String key, Particle fallback, Logger logger) {
        if (section == null || !section.contains(key)) {
            return fallback;
        }
        try {
            return Particle.valueOf(section.getString(key, "").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            logger.warning("Unknown particle moments." + key + "; using " + fallback);
            return fallback;
        }
    }

    private static Set<Material> plants(ConfigurationSection section, Logger logger, Set<Material> fallback) {
        if (section == null || !section.contains("plants")) {
            return fallback;
        }
        List<String> raw = section.getStringList("plants");
        Set<Material> parsed = EnumSet.noneOf(Material.class);
        for (String value : raw) {
            Material material = Material.matchMaterial(value.trim().toUpperCase(Locale.ROOT));
            if (material == null || !SAFE_PLANTS.contains(material)) {
                logger.warning("Ignoring unsupported or unsafe moments.plants entry: " + value);
            } else {
                parsed.add(material);
            }
        }
        return parsed.isEmpty() ? new LinkedHashSet<>(fallback) : parsed;
    }

    private static Map<Material, Integer> loot(ConfigurationSection section, Logger logger, Map<Material, Integer> fallback) {
        ConfigurationSection loot = section == null ? null : section.getConfigurationSection("dig-loot");
        if (loot == null) {
            return fallback;
        }
        Map<Material, Integer> parsed = new LinkedHashMap<>();
        for (String key : loot.getKeys(false)) {
            Material material = Material.matchMaterial(key.trim().toUpperCase(Locale.ROOT));
            int weight = loot.getInt(key);
            if (material == null || !material.isItem() || material.isAir() || weight <= 0) {
                logger.warning("Ignoring invalid moments.dig-loot entry: " + key);
            } else {
                parsed.put(material, weight);
            }
        }
        return parsed.isEmpty() ? fallback : parsed;
    }
}
