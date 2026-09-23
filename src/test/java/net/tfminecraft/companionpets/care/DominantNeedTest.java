package net.tfminecraft.companionpets.care;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetSex;

class DominantNeedTest {
    @Test
    void illnessComesBeforeAnyNeed() {
        Pet pet = pet();
        pet.illness(Illness.SICK);
        pet.need(Need.HUNGER, 0);
        assertNull(DominantNeed.select(pet));
    }

    @Test
    void criticalOutranksAnEarlierLowNeed() {
        Pet pet = pet();
        pet.need(Need.HUNGER, 40);
        pet.need(Need.ENERGY, 10);
        assertEquals(Need.ENERGY, DominantNeed.select(pet));
    }

    @Test
    void sameBandUsesHungerThenEnergyThenCleanlinessThenMood() {
        Pet pet = pet();
        pet.need(Need.HUNGER, 40);
        pet.need(Need.MOOD, 30);
        assertEquals(Need.HUNGER, DominantNeed.select(pet));
    }

    private static Pet pet() {
        return new Pet(UUID.randomUUID(), UUID.randomUUID(), "wolf", "Luna", PetSex.FEMALE);
    }
}
