package net.tfminecraft.companionpets.visual;

import net.tfminecraft.companionpets.behavior.Locomotion;

public final class VisualPose {
    private VisualPose() { }

    public static PetAnimation select(Locomotion.Mode mode, boolean sitting, boolean water,
                                     boolean grounded, boolean flying, double verticalSpeed,
                                     double speed, double runSpeed, PetAnimation previous) {
        if (water) return PetAnimation.SWIM;
        if (!grounded) {
            if (flying) return speed > 0.015 || Math.abs(verticalSpeed) > 0.03 ? PetAnimation.FLY : PetAnimation.HOVER;
            return verticalSpeed > 0.03 ? PetAnimation.JUMP : PetAnimation.FALL;
        }
        if (mode == Locomotion.Mode.SLEEP) return PetAnimation.SLEEP;
        if (mode == Locomotion.Mode.LIE) return PetAnimation.LIE;
        if (mode == Locomotion.Mode.SIT || sitting) return PetAnimation.SIT;
        if (speed <= 0.015) return PetAnimation.IDLE;
        double threshold = previous == PetAnimation.RUN ? runSpeed * 0.85 : runSpeed;
        return speed >= threshold ? PetAnimation.RUN : PetAnimation.WALK;
    }
}
