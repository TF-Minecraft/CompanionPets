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
        CareSettings defaults = CareSettings.defaults();
        ConfigurationSection care = config.getConfigurationSection("care");
        CareSettings careSettings = new CareSettings(
                num(care, "hunger-minutes-to-critical", defaults.hungerMinutesToCritical()),
                num(care, "mood-minutes-to-critical", defaults.moodMinutesToCritical()),
                num(care, "energy-minutes-to-critical", defaults.energyMinutesToCritical()),
                num(care, "dirty-every-minutes", defaults.dirtyEveryMinutes()),
                num(care, "dirty-loss", defaults.dirtyLoss()),
                num(care, "minutes-until-unwell", defaults.minutesUntilUnwell()),
                num(care, "minutes-until-sick", defaults.minutesUntilSick()),
                care != null && care.contains("decay-while-stored") ? care.getBoolean("decay-while-stored") : defaults.decayWhileStored(),
                care != null && care.contains("death-on-neglect") ? care.getBoolean("death-on-neglect") : defaults.deathOnNeglect(),
                num(care, "play-seconds", defaults.playSeconds()),
                num(care, "play-mood-gain", defaults.playMoodGain()),
                num(care, "play-energy-cost", defaults.playEnergyCost()),
                num(care, "favorite-mood-multiplier", defaults.favoriteMoodMultiplier()),
                num(care, "favorite-food-mood", defaults.favoriteFoodMood()),
                num(care, "sleep-minutes-to-full", defaults.sleepMinutesToFull()),
                num(care, "sleeping-hunger-multiplier", defaults.sleepingHungerMultiplier()),
                num(care, "health-loss-per-minute", defaults.healthLossPerMinute()),
                num(care, "health-regen-per-minute", defaults.healthRegenPerMinute()),
                num(care, "medicine-health-bump", defaults.medicineHealthBump()),
                num(care, "bond-gain-per-minute", defaults.bondGainPerMinute()),
                num(care, "bond-loss-per-minute", defaults.bondLossPerMinute()),
                num(care, "wake-mood-penalty", defaults.wakeMoodPenalty()),
                num(care, "critical-sound-seconds", defaults.criticalSoundSeconds()));

        ConfigurationSection play = config.getConfigurationSection("play");
        PlaySettings playSettings = new PlaySettings(
                num(play, "throw-speed-low", PlaySettings.defaults().throwSpeedLow()),
                num(play, "throw-speed-high", PlaySettings.defaults().throwSpeedHigh()));

        ConfigurationSection training = config.getConfigurationSection("training");
        TrainingSettings trainingDefaults = TrainingSettings.defaults();
        TrainingSettings trainingSettings = new TrainingSettings(
                training != null && training.contains("attempts-before-bored")
                        ? training.getInt("attempts-before-bored")
                        : trainingDefaults.attemptsBeforeBored(),
                num(training, "reward-gain", trainingDefaults.rewardGain()),
                num(training, "fail-gain", trainingDefaults.failGain()),
                num(training, "reward-window-seconds", trainingDefaults.rewardWindowSeconds()),
                num(training, "sometimes-at", trainingDefaults.sometimesAt()),
                num(training, "learned-at", trainingDefaults.learnedAt()),
                num(training, "session-distance", trainingDefaults.sessionDistance()));

        ConfigurationSection limits = config.getConfigurationSection("limits");
        Limits limitDefaults = Limits.defaults();
        Limits limitSettings = new Limits(
                limits != null && limits.contains("max-stored") ? limits.getInt("max-stored") : limitDefaults.maxStored(),
                limits != null && limits.contains("max-out") ? limits.getInt("max-out") : limitDefaults.maxOut());

        ConfigurationSection presence = config.getConfigurationSection("presence");
        double near = num(presence, "owner-near-radius", 32);
        double away = num(presence, "away-rate", 0.25);
        double teleport = num(presence, "follow-teleport-blocks", 16);
        double cry = num(presence, "cry-interval-seconds", 45);

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

    private static double num(ConfigurationSection section, String path, double fallback) {
        if (section == null || !section.contains(path)) {
            return fallback;
        }
        return section.getDouble(path);
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
