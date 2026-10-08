package net.tfminecraft.companionpets.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import net.tfminecraft.companionpets.item.ItemRef;
import net.tfminecraft.companionpets.pet.SexMode;
import net.tfminecraft.companionpets.pet.Trick;
import net.tfminecraft.companionpets.visual.PetAnimation;

/** Load the actual commented examples so documentation cannot silently drift from the parser. */
class ConfigExamplesTest {
    @BeforeEach void setup() { MockBukkit.mock(); }
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    private String bundled() throws Exception {
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private CompanionConfig loadWithoutWarnings(String text) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        var plugin = MockBukkit.createMockPlugin();
        List<String> warnings = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) warnings.add(record.getMessage());
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        plugin.getLogger().addHandler(handler);
        try {
            var config = CompanionConfig.load(plugin, yaml);
            assertEquals(List.of(), warnings);
            return config;
        } finally { plugin.getLogger().removeHandler(handler); }
    }

    private String uncomment(String text, String first, String last) {
        var lines = new ArrayList<>(text.lines().toList());
        int start = -1, end = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).stripLeading().startsWith("# " + first)) start = i;
            if (lines.get(i).stripLeading().startsWith("# " + last)) end = i;
        }
        assertTrue(start >= 0 && end >= start, "Missing example: " + first);
        for (int i = start; i <= end; i++) lines.set(i, lines.get(i).replaceFirst("^(\\s*)# ", "$1"));
        return String.join("\n", lines);
    }

    @Test void examplesStayInactiveInTheShippedConfig() throws Exception {
        var config = loadWithoutWarnings(bundled());
        assertEquals(java.util.Set.of("wolf", "cat"), config.types().keySet());
        assertNull(config.customTrick(Trick.valueOf("wave")));
        assertEquals(config.items(), config.type("wolf").items());
        assertEquals(List.of(Trick.FOLLOW), config.type("wolf").defaultTricks());
    }

    @Test void uncommentedExamplesResolveItemsTricksModelsAndVoicesWithoutWarnings() throws Exception {
        String text = uncomment(bundled(), "rabbit:", "  egg: RABBIT_SPAWN_EGG");
        text = uncomment(text, "fox:", "    eat: false");
        text = uncomment(text.replace("species: {}", "species:"), "dog:", "  voice: wolf");
        text = uncomment(text.replace("custom-tricks: {}", "custom-tricks:"), "wave:", "  at: [0, 1]");
        var config = loadWithoutWarnings(text);
        assertEquals(java.util.Set.of("wolf", "cat", "rabbit", "fox"), config.types().keySet());
        assertEquals(EntityType.WOLF, config.type("rabbit").entity());
        assertEquals("rabbit", config.type("rabbit").appearance().model());
        assertEquals(List.of(ItemRef.vanilla(Material.COOKED_BEEF)), config.type("wolf").treats());
        assertEquals(config.items().foods(), config.type("wolf").foods());
        assertEquals(List.of(Trick.FOLLOW, Trick.SIT), config.type("wolf").defaultTricks());
        assertFalse(config.type("wolf").tricks().contains(Trick.SPEAK));
        var fox = config.type("fox");
        assertEquals(EntityType.WOLF, fox.entity());
        assertEquals(BehaviorProfile.CAT, fox.behavior());
        assertEquals(SexMode.CHOOSE, fox.sexMode());
        assertEquals(12002, fox.eggCustomModelData());
        assertEquals(List.of(ItemRef.vanilla(Material.SALMON)), fox.treats());
        assertEquals(java.util.Map.of(ItemRef.vanilla(Material.COOKED_CHICKEN), 35.0), fox.foods());
        assertEquals(List.of(Trick.FOLLOW), fox.defaultTricks());
        assertTrue(fox.tricks().contains(Trick.valueOf("wave")));
        assertFalse(fox.tricks().contains(Trick.JUMP));
        assertFalse(fox.tricks().contains(Trick.SPEAK));
        assertEquals("wave", fox.appearance().animations().get(PetAnimation.PAW).name());
        assertFalse(fox.appearance().animations().containsKey(PetAnimation.BELLY_UP));
        assertEquals(1.2f, fox.sounds().pitch());
        assertEquals(1.32f, fox.sounds().cue(PetSounds.Event.TOY).pitch(), .0001f);
        assertEquals(3, fox.sounds().cue(PetSounds.Event.TOY).minIntervalSeconds());
        assertNull(fox.sounds().cue(PetSounds.Event.EAT));
        var wave = config.customTrick(Trick.valueOf("wave"));
        assertEquals("{pet} waves to {owner}", wave.fallbackText());
        assertEquals(2, wave.duration());
        assertEquals(List.of(0.0, 1.0), wave.at());
        assertEquals(1.1f, wave.sound().pitch());
    }

    @Test void furnitureExampleLoadsWithTheDocumentedMatchingKennelItem() throws Exception {
        String text = uncomment(bundled(), "kennel-furniture:", "kennel-furniture:");
        text = text.replace("kennel: BARREL", "kennel: itemsadder:companions:pet_house");
        var config = loadWithoutWarnings(text);
        assertEquals(ItemRef.parse("itemsadder:companions:pet_house"), config.kennel());
        assertEquals("companions:pet_house", config.kennelFurniture());
        assertEquals(Material.BARREL, config.kennelBlock());
    }
}
