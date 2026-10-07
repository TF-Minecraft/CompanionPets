package net.tfminecraft.companionpets.config;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import net.tfminecraft.companionpets.pet.*;
import java.util.UUID;
import java.util.logging.Logger;

class CustomTrickTest {
    @Test void soundFormsAndTimesUseVoiceValidation() throws Exception {
        org.mockbukkit.mockbukkit.MockBukkit.mock();
        try {
            var yaml = new YamlConfiguration(); yaml.loadFromString("""
                first: {fallback-text: Hello, sound: ENTITY_FROG_AMBIENT}
                list: {animation: croak, sound: [ENTITY_FROG_AMBIENT, ENTITY_FROG_TONGUE], at: [0.54, 2.33]}
                mapped: {animation: croak, sound: {sounds: ENTITY_FROG_AMBIENT, volume: 0.4, pitch: 1.3}, at: 1}
                invalid: {animation: croak, sound: minecraft:entity.nonexistent.ambient}
                invalidtime: {animation: croak, at: -1}
                """);
            var warnings = new java.util.ArrayList<String>();
            Logger logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
            logger.addHandler(new java.util.logging.Handler() {
                public void publish(java.util.logging.LogRecord record) { warnings.add(record.getMessage()); }
                public void flush() { } public void close() { }
            });
            var defs = CustomTrick.read(yaml, logger);
            assertEquals(java.util.List.of(0.0), defs.get(Trick.valueOf("first")).at());
            assertEquals(2, defs.get(Trick.valueOf("list")).sound().sounds().size());
            assertEquals(java.util.List.of(.54, 2.33), defs.get(Trick.valueOf("list")).at());
            assertEquals(.4f, defs.get(Trick.valueOf("mapped")).sound().volume());
            assertEquals(1.3f, defs.get(Trick.valueOf("mapped")).sound().pitch());
            assertNull(defs.get(Trick.valueOf("invalid")).sound());
            assertFalse(defs.containsKey(Trick.valueOf("invalidtime")));
            assertTrue(warnings.stream().anyMatch(s -> s.contains("nonexistent")));
        } finally { org.mockbukkit.mockbukkit.MockBukkit.unmock(); }
    }
    @Test void invalidEntriesDoNotDiscardValidDefinitions() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
            salute:
              display-name: Saludar
              animation: salute
              fallback-text: '{pet} saluda'
            roll:
              animation: roll
            hello:
              fallback-text: Hola
            broken: {animation: ''}
            sit: {animation: sit}
            badtime: {animation: test, duration: -1}
            badtext: {animation: [one, two]}
            """);
        var definitions = CustomTrick.read(yaml, Logger.getAnonymousLogger());
        assertEquals(3, definitions.size());
        assertEquals("Saludar", definitions.get(Trick.valueOf("SALUTE")).displayName());
        assertEquals("", definitions.get(Trick.valueOf("roll")).fallbackText());
    }

    @Test void customIdsKeepWordsAndProgressWithoutAnActiveDefinition() {
        Pet pet = new Pet(UUID.randomUUID(), UUID.randomUUID(), "beagle", "Dog", PetSex.MALE);
        Trick original = Trick.valueOf("salute");
        pet.bindWord("hello", original); pet.progress(original, 75);
        Trick restored = Trick.valueOf(original.name());
        assertEquals(restored, pet.trickFor("hello"));
        assertEquals(75, pet.progress(restored));
        assertTrue(pet.progressView().containsKey(restored));
        assertSame(Trick.SIT, Trick.valueOf("sit"));
    }
}
