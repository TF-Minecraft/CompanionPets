package net.tfminecraft.companionpets.behavior;

import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.PetOrder;

public final class Locomotion {
    private Locomotion() {
    }

    public enum Mode {
        FOLLOW,
        SIT,
        LIE,
        STAY,
        PLAY,
        FETCH,
        SLEEP
    }

    public static Mode choose(
            Illness illness,
            double health,
            double energy,
            double hunger,
            Activity activity,
            boolean fetching,
            boolean forcedSit,
            PetOrder order,
            boolean staying) {
        if (illness == Illness.WEAKENED || health <= 0.0) {
            return Mode.LIE;
        }
        if (activity == Activity.SLEEPING) {
            return Mode.SLEEP;
        }
        if (activity == Activity.PLAYING && fetching) {
            return Mode.FETCH;
        }
        if (activity == Activity.PLAYING) {
            return Mode.PLAY;
        }
        if (forcedSit) {
            return Mode.SIT;
        }
        if (energy < 25.0) {
            return Mode.LIE;
        }
        if (hunger < 25.0) {
            return Mode.SIT;
        }
        if (order == PetOrder.SIT) {
            return Mode.SIT;
        }
        if (staying) {
            return Mode.STAY;
        }
        return Mode.FOLLOW;
    }

    public static double speed(Illness illness, double bond, double cleanliness, boolean favoriteFetch) {
        double speed = bond >= 70.0 ? 1.25 : bond <= 30.0 ? 0.95 : 1.1;
        if (illness == Illness.UNWELL || illness == Illness.SICK) {
            speed *= 0.65;
        }
        if (cleanliness < 25.0) {
            speed *= 0.85;
        }
        if (favoriteFetch) {
            speed *= 1.35;
        }
        return speed;
    }

    public static double followDistance(double bond) {
        return 2.5 + (100.0 - bond) / 40.0;
    }
}
