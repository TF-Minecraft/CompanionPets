package net.tfminecraft.companionpets.visual;

/** Visual poses and actions, independent of a pet's species or model ID. */
public enum PetAnimation {
    IDLE(true), WALK(true), RUN(true), CROUCH(true), JUMP(true), FALL(true), SWIM(true), SIT(true),
    LIE(true), SLEEP(true), FLY(true), HOVER(true),
    ATTACK(false), HURT(false), DEATH(false), HEAD_TILT(false),
    SPAWN(false), EAT(false), SPEAK(false), SHAKE(false),
    PAW(false), SPIN(false), PET(false),
    LIE_BACK(false), BELLY_UP(true), GET_UP(false);

    private final boolean pose;

    PetAnimation(boolean pose) {
        this.pose = pose;
    }

    public boolean pose() { return pose; }

    public boolean holdsMovement() {
        return this == PAW || this == SPIN || this == EAT || this == PET;
    }

    public PetAnimation fallback() {
        return switch (this) {
            case RUN, CROUCH, SWIM -> WALK;
            case JUMP, FALL -> IDLE;
            case SLEEP -> LIE;
            case LIE -> SIT;
            case FLY -> WALK;
            case HOVER -> IDLE;
            default -> null;
        };
    }
}
