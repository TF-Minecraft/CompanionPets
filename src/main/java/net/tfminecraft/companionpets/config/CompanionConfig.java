package net.tfminecraft.companionpets.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.Material;
import net.tfminecraft.companionpets.item.ItemRef;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.companionpets.care.CareSettings;
import net.tfminecraft.companionpets.management.Limits;
import net.tfminecraft.companionpets.pet.SexMode;
import net.tfminecraft.companionpets.pet.Trick;
import net.tfminecraft.companionpets.play.PlaySettings;
import net.tfminecraft.companionpets.training.TrainingSettings;

public final class CompanionConfig {
    private final CareSettings care;
    private final PlaySettings play;
    private final TrainingSettings training;
    private final Limits limits;
    private final MomentSettings moments;
    private final SocialSettings social;
    private final RoamSettings roaming;
    private final double ownerNearRadius;
    public double hearingRadius() { return hearingRadius; }
    private final double hearingRadius;
    private final double awayRate;
    private final double cryIntervalSeconds;
    private final ItemRef kennel;
    private final Material kennelBlock;
    private final String kennelFurniture;
    private final PetItems items;
    private final Map<String, PetTypeDef> types;
    private final Map<Trick, CustomTrick> customTricks;
    private final BellySettings belly;
    public BellySettings belly() { return belly; }
    private final GreetingSettings greeting;
    public GreetingSettings greeting() { return greeting; }
    public CustomTrick customTrick(Trick trick) { return customTricks.get(trick); }
    public java.util.List<Trick> tricks() {
        var result = new ArrayList<>(List.of(Trick.values()));
        result.addAll(customTricks.keySet());
        return List.copyOf(result);
    }

    private CompanionConfig(
            CareSettings care,
            PlaySettings play,
            TrainingSettings training,
            Limits limits,
            MomentSettings moments,
            SocialSettings social,
            RoamSettings roaming,
            double ownerNearRadius,
            double hearingRadius,
            double awayRate,
            double cryIntervalSeconds,
            ItemRef kennel,
            Material kennelBlock,
            String kennelFurniture,
            PetItems items,
            Map<String, PetTypeDef> types,
            Map<Trick, CustomTrick> customTricks,
            BellySettings belly,
            GreetingSettings greeting) {
        this.care = care;
        this.play = play;
        this.training = training;
        this.limits = limits;
        this.moments = moments;
        this.social = social;
        this.roaming = roaming;
        this.ownerNearRadius = ownerNearRadius;
        if (hearingRadius <= 0 || hearingRadius > 64) throw new IllegalArgumentException("orders.hearing-radius must be greater than 0 and at most 64");
        this.hearingRadius = hearingRadius;
        this.awayRate = awayRate;
        this.cryIntervalSeconds = cryIntervalSeconds;
        this.kennel = kennel;
        this.kennelBlock = kennelBlock;
        this.kennelFurniture = kennelFurniture;
        this.items = items;
        this.types = types;
        this.customTricks = customTricks;
        this.belly = belly;
        this.greeting = greeting;
    }

    public static CompanionConfig load(JavaPlugin plugin) {
        return load(plugin, plugin.getConfig());
    }

    public static CompanionConfig load(JavaPlugin plugin, FileConfiguration config) {
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
                num(logger, care, "critical-sound-seconds", defaults.criticalSoundSeconds()),
                num(logger, care, "overfeed-mood-penalty", defaults.overfeedMoodPenalty()),
                num(logger, care, "overfeed-health-penalty", defaults.overfeedHealthPenalty()),
                num(logger, care, "struck-mood-penalty", defaults.struckMoodPenalty()),
                num(logger, care, "rest-again-seconds", defaults.restAgainSeconds()));

        ConfigurationSection play = config.getConfigurationSection("play");
        PlaySettings playSettings = new PlaySettings(
                num(logger, play, "throw-speed-low", PlaySettings.defaults().throwSpeedLow()),
                num(logger, play, "throw-speed-high", PlaySettings.defaults().throwSpeedHigh()),
                num(logger, play, "toy-attention-seconds", PlaySettings.defaults().toyAttentionSeconds()),
                num(logger, play, "favorite-toy-attention-seconds", PlaySettings.defaults().favoriteToyAttentionSeconds()),
                num(logger, play, "fetch-speed-multiplier", PlaySettings.defaults().fetchSpeedMultiplier()));

        ConfigurationSection training = config.getConfigurationSection("training");
        TrainingSettings trainingDefaults = TrainingSettings.defaults();
        TrainingSettings trainingSettings = new TrainingSettings(
                integer(training, "attempts-before-bored", trainingDefaults.attemptsBeforeBored()),
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
                integer(limits, "max-stored", limitDefaults.maxStored()),
                integer(limits, "max-out", limitDefaults.maxOut()));

        MomentSettings momentSettings = MomentSettings.load(config.getConfigurationSection("moments"), logger);
        SocialSettings socialSettings = SocialSettings.load(config.getConfigurationSection("social"), logger);
        RoamSettings roamSettings = RoamSettings.load(config.getConfigurationSection("roaming"), logger);

        ConfigurationSection presence = config.getConfigurationSection("presence");
        double near = num(logger, presence, "owner-near-radius", 32);
        double away = num(logger, presence, "away-rate", 0.25);
        if (presence != null && presence.contains("follow-teleport-blocks"))
            logger.warning("presence.follow-teleport-blocks is obsolete; native owner-follow controls teleportation");
        double cry = num(logger, presence, "cry-interval-seconds", 45);

        ConfigurationSection items = PetItems.explicitSection(config, "items");
        ItemRef kennel = item(items, "kennel", ItemRef.vanilla(Material.BARREL), logger);
        Material kennelBlock = material(items, "kennel-block", kennel == null || kennel.material() == null ? Material.BARREL : kennel.material(), logger);
        if (!kennelBlock.isBlock() || kennelBlock.isAir()) {
            logger.warning("items.kennel-block must be a block; using BARREL");
            kennelBlock = Material.BARREL;
        }
        PetItems interactionItems = PetItems.read(items, PetItems.defaults(), logger);
        String kennelFurniture = null;
        if (PetItems.has(items, "kennel-furniture")) {
            ItemRef furniture = ItemRef.parse(ItemRef.yamlToken(items.get("kennel-furniture")));
            if (furniture.kind() != ItemRef.Kind.ITEMSADDER)
                throw new IllegalArgumentException("items.kennel-furniture must be an ItemsAdder namespaced ID");
            kennelFurniture = furniture.id();
            if (kennel == null || !kennel.equals(furniture))
                throw new IllegalArgumentException("items.kennel must match items.kennel-furniture");
        }
        if (PetItems.has(items, "brush") && !PetItems.has(items, "brushes")) {
            interactionItems = new PetItems(interactionItems.foods(), interactionItems.treats(), interactionItems.medicines(),
                    PetItems.singleton(items.get("brush"), logger), interactionItems.toys());
        }
        boolean mythic = plugin.getServer().getPluginManager().isPluginEnabled("MythicMobs");
        Map<Trick, CustomTrick> custom = CustomTrick.read(config.getConfigurationSection("custom-tricks"), logger);
        List<Trick> defaultTricks = readDefaultTricks(config, "training.default-tricks", List.of(Trick.FOLLOW), custom);
        Map<String, PetTypeDef> types = readTypes(config.getConfigurationSection("pets"), config.getConfigurationSection("species"), mythic, plugin.getLogger(), custom, interactionItems, defaultTricks);
        return new CompanionConfig(
                careSettings,
                playSettings,
                trainingSettings,
                limitSettings,
                momentSettings,
                socialSettings,
                roamSettings,
                near,
                num(logger, config.getConfigurationSection("orders"), "hearing-radius", 12),
                away,
                cry,
                kennel,
                kennelBlock,
                kennelFurniture,
                interactionItems,
                types,
                custom,
                BellySettings.read(config.getConfigurationSection("moments.belly-up"), logger),
                GreetingSettings.read(config.getConfigurationSection("moments.greeting"), logger));
    }

    private static List<Trick> readDefaultTricks(ConfigurationSection section, String key, List<Trick> inherited, Map<Trick, CustomTrick> custom) {
        if (!section.contains(key)) return inherited;
        if (!section.isList(key)) throw new IllegalArgumentException(section.getCurrentPath() + "." + key + " must be a list of trick IDs");
        var result = new java.util.LinkedHashSet<Trick>();
        for (Object value : section.getList(key)) {
            if (!(value instanceof String id)) throw new IllegalArgumentException(key + ": expected a trick ID, got " + value);
            Trick trick = Trick.valueOf(id.trim());
            if (trick.equals(Trick.SPIN) || trick.kind() == Trick.Kind.CUSTOM && !custom.containsKey(trick))
                throw new IllegalArgumentException(key + ": unknown or removed trick " + id);
            result.add(trick);
        }
        return List.copyOf(result);
    }

    private static Map<String, PetTypeDef> readTypes(ConfigurationSection pets, ConfigurationSection species, boolean mythic, Logger logger, Map<Trick, CustomTrick> custom, PetItems globalItems, List<Trick> globalDefaults) {
        Map<String, PetTypeDef> types = new LinkedHashMap<>();
        if (pets == null) {
            return types;
        }
        PetDefinitions definitions = new PetDefinitions(species, custom, logger);
        for (String id : pets.getKeys(false)) {
            ConfigurationSection section = pets.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            PetDefinitions.Resolved resolved;
            try {
                resolved = definitions.resolve(section);
                section = resolved.section();
            } catch (IllegalArgumentException ex) {
                logger.warning("Skipping pet type " + id + ": " + ex.getMessage());
                continue;
            }
            String mythicMob = section.getString("mythic-mob");
            if (mythicMob != null && !mythicMob.isBlank() && !mythic) {
                logger.warning("Skipping pet type " + id + " because MythicMobs is not installed");
                continue;
            }
            EntityType entity = null;
            if (section.contains("entity")) {
                // PetDefinitions.resolve already validated this effective entity.
                entity = EntityType.valueOf(section.getString("entity", "").trim().toUpperCase(Locale.ROOT));
            }
            if (entity == null && (mythicMob == null || mythicMob.isBlank())) {
                logger.warning("Skipping pet type " + id + " because it has no entity or mythic-mob");
                continue;
            }
            if (entity == null) {
                entity = EntityType.WOLF;
                logger.warning("Pet " + id + ": declare entity: WOLF or CAT for MythicMobs; assuming WOLF");
            }
            if (!PetBase.supported(entity)) {
                logger.warning("Skipping pet type " + id + ": native following requires entity WOLF or CAT; found " + entity);
                continue;
            }
            ItemRef egg = parseItem(ItemRef.yamlToken(section.get("egg", "EGG")), logger);
            if (egg == null) {
                logger.warning("Skipping pet type " + id + " because its egg is invalid");
                continue;
            }
            Integer eggCustomModelData = null;
            if (section.contains("egg-custom-model-data")) {
                if (!section.isInt("egg-custom-model-data") || section.getInt("egg-custom-model-data") < 0) {
                    logger.warning("Skipping pet type " + id + " because egg-custom-model-data must be a nonnegative integer");
                    continue;
                }
                if (egg.kind() != ItemRef.Kind.VANILLA) {
                    logger.warning("Skipping pet type " + id + ": egg-custom-model-data is only for vanilla eggs; use the custom item's ID instead");
                    continue;
                }
                eggCustomModelData = section.getInt("egg-custom-model-data");
            }
            SexMode sexMode = "choose".equalsIgnoreCase(section.getString("sex", "random")) ? SexMode.CHOOSE : SexMode.RANDOM;
            Set<Trick> tricks = new java.util.LinkedHashSet<>(resolved.tricks());
            List<Trick> defaults = readDefaultTricks(section, "default-tricks", globalDefaults, custom);
            tricks.addAll(defaults);
            PetAppearance appearance;
            try {
                appearance = PetAppearance.read(section, id, logger);
            } catch (IllegalArgumentException ex) {
                logger.warning("Pet " + id + " will use its vanilla body: " + ex.getMessage());
                appearance = PetAppearance.VANILLA;
            }
            PetItems petItems = PetItems.forPet(section, globalItems, logger);
            if (section.contains("animations") || section.contains("trick-animations")) {
                logger.warning("Pet " + id + ": move animation mappings to appearance.animations");
            }
            boolean duplicateEgg = false;
            for (PetTypeDef existing : types.values()) {
                if (existing.egg().equals(egg) && Objects.equals(existing.eggCustomModelData(), eggCustomModelData)) {
                    logger.warning("Skipping pet type " + id + " because its egg matches " + existing.id());
                    duplicateEgg = true;
                    break;
                }
            }
            if (duplicateEgg) {
                continue;
            }
            types.put(id.toLowerCase(Locale.ROOT), new PetTypeDef(
                    id.toLowerCase(Locale.ROOT),
                    entity,
                    blankToNull(mythicMob),
                    egg,
                    eggCustomModelData,
                    sexMode,
                    appearance,
                    petItems, tricks, defaults, resolved.behaviors(),
                    PetSounds.read(section, entity, logger), section.getBoolean("native-combat", false), resolved.species()));
        }
        return Collections.unmodifiableMap(types);
    }

    static Map<ItemRef, Double> readFoods(ConfigurationSection care, Logger logger) {
        Map<ItemRef, Double> foods = new LinkedHashMap<>();
        if (care == null) return foods;
        Object raw = care.get("foods", null);
        if (raw instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> values && ItemRef.yamlToken(values.get("item")) != null) {
                    addFood(foods, ItemRef.yamlToken(values.get("item")), values.get("hunger"), logger);
                } else logger.warning("Each care.foods entry must contain item and hunger");
            }
        } else if (raw instanceof ConfigurationSection section) {
            // Read literal keys instead of treating dots in item IDs as YAML paths.
            section.getValues(false).forEach((id, gain) -> addFood(foods, id, gain, logger));
        } else if (raw != null) logger.warning("care.foods must be an item/hunger list or a legacy material map");
        return foods;
    }

    private static void addFood(Map<ItemRef, Double> foods, String id, Object raw, Logger logger) {
        ItemRef food = parseItem(id, logger);
        if (food == null) return;
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0) {
            logger.warning("Skipping food " + id + ": hunger must be a finite nonnegative number");
            return;
        }
        foods.put(food, number.doubleValue());
    }

    private static ItemRef item(ConfigurationSection section, String path, ItemRef fallback, Logger logger) {
        if (section == null || !section.contains(path)) return fallback;
        // Invalid selectors disable the action instead of matching another item.
        return parseItem(ItemRef.yamlToken(section.get(path)), logger);
    }

    static ItemRef parseItem(String raw, Logger logger) {
        if (raw == null || raw.isBlank()) return null;
        try { return ItemRef.parse(raw); }
        catch (IllegalArgumentException ex) { logger.warning(ex.getMessage()); return null; }
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

    private static int integer(ConfigurationSection section, String path, int fallback) {
        if (section == null || !section.contains(path)) return fallback;
        if (!section.isInt(path)) throw new IllegalArgumentException(section.getCurrentPath() + "." + path + " must be a 32-bit integer");
        return section.getInt(path);
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

    public MomentSettings moments() {
        return moments;
    }

    public SocialSettings social() {
        return social;
    }

    public RoamSettings roaming() {
        return roaming;
    }

    public double ownerNearRadius() {
        return ownerNearRadius;
    }

    public double awayRate() {
        return awayRate;
    }

    public double cryIntervalSeconds() {
        return cryIntervalSeconds;
    }

    public ItemRef kennel() {
        return kennel;
    }

    public Material kennelBlock() { return kennelBlock; }
    public String kennelFurniture() { return kennelFurniture; }

    public PetItems items() { return items; }

    public Map<String, PetTypeDef> types() {
        return types;
    }

    public PetTypeDef type(String id) {
        if (id == null) {
            return null;
        }
        return types.get(id.toLowerCase(Locale.ROOT));
    }

    public PetTypeDef byEgg(ItemStack item) {
        // Explicit custom identity wins over legacy material + model eggs.
        for (PetTypeDef type : types.values()) {
            if (type.egg().kind() != ItemRef.Kind.VANILLA && type.matchesEgg(item)) return type;
        }
        for (PetTypeDef type : types.values()) {
            if (type.matchesEgg(item)) {
                return type;
            }
        }
        return null;
    }
}
