package net.tfminecraft.companionpets.care;

import net.tfminecraft.companionpets.pet.Presence;

public record CareInput(
        Presence presence,
        boolean walking,
        boolean playing,
        boolean sleeping,
        boolean withOwner,
        long elapsedMillis,
        CareSettings care,
        double awayRate) {
}
