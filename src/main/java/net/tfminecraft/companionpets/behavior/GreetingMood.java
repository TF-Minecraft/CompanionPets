package net.tfminecraft.companionpets.behavior;

import net.tfminecraft.companionpets.pet.*;

/** Enthusiasm is limited by physical condition; affection cannot override illness. */
public record GreetingMood(double intensity, boolean mobile, boolean jumps) {
    public static GreetingMood of(Pet pet, double relationship) {
        double energy = pet.need(Need.ENERGY), health = pet.need(Need.HEALTH), hunger = pet.need(Need.HUNGER);
        boolean mobile = pet.illness() == Illness.NONE && energy >= 25 && health >= 50 && hunger >= 25;
        double strength = (0.35 + 0.65 * Math.max(0, Math.min(100, relationship)) / 100)
                * Math.min(health / 100, 0.25 + 0.75 * energy / 100)
                * (0.5 + 0.5 * hunger / 100);
        strength *= switch (pet.personality()) {
            case PLAYFUL -> 1.1;
            case SHY -> 0.75;
            case GRUMPY -> 0.85;
            default -> 1;
        };
        if (!mobile) strength *= 0.15;
        strength = Math.max(0, Math.min(1, strength));
        return new GreetingMood(strength, mobile, mobile && strength >= 0.65 && energy >= 60 && health >= 70);
    }
}
