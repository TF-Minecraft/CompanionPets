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

/** Opt-in verification against the private assets checkout without copying its data into this repo. */
@EnabledIfSystemProperty(named = "companionpets.tfdev-config", matches = ".+")
class TfDevConfigurationTest {
    @Test void preparedConfigLoadsEveryCapturedTypeAndItsExistingModels() throws Exception {
        MockBukkit.mock();
        try {
            Path path = Path.of(System.getProperty("companionpets.tfdev-config"));
            var current = new YamlConfiguration(); current.load(path.toFile());
            var original = new YamlConfiguration(); original.load(path.getParent().resolve("snapshots/2026-10-04-config.yml").toFile());
            var config = CompanionConfig.load(MockBukkit.createMockPlugin(), current);
            assertEquals(original.getConfigurationSection("pets").getKeys(false), config.types().keySet());
            assertEquals(14, config.types().size());
            for (String root : Set.of("care", "play", "training", "custom-tricks", "limits", "moments", "social", "items", "orders"))
                assertEquals(values(original.getConfigurationSection(root)), values(current.getConfigurationSection(root)), root);
            Path assets = path.getParent().getParent().getParent();
            for (var pet : config.types().values()) {
                var saved = original.getConfigurationSection("pets." + pet.id());
                assertEquals(ItemRef.parse(saved.getString("egg")), pet.egg(), pet.id() + " egg");
                assertFalse(pet.behaves(PetBehavior.ROAM));
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
                if (pet.behaves(PetBehavior.BELLY_RUB)) assertFalse(report.disabledBehaviors().containsKey(PetBehavior.BELLY_RUB), pet.id());
                if (pet.behaves(PetBehavior.TOY_TAIL_WAG)) assertTrue(tail, pet.id() + " tail");
                for (Trick trick : pet.tricks()) {
                    var custom = config.customTrick(trick);
                    if (custom != null)
                        assertTrue(!custom.fallbackText().isBlank() || clips.contains(custom.animation()), pet.id() + ": " + trick.name());
                }
            }
            assertEquals(EntityType.WOLF, config.type("fox").entity());
            assertEquals(EntityType.WOLF, config.type("fox_custom").entity());
            assertEquals("fox", config.type("fox_custom").sounds().preset());
            var fox = config.type("fox");
            var foxCustom = config.type("fox_custom");
            var foxBehaviors = new java.util.HashSet<>(PetBehavior.read(original.getConfigurationSection("pets.fox"),
                    EntityType.FOX, java.util.logging.Logger.getAnonymousLogger()));
            foxBehaviors.remove(PetBehavior.ROAM);
            foxBehaviors.addAll(Set.of(PetBehavior.SOCIAL_GREETING, PetBehavior.SOCIAL_VOCALIZING, PetBehavior.CAT_PLAY));
            assertEquals(foxBehaviors, fox.behaviors(), "Keep captured fox actions, social reactions and favorite-toy side steps");
            assertFalse(fox.behaves(PetBehavior.TOY_WIGGLE));
            assertEquals(fox.behaviors(), foxCustom.behaviors());
            assertEquals(fox.appearance(), foxCustom.appearance());
            assertEquals(fox.sounds(), foxCustom.sounds());
            assertEquals(fox.sexMode(), foxCustom.sexMode());
            assertEquals(fox.tricks(), foxCustom.tricks());
            assertEquals(fox.defaultTricks(), foxCustom.defaultTricks());
            assertEquals(fox.items(), foxCustom.items());
            assertNotEquals(fox.egg(), foxCustom.egg());
            assertEquals(EntityType.WOLF, config.type("frog").entity());
            assertEquals("frog", config.type("frog").sounds().preset());
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
