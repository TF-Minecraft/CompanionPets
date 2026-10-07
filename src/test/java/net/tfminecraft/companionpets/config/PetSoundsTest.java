package net.tfminecraft.companionpets.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import net.tfminecraft.companionpets.config.PetSounds.Event;

class PetSoundsTest {
    private final Logger logger = Logger.getLogger("pet-sounds-test");
    @BeforeEach void setup() { MockBukkit.mock(); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    private PetSounds read(String yaml) throws Exception {
        var config = new YamlConfiguration(); config.loadFromString(yaml);
        return PetSounds.read(config, EntityType.WOLF, logger);
    }

    @Test void omittedSoundsKeepVanillaAudioAndSpeciesDefaults() throws Exception {
        var sounds = read(""); assertTrue(sounds.nativeSounds());
        assertEquals(0, sounds.ambientIntervalSeconds());
        assertEquals(List.of("minecraft:entity.wolf.ambient"), sounds.cue(Event.GREETING).sounds());
    }

    @Test void individualOverridesAcceptVanillaNamesAndCustomResourcePackKeys() throws Exception {
        var sounds = read("""
                sounds:
                  preset: cat
                  ambient-interval-seconds: 12
                  greeting:
                    sounds: [ENTITY_CAT_AMBIENT, 'tfmc:pet.happy']
                    volume: 0.4
                    pitch: 1.2
                    min-interval-seconds: 3
                  toy: false
                  protest: []
                """);
        assertFalse(sounds.nativeSounds()); assertEquals(12, sounds.ambientIntervalSeconds());
        var cue = sounds.cue(Event.GREETING);
        assertEquals(List.of("minecraft:entity.cat.ambient", "tfmc:pet.happy"), cue.sounds());
        assertEquals(.4f, cue.volume()); assertEquals(1.2f, cue.pitch()); assertEquals(3, cue.minIntervalSeconds());
        assertNull(sounds.cue(Event.TOY)); assertNull(sounds.cue(Event.PROTEST));
        assertEquals(List.of("minecraft:entity.cat.hurt"), sounds.cue(Event.HURT).sounds());
    }

    @Test void silenceAndCustomOnlyProfilesDoNotLeakBaseSounds() throws Exception {
        assertTrue(read("sounds: false").cues().isEmpty());
        var sounds = read("sounds: {preset: none, greeting: 'my_pack:hello', ambient-interval-seconds: 0}");
        assertEquals(1, sounds.cues().size()); assertNull(sounds.cue(Event.AMBIENT));
        assertFalse(sounds.nativeSounds()); assertEquals(0, sounds.ambientIntervalSeconds());
    }

    @Test void malformedNamesAndNumbersCannotTriggerAnotherSpeciesOrInvalidPlayback() throws Exception {
        var sounds = read("""
                sounds:
                  ambient-interval-seconds: .nan
                  greeting: [NOT_A_SOUND, 'bad:key with spaces', 42]
                  toy: {sounds: [ENTITY_CAT_AMBIENT], volume: -1, pitch: 99, min-interval-seconds: .inf}
                """);
        assertNull(sounds.cue(Event.GREETING)); assertEquals(25, sounds.ambientIntervalSeconds());
        assertEquals(.4f, sounds.cue(Event.TOY).volume()); assertEquals(1.05f, sounds.cue(Event.TOY).pitch());
        assertEquals(0, sounds.cue(Event.TOY).minIntervalSeconds());
        assertTrue(read("sounds: garbage").cues().isEmpty());
    }

    @Test void onlyWolfAndCatConfigurationsCanSupplyNativeFollowing() throws Exception {
        var yaml = new YamlConfiguration(); yaml.loadFromString("""
                pets:
                  dog: {entity: WOLF, egg: WOLF_SPAWN_EGG}
                  cat: {entity: CAT, egg: CAT_SPAWN_EGG}
                  frog: {entity: FROG, egg: FROG_SPAWN_EGG}
                  fox: {entity: FOX, egg: FOX_SPAWN_EGG}
                  parrot: {entity: PARROT, egg: PARROT_SPAWN_EGG}
                  horse: {entity: HORSE, egg: HORSE_SPAWN_EGG}
                """);
        var config = CompanionConfig.load(MockBukkit.createMockPlugin(), yaml);
        assertEquals(java.util.Set.of("dog", "cat"), config.types().keySet());
        assertFalse(config.type("dog").nativeCombat());
    }

    @Test void rabbitAndPigVoicesGenerateOnlyRegisteredVanillaSounds() throws Exception {
        var yaml = new YamlConfiguration(); yaml.loadFromString("""
                pets:
                  rabbit: {species: dog, voice: rabbit, egg: RABBIT_SPAWN_EGG}
                  pig: {species: cat, voice: pig, egg: PIG_SPAWN_EGG}
                """);
        var config = CompanionConfig.load(MockBukkit.createMockPlugin(), yaml);
        for (String entity : List.of("rabbit", "pig")) {
            var voice = config.type(entity).sounds();
            assertFalse(voice.nativeSounds());
            assertEquals(25, voice.ambientIntervalSeconds());
            for (Event event : Event.values()) {
                String expected = switch (event) {
                    case HURT -> "minecraft:entity." + entity + ".hurt";
                    case DEATH -> "minecraft:entity." + entity + ".death";
                    case EAT -> "minecraft:entity.generic.eat";
                    default -> "minecraft:entity." + entity + ".ambient";
                };
                assertEquals(List.of(expected), voice.cue(event).sounds());
                assertNotNull(org.bukkit.Registry.SOUNDS.get(org.bukkit.NamespacedKey.fromString(expected)));
            }
        }
        var cat = read("sounds: {preset: cat}");
        assertEquals(List.of("minecraft:entity.cat.purr"), cat.cue(Event.HAPPY_QUIET).sounds());
    }

    @Test void missingGeneratedSoundsFallBackToAmbientOrDisableTheVoiceWithWarnings() throws Exception {
        var warnings = new java.util.ArrayList<String>();
        var capture = Logger.getAnonymousLogger(); capture.setUseParentHandlers(false);
        capture.addHandler(new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { warnings.add(record.getMessage()); }
            public void flush() { }
            public void close() { }
        });
        var fallback = PetSounds.defaults("pig", capture, key ->
                !key.endsWith(".hurt") && org.bukkit.Registry.SOUNDS.get(org.bukkit.NamespacedKey.fromString(key)) != null);
        assertEquals(List.of("minecraft:entity.pig.ambient"), fallback.get(Event.HURT).sounds());
        assertTrue(warnings.stream().anyMatch(message -> message.contains("pig.hurt") && message.contains("using")));
        warnings.clear();
        var noAmbient = PetSounds.defaults("pig", capture, key ->
                !key.endsWith(".ambient") && org.bukkit.Registry.SOUNDS.get(org.bukkit.NamespacedKey.fromString(key)) != null);
        assertFalse(noAmbient.containsKey(Event.GREETING));
        assertTrue(noAmbient.containsKey(Event.HURT));
        assertTrue(warnings.stream().anyMatch(message -> message.contains("disabling affected events")));
        warnings.clear();
        assertTrue(PetSounds.defaults("pig", capture, key -> key.equals("minecraft:entity.generic.eat")).isEmpty());
        assertTrue(warnings.stream().anyMatch(message -> message.contains("no valid entity sounds")));
        warnings.clear();
        var silentEntity = new YamlConfiguration(); silentEntity.loadFromString("sounds: {preset: armor_stand}");
        assertTrue(PetSounds.read(silentEntity, EntityType.WOLF, capture).cues().isEmpty());
        assertTrue(warnings.stream().anyMatch(message -> message.contains("no valid entity sounds")));
        warnings.clear();
        var yaml = new YamlConfiguration(); yaml.loadFromString("sounds: {preset: not_an_entity}");
        assertTrue(PetSounds.read(yaml, EntityType.WOLF, capture).cues().isEmpty());
        assertTrue(warnings.stream().anyMatch(message -> message.contains("Unknown vanilla entity")));
        assertTrue(read("sounds: {preset: none, greeting: 'minecraft:entity.not_an_entity.ambient'}").cues().isEmpty());
    }
}
