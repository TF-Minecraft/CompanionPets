package net.tfminecraft.companionpets.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class ThrowAndFavoriteTest {
    @Test
    void aimAndSneakStayInsideTheConfiguredSpeeds() {
        assertEquals(1.1, ThrowSpeed.speed(-25, false, 0.6, 1.1), 0.001);
        assertEquals(0.6, ThrowSpeed.speed(-90, false, 0.6, 1.1), 0.001);
        double forward = ThrowSpeed.speed(0, false, 0.6, 1.1);
        assertTrue(forward > 0.6 && forward < 1.1);
        double sneaky = ThrowSpeed.speed(-25, true, 0.6, 1.1);
        assertTrue(sneaky < forward);
        assertTrue(sneaky >= 0.6 && sneaky <= 1.1);
    }

    @Test
    void favoriteStaysRerollsOrClearsWithTheList() {
        assertEquals("STICK", FavoriteToy.reconcile("STICK", List.of("STICK", "FEATHER"), new Random(1)).toy());
        assertFalse(FavoriteToy.reconcile("STICK", List.of("STICK", "FEATHER"), new Random(1)).notifyLost());

        FavoriteToy.Result added = FavoriteToy.reconcile("STICK", List.of("STICK", "FEATHER", "BONE"), new Random(1));
        assertEquals("STICK", added.toy());

        FavoriteToy.Result lost = FavoriteToy.reconcile("BONE", List.of("STICK", "FEATHER"), new Random(1));
        assertTrue(lost.notifyLost());
        assertTrue(List.of("STICK", "FEATHER").contains(lost.toy()));

        FavoriteToy.Result empty = FavoriteToy.reconcile("STICK", List.of(), new Random(1));
        assertNull(empty.toy());
        assertFalse(empty.notifyLost());

        FavoriteToy.Result fresh = FavoriteToy.reconcile(null, List.of("STICK"), new Random(1));
        assertEquals("STICK", fresh.toy());
        assertFalse(fresh.notifyLost());
    }
}
