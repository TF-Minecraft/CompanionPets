package net.tfminecraft.companionpets.pet;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RelationshipMemoryTest {
    @Test void meaningfulSeparatedCareBuildsTrustWhileRapidClicksDoNot() {
        var memories = new RelationshipMemory(); var person = UUID.randomUUID();
        assertTrue(memories.reinforce(person, 4, 1000, 60_000));
        assertFalse(memories.reinforce(person, 4, 2000, 60_000));
        assertFalse(memories.familiar(person));
        assertTrue(memories.reinforce(person, 4, 61_000, 60_000));
        assertTrue(memories.reinforce(person, 4, 121_000, 60_000)); assertTrue(memories.familiar(person));
        assertTrue(memories.reinforce(person, 1000, 181_000, 60_000)); assertEquals(100, memories.trust(person));
        memories.nearby(person, 182_000); memories.greeted(person, 183_000); memories.nearby(person, 1);
        assertEquals(183_000, memories.get(person).nearbyAt()); assertEquals(183_000, memories.get(person).greetedAt());
    }
    @Test void memoryIsBoundedAndBadSavedValuesCannotCreateTrust() {
        var memories = new RelationshipMemory(); var oldest = UUID.randomUUID();
        memories.reinforce(oldest, 4, 1, 0);
        for (int i = 2; i <= 40; i++) memories.reinforce(UUID.randomUUID(), 4, i, 0);
        assertEquals(RelationshipMemory.LIMIT, memories.entries().size()); assertNull(memories.get(oldest));
        memories.restore(oldest, new RelationshipMemory.Memory(Double.NaN, -1, -1, -1)); assertNull(memories.get(oldest));
        assertFalse(memories.reinforce(oldest, Double.POSITIVE_INFINITY, 100, 0));
    }
    @Test void ownershipTransferClearsMemoriesAndAnticipationWithoutGrantingRights() {
        var owner = UUID.randomUUID(); var person = UUID.randomUUID();
        var pet = new Pet(UUID.randomUUID(), owner, "wolf", "Toby", PetSex.MALE);
        pet.carers().reinforce(person, 50, 1000, 0); pet.friends().reinforce(UUID.randomUUID(), 50, 1000, 0);
        assertEquals(owner, pet.ownerId()); pet.toyExcitedUntilMillis(10000); pet.ownerId(UUID.randomUUID());
        assertTrue(pet.carers().entries().isEmpty()); assertTrue(pet.friends().entries().isEmpty());
        assertEquals(0, pet.toyExcitedUntilMillis());
    }
}
