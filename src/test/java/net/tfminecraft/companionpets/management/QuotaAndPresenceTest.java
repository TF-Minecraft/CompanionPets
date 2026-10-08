package net.tfminecraft.companionpets.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.tfminecraft.companionpets.pet.Presence;

class QuotaAndPresenceTest {
    @Test
    void quotasAreHardStops() {
        assertTrue(Quota.canBringOut(3, 4));
        assertFalse(Quota.canBringOut(4, 4));
        assertTrue(Quota.canAdopt(19, 20));
        assertFalse(Quota.canAdopt(20, 20));
        assertFalse(Quota.canAdopt(0, 0));
        assertFalse(Quota.canBringOut(0, 0));
    }

    @Test void outsideQuotaIsASubsetOfTheTotalEvenWhenConfiguredHigher() {
        var limits = new Limits(2, 4);
        assertEquals(2, limits.maxPets()); assertEquals(4, limits.maxOut());
        assertTrue(Quota.canAdopt(1, limits.maxPets()));
        assertFalse(Quota.canAdopt(2, limits.maxPets()));
        assertTrue(Quota.canBringOut(2, limits.maxOut()));
    }

    @Test
    void presenceFollowsOwnerDistanceAndStorage() {
        assertEquals(Presence.FROZEN, PresenceRules.resolve(false, false, 1, 32, false));
        assertEquals(Presence.FROZEN, PresenceRules.resolve(true, true, 1, 32, false));
        assertEquals(Presence.NEAR, PresenceRules.resolve(true, true, 1, 32, true));
        assertEquals(Presence.NEAR, PresenceRules.resolve(false, true, 32, 32, false));
        assertEquals(Presence.AWAY, PresenceRules.resolve(false, true, 32.1, 32, false));
    }
}
