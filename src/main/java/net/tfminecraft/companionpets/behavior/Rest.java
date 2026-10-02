package net.tfminecraft.companionpets.behavior;

import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.NeedBand;

public final class Rest {
    private Rest() {
    }

    public static boolean recoversEnergy(Locomotion.Mode mode) {
        return mode == Locomotion.Mode.SIT || mode == Locomotion.Mode.LIE || mode == Locomotion.Mode.SLEEP;
    }

    public static boolean shouldLieDown(double energy, Activity activity, boolean fetching, long now, long notBefore) {
        return NeedBand.of(energy) == NeedBand.CRITICAL
                && activity == Activity.NONE
                && !fetching
                && now >= notBefore;
    }
}
