package net.tfminecraft.companionpets.pet;

import java.util.UUID;

public enum PetPersonality {
    FRIENDLY("Friendly", "Enjoys meeting other pets"),
    PLAYFUL("Playful", "Often wants to chase and play"),
    SHY("Shy", "Needs time before approaching strangers"),
    TERRITORIAL("Territorial", "Guards personal space"),
    GRUMPY("Grumpy", "Quick to complain when crowded");

    private final String label;
    private final String description;

    PetPersonality(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public boolean badTemper() {
        return this == TERRITORIAL || this == GRUMPY;
    }

    public static PetPersonality forId(UUID id) {
        int roll = Math.floorMod(id.hashCode(), 100);
        if (roll < 30) return FRIENDLY;
        if (roll < 55) return PLAYFUL;
        if (roll < 75) return SHY;
        if (roll < 90) return TERRITORIAL;
        return GRUMPY;
    }
}
