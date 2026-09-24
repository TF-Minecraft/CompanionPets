package net.tfminecraft.companionpets.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.tfminecraft.companionpets.pet.PetSex;

class NamesTest {
    @Test
    void answersIgnoreCaseSpacingAndPunctuation() {
        assertTrue(Names.confirms("  YES! "));
        assertTrue(Names.confirms("Okay."));
        assertTrue(Names.cancels("Cancel"));
        assertTrue(Names.cancels("no."));
        assertEquals(PetSex.FEMALE, Names.sex(" Female "));
        assertEquals(PetSex.MALE, Names.sex("BOY!"));
    }

    @Test
    void spanishAnswersAreNotAccepted() {
        assertFalse(Names.confirms("sí"));
        assertFalse(Names.cancels("cancelar"));
        assertNull(Names.sex("hembra"));
    }
}
