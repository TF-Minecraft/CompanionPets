package net.tfminecraft.companionpets.behavior;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import net.tfminecraft.companionpets.pet.*;

class ToyInvitationTest {
    @Test void everyPersonalityInterpolatesTheTableAndCapsFavoriteBonus() {
        PetPersonality[] personalities = {PetPersonality.PLAYFUL, PetPersonality.FRIENDLY,
                PetPersonality.SHY, PetPersonality.TERRITORIAL, PetPersonality.GRUMPY};
        double[][] table = {{.60, .80, .95}, {.45, .70, .90}, {.10, .40, .85}, {.12, .45, .80}, {.08, .25, .60}};
        for (int i = 0; i < personalities.length; i++) {
            Pet pet = new Pet(UUID.randomUUID(), UUID.randomUUID(), "wolf", "Toby", PetSex.MALE);
            pet.personality(personalities[i]);
            double[] trust = {0, 12, 40, 100, -1, 6, 26};
            double[] expected = {table[i][0], table[i][1], table[i][2], table[i][2], table[i][0],
                    (table[i][0] + table[i][1]) / 2, (table[i][1] + table[i][2]) / 2};
            for (int j = 0; j < trust.length; j++) {
                assertEquals(expected[j], ToyInvitation.of(pet, trust[j], false).chance(), 1e-12);
                assertEquals(Math.min(.95, expected[j] + .15), ToyInvitation.of(pet, trust[j], true).chance(), 1e-12);
                assertTrue(ToyInvitation.of(pet, trust[j], false).chance() > 0);
            }
            var stranger = ToyInvitation.of(pet, 0, false);
            assertEquals(i == 0 ? 1 : i == 1 ? .75 : .5, stranger.attentionFactor());
            assertEquals(i == 2 || i == 3 ? 1 : 0, stranger.extraDistance());
            assertEquals(i < 2, stranger.exuberant());
            var familiar = ToyInvitation.of(pet, RelationshipMemory.FAMILIAR_AT, false);
            assertEquals(1, familiar.attentionFactor()); assertEquals(0, familiar.extraDistance());
            assertTrue(familiar.exuberant());
        }
    }
}
