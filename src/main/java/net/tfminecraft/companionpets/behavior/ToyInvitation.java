package net.tfminecraft.companionpets.behavior;

import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetPersonality;
import net.tfminecraft.companionpets.pet.RelationshipMemory;

/** Personality and familiarity shape an invitation without excluding shy pets. */
public record ToyInvitation(double chance, double attentionFactor, double extraDistance, boolean exuberant) {
    public static ToyInvitation of(Pet pet, double trust, boolean favorite) {
        double[] chances = switch (pet.personality()) {
            case PLAYFUL -> new double[]{.60, .80, .95};
            case FRIENDLY -> new double[]{.45, .70, .90};
            case SHY -> new double[]{.10, .40, .85};
            case TERRITORIAL -> new double[]{.12, .45, .80};
            case GRUMPY -> new double[]{.08, .25, .60};
        };
        double bounded = Math.max(0, Math.min(40, trust));
        double chance = bounded < RelationshipMemory.FAMILIAR_AT
                ? chances[0] + (chances[1] - chances[0]) * bounded / RelationshipMemory.FAMILIAR_AT
                : chances[1] + (chances[2] - chances[1]) * (bounded - RelationshipMemory.FAMILIAR_AT) / (40 - RelationshipMemory.FAMILIAR_AT);
        boolean familiar = trust >= RelationshipMemory.FAMILIAR_AT;
        double attention = familiar ? 1 : switch (pet.personality()) {
            case SHY, TERRITORIAL, GRUMPY -> .5;
            case FRIENDLY -> .75;
            case PLAYFUL -> 1;
        };
        boolean reserved = pet.personality() == PetPersonality.SHY || pet.personality() == PetPersonality.TERRITORIAL;
        boolean exuberant = familiar || pet.personality() == PetPersonality.PLAYFUL || pet.personality() == PetPersonality.FRIENDLY;
        return new ToyInvitation(Math.min(.95, chance + (favorite ? .15 : 0)), attention,
                !familiar && reserved ? 1 : 0, exuberant);
    }
}
