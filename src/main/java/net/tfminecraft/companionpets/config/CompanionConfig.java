package net.tfminecraft.companionpets.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.companionpets.care.CareSettings;
import net.tfminecraft.companionpets.management.Limits;
import net.tfminecraft.companionpets.pet.SexMode;
import net.tfminecraft.companionpets.play.PlaySettings;
import net.tfminecraft.companionpets.training.TrainingSettings;

public final class CompanionConfig {
    private final CareSettings care;
    private final PlaySettings play;
    private final TrainingSettings training;
    private final Limits limits;
    private final double ownerNearRadius;
    private final double awayRate;
    private final double followTeleportBlocks;
    private final double cryIntervalSeconds;
    private final Material kennel;
    private final Material whistle;
    private final Map<String, PetTypeDef> types;

    private CompanionConfig(
            CareSettings care,
            PlaySettings play,
            TrainingSettings training,
            Limits limits,
            double ownerNearRadius,
            double awayRate,
            double followTeleportBlocks,
            double cryIntervalSeconds,
            Material kennel,
            Material whistle,
            Map<String, PetTypeDef> types) {
        this.care = care;
        this.play = play;
        this.training = training;
        this.limits = limits;
        this.ownerNearRadius = ownerNearRadius;
        this.awayRate = awayRate;
        this.followTeleportBlocks = followTeleportBlocks;
        this.cryIntervalSeconds = cryIntervalSeconds;
        this.kennel = kennel;
        this.whistle = whistle;
        this.types = types;
    }

    public static CompanionConfig load(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        Logger logger = plugin.getLogger();
        CareSettings defaults = CareSettings.defaults();
        ConfigurationSection care = config.getConfigurationSection("care");
        CareSettings careSettings = new CareSettings(
                num(logger, care, "hunger-minutes-to-critical", defaults.hungerMinutesToCritical()),
                num(logger, care, "mood-minutes-to-critical", defaults.moodMinutesToCritical()),
                num(logger, care, "energy-minutes-to-critical", defaults.energyMinutesToCritical()),
                num(logger, care, "dirty-every-minutes", defaults.dirtyEveryMinutes()),
                num(logger, care, "dirty-loss", defaults.dirtyLoss()),
                num(logger, care, "minutes-until-unwell", defaults.minutesUntilUnwell()),
                num(logger, care, "minutes-until-sick", defaults.minutesUntilSick()),
                care != null && care.contains("decay-while-stored") ? care.getBoolean("decay-while-stored") : defaults.decayWhileStored(),
                care != null && care.contains("death-on-neglect") ? care.getBoolean("death-on-neglect") : defaults.deathOnNeglect(),
                num(logger, care, "play-seconds", defaults.playSeconds()),
                num(logger, care, "play-mood-gain", defaults.playMoodGain()),
                num(logger, care, "play-energy-cost", defaults.playEnergyCost()),
                num(logger, care, "favorite-mood-multiplier", defaults.favoriteMoodMultiplier()),
                num(logger, care, "favorite-food-mood", defaults.favoriteFoodMood()),
                num(logger, care, "sleep-minutes-to-full", defaults.sleepMinutesToFull()),
                num(logger, care, "sleeping-hunger-multiplier", defaults.sleepingHungerMultiplier()),
                num(logger, care, "health-loss-per-minute", defaults.healthLossPerMinute()),
                num(logger, care, "health-regen-per-minute", defaults.healthRegenPerMinute()),
                num(logger, care, "medicine-health-bump", defaults.medicineHealthBump()),
                num(logger, care, "bond-gain-per-minute", defaults.bondGainPerMinute()),
                num(logger, care, "bond-loss-per-minute", defaults.bondLossPerMinute()),
                num(logger, care, "wake-mood-penalty", defaults.wakeMoodPenalty()),
                num(logger, care, "critical-sound-seconds", defaults.criticalSoundSeconds()));

        ConfigurationSection play = config.getConfigurationSection("play");
        PlaySettings playSettings = new PlaySettings(
                num(logger, play, "throw-speed-low", PlaySettings.defaults().throwSpeedLow()),
                num(logger, play, "throw-speed-high", PlaySettings.defaults().throwSpeedHigh()));

        ConfigurationSection training = config.getConfigurationSection("training");
        TrainingSettings trainingDefaults = TrainingSettings.defaults();
        TrainingSettings trainingSettings = new TrainingSettings(
                training != null && training.contains("attempts-before-bored")
                        ? training.getInt("attempts-before-bored")
                        : trainingDefaults.attemptsBeforeBored(),
                num(logger, training, "reward-gain", trainingDefaults.rewardGain()),
                num(logger, training, "fail-gain", trainingDefaults.failGain()),
                num(logger, training, "reward-window-seconds", trainingDefaults.rewardWindowSeconds()),
                num(logger, training, "sometimes-at", trainingDefaults.sometimesAt()),
                num(logger, training, "learned-at", trainingDefaults.learnedAt()),
                num(logger, training, "session-distance", trainingDefaults.sessionDistance()),
                num(logger, training, "attempt-energy-cost", trainingDefaults.attemptEnergyCost()),
                num(logger, training, "attempt-hunger-cost", trainingDefaults.attemptHungerCost()),
                num(logger, training, "attempt-mood-cost", trainingDefaults.attemptMoodCost()),
                num(logger, training, "treat-hunger-gain", trainingDefaults.treatHungerGain()),
                num(logger, training, "rest-seconds", trainingDefaults.restSeconds()));

        ConfigurationSection limits = config.getConfigurationSection("limits");
        Limits limitDefaults = Limits.defaults();
        Limits limitSettings = new Limits(
                limits != null && limits.contains("max-stored") ? limits.getInt("max-stored") : limitDefaults.maxStored(),
                limits != null && limits.contains("max-out") ? limits.getInt("max-out") : limitDefaults.maxOut());

        ConfigurationSection presence = config.getConfigurationSection("presence");
        double near = num(logger, presence, "owner-near-radius", 32);
        double away = num(logger, presence, "away-rate", 0.25);
        double teleport = num(logger, presence, "follow-teleport-blocks", 16);
        double cry = num(logger, presence, "cry-interval-seconds", 45);

        ConfigurationSection items = config.getConfigurationSection("items");
        Material kennel = material(items, "kennel", Material.BARREL, plugin.getLogger());
        Material whistle = material(items, "whistle", Material.GOAT_HORN, plugin.getLogger());

        boolean mythic = plugin.getServer().getPluginManager().isPluginEnabled("MythicMobs");
        Map<String, PetTypeDef> types = readTypes(config.getConfigurationSection("pets"), mythic, plugin.getLogger());
        return new CompanionConfig(
                careSettings,
                playSettings,
                trainingSettings,
                limitSettings,
                near,
                away,
                teleport,
                cry,
                kennel,
                whistle,
                types);
    }

    private static Map<String, PetTypeDef> readTypes(ConfigurationSection pets, boolean mythic, Logger logger) {
        Map<String, PetTypeDef> types = new LinkedHashMap<>();
        if (pets == null) {
            return types;
        }
        for (String id : pets.getKeys(false)) {
            ConfigurationSection section = pets.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            String mythicMob = section.getString("mythic-mob");
            if (mythicMob != null && !mythicMob.isBlank() && !mythic) {
                logger.warning("Skipping pet type " + id + " because MythicMobs is not installed");
                continue;
            }
            EntityType entity = null;
            if (section.contains("entity")) {
                try {
                    entity = EntityType.valueOf(section.getString("entity", "").trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    logger.warning("Skipping pet type " + id + " because entity is invalid");
                    continue;
                }
            }
            if (entity == null && (mythicMob == null || mythicMob.isBlank())) {
                logger.warning("Skipping pet type " + id + " because it has no entity or mythic-mob");
                continue;
            }
            Material egg = material(section, "egg", Material.EGG, logger);
            SexMode sexMode = "choose".equalsIgnoreCase(section.getString("sex", "random")) ? SexMode.CHOOSE : SexMode.RANDOM;
            String model = blankToNull(section.getString("model"));
            ConfigurationSection care = section.getConfigurationSection("care");
            Map<Material, Double> foods = new LinkedHashMap<>();
            ConfigurationSection foodSection = care == null ? null : care.getConfigurationSection("foods");
            if (foodSection != null) {
                for (String foodId : foodSection.getKeys(false)) {
                    Material food = parseMaterial(foodId, logger);
                    if (food != null) {
                        foods.put(food, foodSection.getDouble(foodId));
                    }
                }
            }
            Material favorite = care == null ? null : parseMaterial(care.getString("favorite"), logger);
            if (favorite != null && !foods.containsKey(favorite)) {
                foods.put(favorite, 30.0);
            }
            Material medicine = care == null ? Material.HONEY_BOTTLE : material(care, "medicine", Material.HONEY_BOTTLE, logger);
            List<Material> toys = new ArrayList<>();
            for (String toyId : section.getStringList("toys")) {
                Material toy = parseMaterial(toyId, logger);
                if (toy != null && !toys.contains(toy)) {
                    toys.add(toy);
                }
            }
            Map<String, String> animations = new LinkedHashMap<>();
            ConfigurationSection animationSection = section.getConfigurationSection("animations");
            if (animationSection != null) {
                for (String state : animationSection.getKeys(false)) {
                    animations.put(state.toUpperCase(Locale.ROOT), animationSection.getString(state));
                }
            }
            types.put(id.toLowerCase(Locale.ROOT), new PetTypeDef(
                    id.toLowerCase(Locale.ROOT),
                    entity,
                    blankToNull(mythicMob),
                    egg,
                    sexMode,
                    model,
                    Collections.unmodifiableMap(foods),
                    favorite,
                    medicine,
                    List.copyOf(toys),
                    Collections.unmodifiableMap(animations)));
        }
        return Collections.unmodifiableMap(types);
    }

    private static Material material(ConfigurationSection section, String path, Material fallback, Logger logger) {
        if (section == null || !section.contains(path)) {
            return fallback;
        }
        Material parsed = parseMaterial(section.getString(path), logger);
        return parsed == null ? fallback : parsed;
    }

    private static Material parseMaterial(String raw, Logger logger) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        if (material == null) {
            logger.warning("Unknown material " + raw);
        }
        return material;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private static double num(Logger logger, ConfigurationSection section, String path, double fallback) {
        if (section == null || !section.contains(path)) {
            return fallback;
        }
        Object raw = section.get(path);
        if (!(raw instanceof Number number)) {
            logger.warning("Config value " + section.getCurrentPath() + "." + path + " is not a number; using " + fallback);
            return fallback;
        }
        double value = number.doubleValue();
        if (value < 0.0 || Double.isNaN(value) || Double.isInfinite(value)) {
            logger.warning("Config value " + section.getCurrentPath() + "." + path + " must be 0 or more; using " + fallback);
            return fallback;
        }
        return value;
    }

    public CareSettings care() {
        return care;
    }

    public PlaySettings play() {
        return play;
    }

    public TrainingSettings training() {
        return training;
    }

    public Limits limits() {
        return limits;
    }

    public double ownerNearRadius() {
        return ownerNearRadius;
    }

    public double awayRate() {
        return awayRate;
    }

    public double followTeleportBlocks() {
        return followTeleportBlocks;
    }

    public double cryIntervalSeconds() {
        return cryIntervalSeconds;
    }

    public Material kennel() {
        return kennel;
    }

    public Material whistle() {
        return whistle;
    }

    public Map<String, PetTypeDef> types() {
        return types;
    }

    public PetTypeDef type(String id) {
        if (id == null) {
            return null;
        }
        return types.get(id.toLowerCase(Locale.ROOT));
    }

    public PetTypeDef byEgg(Material material) {
        if (material == null) {
            return null;
        }
        for (PetTypeDef type : types.values()) {
            if (type.egg() == material) {
                return type;
            }
        }
        return null;
    }
}
