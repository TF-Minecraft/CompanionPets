package net.tfminecraft.companionpets.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.tfminecraft.companionpets.chat.SpokenOrder;
import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.PetOrder;
import net.tfminecraft.companionpets.pet.PetSex;
import net.tfminecraft.companionpets.text.PetTexts;

class LocomotionAndOrdersTest {
    @Test
    void locomotionPrefersDistressOverThePlayerOrder() {
        assertEquals(Locomotion.Mode.LIE, choose(Illness.WEAKENED, 10, 100, 100, Activity.NONE, false, false, PetOrder.FOLLOW, false));
        assertEquals(Locomotion.Mode.LIE, choose(Illness.NONE, 100, 10, 100, Activity.NONE, false, false, PetOrder.FOLLOW, false));
        assertEquals(Locomotion.Mode.SIT, choose(Illness.NONE, 100, 100, 10, Activity.NONE, false, false, PetOrder.FOLLOW, false));
        assertEquals(Locomotion.Mode.SIT, choose(Illness.NONE, 100, 100, 100, Activity.NONE, false, false, PetOrder.SIT, false));
        assertEquals(Locomotion.Mode.STAY, choose(Illness.NONE, 100, 100, 100, Activity.NONE, false, false, PetOrder.FOLLOW, true));
        assertEquals(Locomotion.Mode.FOLLOW, choose(Illness.NONE, 100, 100, 100, Activity.NONE, false, false, PetOrder.FOLLOW, false));
        assertEquals(Locomotion.Mode.SLEEP, choose(Illness.NONE, 100, 10, 100, Activity.SLEEPING, false, false, PetOrder.FOLLOW, false));
        assertEquals(Locomotion.Mode.FETCH, choose(Illness.NONE, 100, 100, 100, Activity.PLAYING, true, false, PetOrder.FOLLOW, false));
    }

    @Test
    void highBondFollowsCloserAndSicknessSlowsTheWalk() {
        assertTrue(Locomotion.followDistance(100) < Locomotion.followDistance(0));
        assertTrue(Locomotion.speed(Illness.SICK, 50, 100, false) < Locomotion.speed(Illness.NONE, 50, 100, false));
        assertTrue(Locomotion.speed(Illness.NONE, 50, 100, true) > Locomotion.speed(Illness.NONE, 50, 100, false));
    }

    @Test
    void aChatLineIsTheWholeOrder() {
        assertTrue(SpokenOrder.matches("sit", "sit"));
        assertTrue(SpokenOrder.matches("  SIT! ", "sit"));
        assertTrue(SpokenOrder.matches("Roll   over.", "roll over"));
        assertFalse(SpokenOrder.matches("please sit", "sit"));
        assertEquals("Luna's stomach is rumbling", PetTexts.lowNeed("Luna", PetSex.FEMALE, net.tfminecraft.companionpets.pet.Need.HUNGER));
    }

    private static Locomotion.Mode choose(
            Illness illness,
            double health,
            double energy,
            double hunger,
            Activity activity,
            boolean fetching,
            boolean forcedSit,
            PetOrder order,
            boolean staying) {
        return Locomotion.choose(illness, health, energy, hunger, activity, fetching, forcedSit, order, staying);
    }
}
