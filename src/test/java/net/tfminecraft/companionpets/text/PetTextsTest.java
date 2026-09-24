package net.tfminecraft.companionpets.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PetTextsTest {
    @Test
    void ageUsesTheTimeSinceBirth() {
        long born = 1_000_000L;
        assertEquals("Unknown", PetTexts.age(0L, born));
        assertEquals("Newborn", PetTexts.age(born, born + 30_000L));
        assertEquals("1 minute", PetTexts.age(born, born + 60_000L));
        assertEquals("12 minutes", PetTexts.age(born, born + 12 * 60_000L));
        assertEquals("1 hour", PetTexts.age(born, born + 60 * 60_000L));
        assertEquals("2 hours and 5 minutes", PetTexts.age(born, born + (2 * 60 + 5) * 60_000L));
        assertEquals("1 day", PetTexts.age(born, born + 24 * 60 * 60_000L));
        assertEquals("3 days and 4 hours", PetTexts.age(born, born + (3 * 24 + 4) * 60 * 60_000L));
    }

    @Test
    void speciesAndItemNamesReadNaturally() {
        assertEquals("Wolf", PetTexts.speciesName("wolf"));
        assertEquals("Snow fox", PetTexts.speciesName("snow_fox"));
        assertEquals("Pet", PetTexts.speciesName(""));
        assertEquals("glow berries", PetTexts.itemName("GLOW_BERRIES"));
    }
}
