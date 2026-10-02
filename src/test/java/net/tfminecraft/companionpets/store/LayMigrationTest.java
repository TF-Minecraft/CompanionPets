package net.tfminecraft.companionpets.store;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.Trick;
import net.tfminecraft.companionpets.text.PetTexts;

class LayMigrationTest {
    @Test void removedSpinDoesNotReturnFromLegacyWordsOrProgress() throws Exception {
        var read = PetStore.class.getDeclaredMethod("readPet", UUID.class, ConfigurationSection.class); read.setAccessible(true);
        var yaml = new YamlConfiguration();
        yaml.loadFromString("owner: " + UUID.randomUUID() + "\ntype: wolf\nwords:\n  - {word: spin, trick: SPIN}\n  - {word: sit, trick: SIT}\nprogress: {SPIN: 100, SIT: 80}\n");
        Pet pet = (Pet) read.invoke(null, UUID.randomUUID(), yaml);
        assertNull(pet.trickFor("spin")); assertEquals(0, pet.progress(Trick.SPIN)); assertEquals(80, pet.progress(Trick.SIT));
        assertFalse(java.util.List.of(Trick.values()).contains(Trick.SPIN));
    }
    @Test
    void layIsTheBuiltInTrickAndSleepRemainsAConfigurationAlias() {
        assertSame(Trick.LAY, Trick.valueOf("lay"));
        assertSame(Trick.LAY, Trick.valueOf("sleep"));
        assertEquals(Trick.Kind.LAY, new Trick("sleep").kind());
        assertEquals("Lay", PetTexts.trickName(Trick.LAY));
    }

    @Test
    void savedSleepWordsAndProgressLoadAsLayWithoutLosingTheHigherProgress() throws Exception {
        var read = PetStore.class.getDeclaredMethod("readPet", UUID.class, ConfigurationSection.class);
        read.setAccessible(true);
        for (String progress : java.util.List.of("SLEEP: 80\n  LAY: 20", "LAY: 20\n  SLEEP: 80")) {
            var yaml = new YamlConfiguration();
            yaml.loadFromString("owner: " + UUID.randomUUID() + "\n"
                    + "type: wolf\nwords:\n  - {word: rest, trick: SLEEP}\n  - {word: lay, trick: LAY}\n"
                    + "progress:\n  " + progress + "\n");
            Pet pet = (Pet) read.invoke(null, UUID.randomUUID(), yaml);
            assertEquals(Trick.LAY, pet.trickFor("rest"));
            assertEquals(Trick.LAY, pet.trickFor("lay"));
            assertEquals(80, pet.progress(Trick.LAY));
            assertEquals(java.util.Set.of(Trick.LAY), pet.progressView().keySet());
        }
    }
}
