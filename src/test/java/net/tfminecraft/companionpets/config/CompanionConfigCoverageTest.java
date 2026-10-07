package net.tfminecraft.companionpets.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import net.tfminecraft.companionpets.item.ItemRef;
import net.tfminecraft.companionpets.pet.Trick;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class CompanionConfigCoverageTest {
    private JavaPlugin plugin;
    private final List<String> warnings = new ArrayList<>();
    private final Handler handler = new Handler() {
        @Override public void publish(LogRecord record) { warnings.add(record.getMessage()); }
        @Override public void flush() { }
        @Override public void close() { }
    };

    @BeforeEach void setup() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        plugin.getLogger().addHandler(handler);
    }
    @AfterEach void cleanup() {
        plugin.getLogger().removeHandler(handler);
        MockBukkit.unmock();
    }

    @Test void integerCountsRejectFractionsTextAndOverflowInsteadOfSilentlyChangingQuotasOrTraining() throws Exception {
        for (String path : List.of("limits.max-stored", "limits.max-out", "training.attempts-before-bored")) {
            for (String value : List.of("1.5", "not-a-count", "4294967297", ".nan", ".inf")) {
                var yaml = yaml(path.substring(0, path.indexOf('.')) + ": {" + path.substring(path.indexOf('.') + 1) + ": " + value + "}");
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> CompanionConfig.load(plugin, yaml), path + "=" + value);
                assertTrue(error.getMessage().contains(path), error.getMessage());
            }
        }
    }

    @Test void integerDefaultsZeroQuotasAndLargestIntegerRemainExact() throws Exception {
        var defaults = load("");
        assertEquals(20, defaults.limits().maxStored()); assertEquals(4, defaults.limits().maxOut());
        assertEquals(6, defaults.training().attemptsBeforeBored());
        var zero = load("limits: {max-stored: 0, max-out: 0}\ntraining: {attempts-before-bored: 1}");
        assertEquals(0, zero.limits().maxStored()); assertEquals(0, zero.limits().maxOut());
        assertEquals(1, zero.training().attemptsBeforeBored());
        var max = load("limits: {max-stored: 2147483647, max-out: 2147483647}\ntraining: {attempts-before-bored: 2147483647}");
        assertEquals(Integer.MAX_VALUE, max.limits().maxStored()); assertEquals(Integer.MAX_VALUE, max.limits().maxOut());
        assertEquals(Integer.MAX_VALUE, max.training().attemptsBeforeBored());
        assertThrows(IllegalArgumentException.class, () -> load("limits: {max-out: -1}"));
        assertThrows(IllegalArgumentException.class, () -> load("training: {attempts-before-bored: 0}"));
    }

    @Test void numericFallbacksWarnAndPreserveValidNeighbours() throws Exception {
        var defaults = load("");
        var config = load("""
                care: {dirty-loss: plenty, hunger-minutes-to-critical: 17, bond-gain-per-minute: .nan}
                presence: {owner-near-radius: .inf, away-rate: -1, cry-interval-seconds: soon, follow-teleport-blocks: 12}
                orders: {hearing-radius: .nan}
                """);
        assertEquals(defaults.care().dirtyLoss(), config.care().dirtyLoss());
        assertEquals(17, config.care().hungerMinutesToCritical());
        assertEquals(defaults.care().bondGainPerMinute(), config.care().bondGainPerMinute());
        assertEquals(32, config.ownerNearRadius()); assertEquals(0.25, config.awayRate());
        assertEquals(45, config.cryIntervalSeconds()); assertEquals(12, config.hearingRadius());
        assertTrue(warnings.stream().anyMatch(s -> s.contains("care.dirty-loss is not a number")));
        assertTrue(warnings.stream().anyMatch(s -> s.contains("presence.follow-teleport-blocks is obsolete")));
        assertThrows(IllegalArgumentException.class, () -> load("orders: {hearing-radius: 0}"));
        assertThrows(IllegalArgumentException.class, () -> load("orders: {hearing-radius: 65}"));
        assertEquals(64, load("orders: {hearing-radius: 64}").hearingRadius());
    }

    @Test void invalidKennelBlocksFallBackToTheConfiguredBlockOrBarrel() throws Exception {
        for (String raw : List.of("''", "NOT_A_MATERIAL", "123")) {
            var config = load("items: {kennel: CHEST, kennel-block: " + raw + "}");
            assertEquals(Material.CHEST, config.kennelBlock(), raw);
            assertEquals(ItemRef.vanilla(Material.CHEST), config.kennel());
        }
        for (String raw : List.of("STICK", "AIR")) {
            var config = load("items: {kennel: CHEST, kennel-block: " + raw + "}");
            assertEquals(Material.BARREL, config.kennelBlock());
        }
        assertTrue(warnings.stream().anyMatch(s -> s.contains("Unknown material NOT_A_MATERIAL")));
        assertTrue(warnings.stream().anyMatch(s -> s.contains("kennel-block must be a block")));
        var invalid = load("items: {kennel: NOT_A_MATERIAL}");
        assertNull(invalid.kennel()); assertEquals(Material.BARREL, invalid.kennelBlock());
    }

    @Test void malformedTypesAreSkippedWithActionableWarningsWithoutLosingValidTypes() throws Exception {
        var config = load("""
                pets:
                  scalar: broken
                  invalid_entity: {entity: NOT_REAL_ENTITY, egg: BONE}
                  missing_body: {egg: EGG}
                  blank_mythic: {mythic-mob: ' ', egg: STICK}
                  invalid_egg: {entity: WOLF, egg: INVALID_EGG}
                  negative_model: {entity: WOLF, egg: STICK, egg-custom-model-data: -1}
                  fractional_model: {entity: WOLF, egg: STICK, egg-custom-model-data: 1.5}
                  text_model: {entity: WOLF, egg: STICK, egg-custom-model-data: nope}
                  uninstalled_provider: {entity: WOLF, mythic-mob: WolfPet, egg: BONE}
                  valid: {entity: CAT, egg: CAT_SPAWN_EGG}
                """);
        assertEquals(Set.of("valid"), config.types().keySet());
        for (String expected : List.of("invalid_entity: entity is invalid", "missing_body because it has no entity",
                "blank_mythic because it has no entity", "invalid_egg because its egg is invalid",
                "negative_model because egg-custom-model-data", "fractional_model because egg-custom-model-data",
                "text_model because egg-custom-model-data", "uninstalled_provider because MythicMobs is not installed"))
            assertTrue(warnings.stream().anyMatch(s -> s.contains(expected)), expected + " in " + warnings);
        assertNull(config.type(null)); assertNull(config.type("missing"));
        assertSame(config.type("valid"), config.type("VALID"));
    }

    @Test void invalidAppearanceUsesVanillaAndLegacyAnimationMappingPromptsMigration() throws Exception {
        var config = load("""
                pets:
                  wolf:
                    entity: WOLF
                    egg: WOLF_SPAWN_EGG
                    appearance: {type: modelengine, model: canine, scale: 0}
                    trick-animations: {sit: sit_clip}
                """);
        assertEquals(PetAppearance.VANILLA, config.type("wolf").appearance());
        assertTrue(warnings.stream().anyMatch(s -> s.contains("will use its vanilla body: scale must be positive")));
        assertTrue(warnings.stream().anyMatch(s -> s.contains("move animation mappings to appearance.animations")));
    }

    @Test void foodsDiscardMalformedRowsAndWrongShapeWithoutReplacingGoodEntries() throws Exception {
        var config = load("""
                items:
                  foods:
                    - not_a_food_row
                    - {hunger: 35}
                    - {item: SALMON, hunger: 42}
                    - {item: BEEF, hunger: -1}
                    - {item: POTATO, hunger: .inf}
                pets: {wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG}}
                """);
        assertEquals(Map.of(ItemRef.vanilla(Material.SALMON), 42.0), config.type("wolf").foods());
        assertTrue(warnings.stream().anyMatch(s -> s.contains("Each care.foods entry must contain item and hunger")));
        var invalid = load("items: {foods: SALMON}");
        assertTrue(invalid.items().foods().isEmpty());
        assertTrue(warnings.stream().anyMatch(s -> s.contains("care.foods must be an item/hunger list")));
        assertTrue(CompanionConfig.readFoods(null, plugin.getLogger()).isEmpty());
        assertNull(CompanionConfig.parseItem(" ", plugin.getLogger()));
    }

    @Test void invalidSpeciesDefinitionsRetainBuiltinsAndDoNotPolluteNeighbouringPets() throws Exception {
        var config = load("""
                species:
                  scalar: broken
                  dog: {voice: true}
                  broken: {animations: not_a_mapping}
                pets:
                  dog: {species: dog, egg: WOLF_SPAWN_EGG}
                  empty: {species: '', egg: EGG}
                  number: {species: 123, egg: STICK}
                  unknown: {species: missing, egg: BONE}
                """);
        assertEquals(Set.of("dog"), config.types().keySet());
        assertEquals(EntityType.WOLF, config.type("dog").entity());
        assertTrue(warnings.stream().anyMatch(s -> s.contains("Species scalar must be a mapping")));
        assertTrue(warnings.stream().anyMatch(s -> s.contains("Ignoring species dog: voice must be")));
        assertTrue(warnings.stream().anyMatch(s -> s.contains("Ignoring species broken: animations must be")));
        assertTrue(warnings.stream().anyMatch(s -> s.contains("species must be a nonempty species ID")));
    }

    @Test void advancedSoundsOverrideVoiceShortcutsAndUnknownListOptionsOnlyWarn() throws Exception {
        var config = load("""
                pets:
                  muted: {entity: WOLF, egg: WOLF_SPAWN_EGG, voice: frog, sounds: false}
                  voiced:
                    entity: CAT
                    egg: CAT_SPAWN_EGG
                    voice: false
                    sounds: {preset: frog}
                    tricks: {add: [sit], remove: [jump], append: [paw]}
                """);
        assertTrue(config.type("muted").sounds().cues().isEmpty());
        assertEquals(List.of("minecraft:entity.frog.ambient"),
                config.type("voiced").sounds().cue(PetSounds.Event.AMBIENT).sounds());
        assertTrue(config.type("voiced").allowsTrick(Trick.SIT));
        assertFalse(config.type("voiced").allowsTrick(Trick.JUMP));
        assertTrue(warnings.stream().anyMatch(s -> s.contains("Unknown list adjustment pets.voiced.tricks.append")));
    }

    @Test void momentsClampFiniteValuesAndFallBackForInvalidParticleDataAndUnsafePlants() throws Exception {
        var config = load("""
                moments:
                  enabled: false
                  low-mood-threshold: 101
                  owner-radius: -1
                  target-radius: far
                  juvenile-hours: .nan
                  affection-particle: ITEM
                  bark-particle: not_a_particle
                  mischief-particle: HEART
                  affection-particle-count: 0
                  bark-particle-count: 33
                  plants: [TNT, UNKNOWN, FERN]
                  dig-loot: [broken, {weight: 2}, {item: BONE, weight: 3}]
                """);
        var moments = config.moments();
        assertFalse(moments.enabled()); assertEquals(100, moments.lowMoodThreshold());
        assertEquals(0, moments.ownerRadius()); assertEquals(MomentSettings.defaults().targetRadius(), moments.targetRadius());
        assertEquals(MomentSettings.defaults().juvenileHours(), moments.juvenileHours());
        assertEquals(Particle.SPLASH, moments.affectionParticle()); assertEquals(Particle.ANGRY_VILLAGER, moments.barkParticle());
        assertEquals(Particle.HEART, moments.mischiefParticle()); assertEquals(1, moments.affectionParticleCount());
        assertEquals(32, moments.barkParticleCount()); assertEquals(Set.of(Material.FERN), moments.plants());
        assertEquals(Map.of(ItemRef.vanilla(Material.BONE), 3), moments.digLoot());
        assertTrue(warnings.stream().anyMatch(s -> s.contains("requires particle data")));
        assertTrue(warnings.stream().anyMatch(s -> s.contains("Unknown particle moments.bark-particle")));
        assertTrue(warnings.stream().anyMatch(s -> s.contains("Each moments.dig-loot entry must contain item and weight")));
        assertTrue(load("moments: {dig-loot: BONE}").moments().digLoot().isEmpty());
        assertTrue(warnings.stream().anyMatch(s -> s.contains("moments.dig-loot must be an item/weight list")));
    }

    @Test void yamlRoundTripKeepsResolvedInheritanceLegacyItemsAndImmutableCollections() throws Exception {
        var source = yaml("""
                training: {default-tricks: [follow, sit]}
                limits: {max-stored: 7, max-out: 2}
                presence: {owner-near-radius: 16, away-rate: 0.5, cry-interval-seconds: 20}
                items: {kennel: CHEST, brush: FEATHER}
                custom-tricks: {salute: {fallback-text: '{pet} salutes {owner}'}}
                species:
                  dog:
                    model: canine
                    voice: {preset: wolf, pitch: 1.2}
                    tricks: {add: [salute]}
                    items: {treats: [COD]}
                pets:
                  Beagle:
                    species: dog
                    egg: WOLF_SPAWN_EGG
                    sex: choose
                    native-combat: true
                    care: {foods: {BEEF: 35}, medicine: HONEY_BOTTLE}
                    toys: [STICK]
                """);
        String before = source.saveToString();
        var first = CompanionConfig.load(plugin, source);
        assertEquals(before, source.saveToString(), "Resolving inheritance must not rewrite source YAML");
        var roundTrip = CompanionConfig.load(plugin, yaml(before));
        assertEquals(first.types(), roundTrip.types());
        assertEquals(first.items(), roundTrip.items()); assertEquals(first.limits(), roundTrip.limits());
        assertEquals(first.training(), roundTrip.training()); assertEquals(first.moments(), roundTrip.moments());
        assertEquals(first.social(), roundTrip.social()); assertEquals(first.roaming(), roundTrip.roaming());
        assertEquals(first.belly(), roundTrip.belly()); assertEquals(first.greeting(), roundTrip.greeting());
        var pet = roundTrip.type("BEAGLE");
        assertEquals("canine", pet.appearance().model()); assertEquals(1.2, pet.sounds().pitch(), 0.000001);
        assertEquals(Map.of(ItemRef.vanilla(Material.BEEF), 35.0), pet.foods());
        assertEquals(List.of(ItemRef.vanilla(Material.FEATHER)), pet.brushes());
        assertTrue(pet.allowsTrick(Trick.valueOf("salute")));
        assertEquals("{pet} salutes {owner}", roundTrip.customTrick(Trick.valueOf("salute")).fallbackText());
        assertEquals(pet, roundTrip.byEgg(new ItemStack(Material.WOLF_SPAWN_EGG)));
        assertNull(roundTrip.byEgg(null)); assertNull(roundTrip.byEgg(new ItemStack(Material.BONE)));
        assertThrows(UnsupportedOperationException.class, () -> roundTrip.types().clear());
        assertThrows(UnsupportedOperationException.class, () -> roundTrip.tricks().clear());
        assertThrows(UnsupportedOperationException.class, () -> pet.foods().clear());
    }

    @Test void malformedTrickListsWarnWithoutAcceptingInvalidOverrides() throws Exception {
        var scalar = load("pets: {wolf: {entity: WOLF, egg: BONE, tricks: broken, default-tricks: []}}");
        assertTrue(scalar.type("wolf").tricks().isEmpty());
        assertTrue(warnings.stream().anyMatch(s -> s.contains("tricks must be a list")));
        warnings.clear();
        var adjusted = load("pets: {wolf: {entity: WOLF, egg: BONE, tricks: {add: broken, remove: broken}}}");
        var defaults = load("pets: {wolf: {entity: WOLF, egg: BONE}}");
        assertEquals(defaults.type("wolf").tricks(), adjusted.type("wolf").tricks());
        assertEquals(2, warnings.stream().filter(s -> s.contains("must be a list; ignoring adjustment")).count());
    }

    @Test void mythicOnlyTypeUsesTheDocumentedWolfFallbackWithAWarning() throws Exception {
        MockBukkit.createMockPlugin("MythicMobs");
        var config = load("pets: {mythic: {mythic-mob: CustomPet, egg: BONE}}");
        assertEquals(EntityType.WOLF, config.type("mythic").entity());
        assertTrue(warnings.stream().anyMatch(s -> s.contains("assuming WOLF")));
    }

    private YamlConfiguration yaml(String source) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString(source);
        return yaml;
    }
    private CompanionConfig load(String source) throws Exception { return CompanionConfig.load(plugin, yaml(source)); }
}
