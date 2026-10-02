package net.tfminecraft.companionpets.care;

import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;

public final class HealthRecovery {
    private HealthRecovery() { }

    /** Reward actual improvements, so feeding a full pet or brushing a clean one cannot heal it. */
    public static void improve(Pet pet, Need need, double value) {
        double before = pet.need(need);
        pet.need(need, value);
        if (!pet.dead() && (need == Need.HUNGER || need == Need.CLEANLINESS)) {
            pet.need(Need.HEALTH, pet.need(Need.HEALTH) + Math.max(0, pet.need(need) - before) * 0.25);
        }
    }

    public static boolean physicalNeedsCritical(Pet pet) {
        return pet.need(Need.HUNGER) < 25 || pet.need(Need.ENERGY) < 25 || pet.need(Need.CLEANLINESS) < 25;
    }

    public static boolean canRecover(Pet pet, boolean resting) {
        return !pet.dead() && pet.need(Need.HUNGER) >= 25 && pet.need(Need.CLEANLINESS) >= 25
                && (resting || pet.need(Need.ENERGY) >= 25);
    }

    public static void recover(Pet pet, double gain) {
        pet.need(Need.HEALTH, pet.need(Need.HEALTH) + gain);
        if (pet.need(Need.HEALTH) >= 100) {
            pet.illness(Illness.NONE);
            pet.treated(false);
            pet.criticalMillis(0);
        }
    }
}
