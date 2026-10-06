package net.tfminecraft.companionpets.visual;

import net.tfminecraft.companionpets.behavior.Locomotion;

/** Postures the plugin overlays. Idle, walk, jump and fly come from ModelEngine itself. */
public final class VisualPose {
    private VisualPose() { }

    public static PetAnimation select(Locomotion.Mode mode, boolean sitting, boolean water) {
        if (water) return PetAnimation.SWIM;
        if (mode == Locomotion.Mode.SLEEP) return PetAnimation.SLEEP;
        if (mode == Locomotion.Mode.LIE) return PetAnimation.LIE;
        if (mode == Locomotion.Mode.STAY) return PetAnimation.STAND;
        if (mode == Locomotion.Mode.SIT || sitting) return PetAnimation.SIT;
        return PetAnimation.IDLE;
    }
}
