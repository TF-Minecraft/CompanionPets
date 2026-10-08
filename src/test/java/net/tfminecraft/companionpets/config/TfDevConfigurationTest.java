package net.tfminecraft.companionpets.config;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockbukkit.mockbukkit.MockBukkit;
import net.tfminecraft.companionpets.item.ItemRef;
import net.tfminecraft.companionpets.pet.SexMode;
import net.tfminecraft.companionpets.pet.Trick;
import net.tfminecraft.companionpets.visual.*;

/** Opt-in comparison with deployed TF Dev definitions and their local model assets. */
@EnabledIfSystemProperty(named = "companionpets.tfdev-config", matches = ".+")
class TfDevConfigurationTest {
    @Test void bundledPetsMatchDeployedDefinitionsWithOnlyBreedVoicePitchChanges() throws Exception {
        MockBukkit.mock();
        try {
            var deployedYaml = new YamlConfiguration();
            deployedYaml.load(Path.of(System.getProperty("companionpets.tfdev-config")).toFile());
            var bundledYaml = new YamlConfiguration();
            try (var input = getClass().getResourceAsStream("/config.yml")) {
                bundledYaml.loadFromString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
            var plugin = MockBukkit.createMockPlugin();
            var deployed = CompanionConfig.load(plugin, deployedYaml);
            var bundled = CompanionConfig.load(plugin, bundledYaml);
            assertEquals(deployed.types().keySet(), bundled.types().keySet());
            var breedPitches = java.util.Map.of("beagle", 1.05f, "chihuahua", 1.25f, "corgi", 1.10f,
                    "golden", .90f, "husky", .95f);
            for (var expected : deployed.types().values()) {
                var actual = bundled.type(expected.id());
                for (var component : PetTypeDef.class.getRecordComponents()) {
                    // Interaction lists inherit each file's own global care/items settings.
                    if (Set.of("items", "sounds").contains(component.getName())) continue;
                    assertEquals(component.getAccessor().invoke(expected), component.getAccessor().invoke(actual),
                            expected.id() + " " + component.getName());
                }
                float pitch = breedPitches.getOrDefault(expected.id(), 1f);
                assertEquals(pitch, actual.sounds().pitch());
                for (var event : PetSounds.Event.values()) {
                    var oldCue = expected.sounds().cue(event);
                    var newCue = actual.sounds().cue(event);
                    if (oldCue == null) { assertNull(newCue); continue; }
                    assertEquals(oldCue.sounds(), newCue.sounds());
                    assertEquals(oldCue.volume(), newCue.volume());
                    assertEquals(oldCue.pitch() * pitch, newCue.pitch(), .0001f);
                }
            }
            for (String id : java.util.List.of("tongue", "croak"))
                assertEquals(deployed.customTrick(Trick.valueOf(id)), bundled.customTrick(Trick.valueOf(id)));
        } finally { MockBukkit.unmock(); }
    }

    @Test void preparedConfigLoadsEveryCapturedTypeAndItsExistingModels() throws Exception {
        MockBukkit.mock();
        try {
            Path path = Path.of(System.getProperty("companionpets.tfdev-config"));
            var current = new YamlConfiguration(); current.load(path.toFile());
            var original = new YamlConfiguration(); original.load(path.getParent().resolve("snapshots/2026-10-04-config.yml").toFile());
            var config = CompanionConfig.load(MockBukkit.createMockPlugin(), current);
            var expectedTypes = new java.util.HashSet<>(original.getConfigurationSection("pets").getKeys(false));
            expectedTypes.remove("fox_custom");
            assertEquals(expectedTypes, config.types().keySet());
            assertEquals(13, config.types().size());
            for (String root : Set.of("care", "play", "training", "limits", "moments", "social", "items", "orders"))
                assertEquals(values(original.getConfigurationSection(root)), values(current.getConfigurationSection(root)), root);
            Path assets = path.getParent().getParent().getParent();
            for (var pet : config.types().values()) {
                var saved = original.getConfigurationSection("pets." + (pet.id().equals("fox") ? "fox_custom" : pet.id()));
                assertEquals(ItemRef.parse(saved.getString("egg")), pet.egg(), pet.id() + " egg");
                BehaviorProfile expectedProfile = pet.id().equals("frog") ? BehaviorProfile.BASIC
                        : pet.id().equals("fox") || Set.of("cat", "catblack", "catfunny", "catorange", "mainecoon").contains(pet.id())
                        ? BehaviorProfile.CAT : BehaviorProfile.DOG;
                assertEquals(expectedProfile, pet.behavior(), pet.id());
                if (saved.getString("sex", "random").equals("choose")) assertEquals(SexMode.CHOOSE, pet.sexMode());
                String model = saved.getString("appearance.model");
                if (model != null) assertEquals(model, pet.appearance().model(), pet.id() + " model");
                if (!pet.appearance().modeled()) continue;
                var blueprint = new YamlConfiguration();
                blueprint.loadFromString(Files.readString(assets.resolve("models/modelengine/companions/" + pet.appearance().model() + ".bbmodel")));
                Set<String> clips = blueprint.getMapList("animations").stream().map(entry -> entry.get("name").toString()).collect(Collectors.toSet());
                boolean tail = blueprint.getMapList("groups").stream().anyMatch(entry -> entry.get("name").toString().matches("(?i)tail([0-9_].*)?"));
                var visual = new PetVisual() {
                    public void apply(org.bukkit.entity.Entity entity, PetTypeDef type) { }
                    public boolean modelAvailable(PetTypeDef type) { return true; }
                    public Set<String> clips(PetTypeDef type) { return clips; }
                    public boolean hasTail(PetTypeDef type) { return tail; }
                };
                var report = PetCapabilities.inspect(pet, visual);
                assertTrue(report.animations().containsKey(PetAnimation.IDLE), pet.id() + " idle");
                assertTrue(report.animations().containsKey(PetAnimation.WALK), pet.id() + " walk");
                assertTrue(report.animations().containsKey(PetAnimation.LIE), pet.id() + " rest");
                if (pet.behaves(PetBehavior.BELLY_RUB)) {
                    boolean complete = Set.of(PetAnimation.LIE_BACK, PetAnimation.BELLY_UP, PetAnimation.GET_UP).stream().allMatch(report.animations()::containsKey);
                    assertEquals(!complete, report.disabledBehaviors().containsKey(PetBehavior.BELLY_RUB), pet.id());
                }
                if (pet.behaves(PetBehavior.TOY_TAIL_WAG)) assertTrue(tail, pet.id() + " tail");
                for (Trick trick : pet.tricks()) {
                    var custom = config.customTrick(trick);
                    if (custom != null)
                        assertTrue(!custom.fallbackText().isBlank() || clips.contains(custom.animation()), pet.id() + ": " + trick.name());
                }
            }
            assertEquals(EntityType.WOLF, config.type("fox").entity());
            assertEquals("minecraft:entity.fox.ambient", config.type("fox").sounds().cue(PetSounds.Event.AMBIENT).sounds().getFirst());
            assertEquals(ItemRef.parse("mmoitems:PETS:PET_FOX_EGG"), config.type("fox").egg());
            assertNull(config.type("fox_custom"));
            assertEquals(java.util.List.of(.54, 2.33, 2.67), config.customTrick(Trick.valueOf("croak")).at());
            assertEquals(java.util.List.of("minecraft:entity.frog.ambient"), config.customTrick(Trick.valueOf("croak")).sound().sounds());
            assertEquals(EntityType.WOLF, config.type("frog").entity());
            assertEquals("minecraft:entity.frog.ambient", config.type("frog").sounds().cue(PetSounds.Event.AMBIENT).sounds().getFirst());
            assertTrue(config.type("beagle").behaves(PetBehavior.SOCIAL_GREETING));
            assertTrue(config.type("beagle").behaves(PetBehavior.SOCIAL_TAIL_WAG));
            assertTrue(config.type("beagle").behaves(PetBehavior.SOCIAL_JUMPS));
            assertTrue(config.type("beagle").behaves(PetBehavior.SOCIAL_VOCALIZING));
            assertTrue(config.type("beagle").behaves(PetBehavior.TOY_WIGGLE));
            assertEquals(1f, config.type("chihuahua").sounds().pitch());
            assertTrue(config.type("chihuahua").behaves(PetBehavior.DIG_GIFTS));
        } finally { MockBukkit.unmock(); }
    }

    private static java.util.Map<String, Object> values(org.bukkit.configuration.ConfigurationSection section) {
        return section.getValues(true).entrySet().stream()
                .filter(entry -> !(entry.getValue() instanceof org.bukkit.configuration.ConfigurationSection))
                .collect(Collectors.toMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue));
    }
}
