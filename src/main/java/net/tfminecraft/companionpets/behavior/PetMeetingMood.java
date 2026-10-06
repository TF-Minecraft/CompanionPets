package net.tfminecraft.companionpets.behavior;

import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.RelationshipMemory;

/** Each pet decides how to receive its partner; friendliness need not be mutual. */
public record PetMeetingMood(Reaction reaction, double intensity, boolean acceptsPlay, boolean hops) {
    public enum Reaction { APPROACH, INVITE, OBSERVE, GUARD, BRIEF }

    public static PetMeetingMood of(Pet pet, double friendship) {
        GreetingMood physical = GreetingMood.of(pet, friendship);
        boolean familiar = friendship >= RelationshipMemory.FAMILIAR_AT;
        Reaction reaction = switch (pet.personality()) {
            case FRIENDLY -> Reaction.APPROACH;
            case PLAYFUL -> Reaction.INVITE;
            case SHY -> familiar ? Reaction.APPROACH : Reaction.OBSERVE;
            case TERRITORIAL -> familiar ? Reaction.APPROACH : Reaction.GUARD;
            case GRUMPY -> Reaction.BRIEF;
        };
        boolean willing = physical.mobile() && pet.need(Need.ENERGY) >= 50 && pet.need(Need.HEALTH) >= 70
                && switch (pet.personality()) {
                    case FRIENDLY, PLAYFUL -> true;
                    case SHY, TERRITORIAL -> familiar;
                    case GRUMPY -> friendship >= 30;
                };
        double enthusiasm = physical.intensity();
        if (reaction == Reaction.OBSERVE || reaction == Reaction.GUARD) enthusiasm *= 0.3;
        if (reaction == Reaction.BRIEF) enthusiasm *= familiar ? 0.75 : 0.3;
        boolean hops = physical.mobile() && pet.need(Need.ENERGY) >= 60 && pet.need(Need.HEALTH) >= 70
                && (reaction == Reaction.INVITE || familiar && enthusiasm >= 0.55);
        return new PetMeetingMood(reaction, enthusiasm, willing, hops);
    }

    public double personalSpace() {
        return switch (reaction) {
            case OBSERVE -> 2.4;
            case GUARD -> 2.2;
            case BRIEF -> 1.6;
            default -> 1.2;
        };
    }
    public boolean approaches() { return reaction == Reaction.APPROACH || reaction == Reaction.INVITE; }
}
