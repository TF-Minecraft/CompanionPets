package net.tfminecraft.companionpets.behavior;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import net.tfminecraft.companionpets.pet.*;

class GreetingMoodTest {
    private Pet pet() {
        var pet = new Pet(UUID.randomUUID(), UUID.randomUUID(), "wolf", "Toby", PetSex.MALE);
        pet.personality(PetPersonality.FRIENDLY); return pet;
    }
    @Test void relationshipAndEnergyChangeEnthusiasmButIllnessAlwaysWins() {
        var pet = pet(); var eager = GreetingMood.of(pet, 100);
        assertTrue(eager.intensity() > GreetingMood.of(pet, 0).intensity()); assertTrue(eager.jumps());
        pet.need(Need.ENERGY, 30); var tired = GreetingMood.of(pet, 100);
        assertTrue(tired.mobile()); assertFalse(tired.jumps()); assertTrue(tired.intensity() < eager.intensity());
        pet.need(Need.ENERGY, 100); pet.illness(Illness.SICK);
        assertFalse(GreetingMood.of(pet, 100).mobile()); assertFalse(GreetingMood.of(pet, 100).jumps());
        assertTrue(GreetingMood.of(pet, 100).intensity() < 0.2);
    }
    @Test void LowPhysicalNeedsPreventAnExcitedApproachAndShyPetsAreGentler() {
        var pet = pet(); double friendly = GreetingMood.of(pet, 100).intensity();
        pet.personality(PetPersonality.SHY); assertTrue(GreetingMood.of(pet, 100).intensity() < friendly);
        for (Need need : java.util.List.of(Need.HEALTH, Need.HUNGER, Need.ENERGY)) {
            pet.need(need, 10); assertFalse(GreetingMood.of(pet, 100).mobile()); pet.need(need, 100);
        }
    }
}
