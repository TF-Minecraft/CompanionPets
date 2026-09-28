package net.tfminecraft.companionpets.config;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import net.tfminecraft.companionpets.pet.*;
import java.util.UUID;
import java.util.logging.Logger;

class CustomTrickTest {
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
