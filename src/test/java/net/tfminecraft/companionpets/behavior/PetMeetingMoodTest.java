package net.tfminecraft.companionpets.behavior;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import net.tfminecraft.companionpets.pet.*;

class PetMeetingMoodTest {
    private Pet pet(PetPersonality personality) {
        var pet = new Pet(UUID.randomUUID(), UUID.randomUUID(), "dog", "Toby", PetSex.MALE);
        pet.personality(personality); return pet;
    }
    @Test void reservedPetsWarmUpToTheirOwnFriendsAndKeepDistinctPersonalities() {
        var shy = pet(PetPersonality.SHY);
        assertEquals(PetMeetingMood.Reaction.OBSERVE, PetMeetingMood.of(shy, 0).reaction());
        assertFalse(PetMeetingMood.of(shy, 0).acceptsPlay());
        assertEquals(PetMeetingMood.Reaction.APPROACH, PetMeetingMood.of(shy, 12).reaction());
        assertTrue(PetMeetingMood.of(shy, 12).acceptsPlay());
        var territorial = pet(PetPersonality.TERRITORIAL);
        assertEquals(PetMeetingMood.Reaction.GUARD, PetMeetingMood.of(territorial, 0).reaction());
        assertEquals(PetMeetingMood.Reaction.APPROACH, PetMeetingMood.of(territorial, 12).reaction());
        var grumpy = pet(PetPersonality.GRUMPY);
        assertEquals(PetMeetingMood.Reaction.BRIEF, PetMeetingMood.of(grumpy, 100).reaction());
        assertTrue(PetMeetingMood.of(grumpy, 30).acceptsPlay());
        assertFalse(PetMeetingMood.of(grumpy, 12).acceptsPlay());
        assertTrue(PetMeetingMood.of(grumpy, 30).intensity() > PetMeetingMood.of(grumpy, 0).intensity());
    }
    @Test void APlayInvitationDoesNotMakeItsShyPartnerAcceptAndPoorHealthOverridesFriendship() {
        var playful = pet(PetPersonality.PLAYFUL);
        assertEquals(PetMeetingMood.Reaction.INVITE, PetMeetingMood.of(playful, 0).reaction());
        assertTrue(PetMeetingMood.of(playful, 0).hops());
        assertFalse(PetMeetingMood.of(pet(PetPersonality.SHY), 0).acceptsPlay());
        playful.need(Need.ENERGY, 30);
        assertFalse(PetMeetingMood.of(playful, 100).acceptsPlay());
        assertFalse(PetMeetingMood.of(playful, 100).hops());
        playful.need(Need.ENERGY, 100); playful.illness(Illness.SICK);
        assertFalse(PetMeetingMood.of(playful, 100).acceptsPlay());
        assertFalse(PetMeetingMood.of(playful, 100).hops());
    }
}
