package net.tfminecraft.companionpets.management;

import net.tfminecraft.companionpets.pet.Presence;

public final class PresenceRules {
    private PresenceRules() {
    }

    public static Presence resolve(
            boolean stored,
            boolean ownerOnline,
            double distance,
            double nearRadius,
            boolean decayWhileStored) {
        if (!ownerOnline) {
            return Presence.FROZEN;
        }
        if (stored && !decayWhileStored) {
            return Presence.FROZEN;
        }
        if (stored || distance <= nearRadius) {
            return Presence.NEAR;
        }
        return Presence.AWAY;
    }
}
