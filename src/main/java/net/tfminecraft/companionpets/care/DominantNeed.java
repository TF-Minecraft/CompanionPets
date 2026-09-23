package net.tfminecraft.companionpets.care;

import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.NeedBand;
import net.tfminecraft.companionpets.pet.Pet;

public final class DominantNeed {
    private static final Need[] ORDER = {Need.HUNGER, Need.ENERGY, Need.CLEANLINESS, Need.MOOD};

    private DominantNeed() {
    }

    public static Need select(Pet pet) {
        if (pet.illness() == Illness.UNWELL || pet.illness() == Illness.SICK || pet.illness() == Illness.WEAKENED) {
            return null;
        }
        Need best = null;
        NeedBand bestBand = NeedBand.STABLE;
        for (Need need : ORDER) {
            NeedBand band = NeedBand.of(pet.need(need));
            if (band == NeedBand.STABLE) {
                continue;
            }
            if (best == null || worse(band, bestBand)) {
                best = need;
                bestBand = band;
            }
        }
        return best;
    }

    private static boolean worse(NeedBand candidate, NeedBand current) {
        return rank(candidate) > rank(current);
    }

    private static int rank(NeedBand band) {
        return switch (band) {
            case STABLE -> 0;
            case LOW -> 1;
            case CRITICAL -> 2;
        };
    }
}
