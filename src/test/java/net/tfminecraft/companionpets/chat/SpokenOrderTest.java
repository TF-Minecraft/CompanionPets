package net.tfminecraft.companionpets.chat;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SpokenOrderTest {
    @Test void acceptsCommandsOnEitherSideOfWholeNamesAndPunctuation() {
        assertEquals("sit down", SpokenOrder.addressedCommand("Sir Toby, sit down!", "Sir Toby"));
        assertEquals("sit down", SpokenOrder.addressedCommand("sit down Sir Toby!", "Sir Toby"));
        assertEquals("jump", SpokenOrder.addressedCommand("TOBY: jump", "Toby"));
        assertNull(SpokenOrder.addressedCommand("Toby", "Toby"));
        assertNull(SpokenOrder.addressedCommand("Tobyson jump", "Toby"));
        assertNull(SpokenOrder.addressedCommand("jump Notoby", "Toby"));
        assertNull(SpokenOrder.addressedCommand("please Toby jump", "Toby"));
    }
}
