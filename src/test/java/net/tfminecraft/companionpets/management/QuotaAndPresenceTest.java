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
        assertTrue(Quota.canStore(19, 20));
        assertFalse(Quota.canStore(20, 20));
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
