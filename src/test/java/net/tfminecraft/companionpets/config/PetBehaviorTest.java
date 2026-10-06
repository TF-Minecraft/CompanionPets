package net.tfminecraft.companionpets.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

class PetBehaviorTest {
    @Test void defaultsGiveSpeciesDifferentActionsWithoutRestrictingExplicitOverrides() {
        var dog = PetBehavior.defaults(EntityType.WOLF);
        var cat = PetBehavior.defaults(EntityType.CAT);
        var frog = PetBehavior.defaults(EntityType.FROG);
        assertTrue(dog.contains(PetBehavior.DIG_GIFTS)); assertTrue(dog.contains(PetBehavior.GREETING_TAIL_WAG));
        assertFalse(cat.contains(PetBehavior.DIG_GIFTS)); assertFalse(cat.contains(PetBehavior.GREETING_JUMPS));
        assertTrue(cat.contains(PetBehavior.CAT_PLAY)); assertTrue(cat.contains(PetBehavior.GREETING_MEOWS));
        assertTrue(frog.contains(PetBehavior.FETCH)); assertTrue(frog.contains(PetBehavior.GREETING_JUMPS));
        assertTrue(frog.contains(PetBehavior.TOY_ANTICIPATION)); assertTrue(frog.contains(PetBehavior.TOY_JUMPS));
        assertFalse(frog.contains(PetBehavior.TOY_TAIL_WAG)); assertFalse(frog.contains(PetBehavior.DIG_GIFTS));
        assertTrue(dog.contains(PetBehavior.SOCIAL_GREETING)); assertTrue(dog.contains(PetBehavior.SOCIAL_TAIL_WAG));
        assertTrue(dog.contains(PetBehavior.SOCIAL_JUMPS)); assertTrue(cat.contains(PetBehavior.SOCIAL_VOCALIZING));
        assertFalse(cat.contains(PetBehavior.SOCIAL_JUMPS)); assertFalse(cat.contains(PetBehavior.SOCIAL_TAIL_WAG));
        assertTrue(frog.contains(PetBehavior.SOCIAL_GREETING)); assertTrue(frog.contains(PetBehavior.SOCIAL_JUMPS));
        assertFalse(frog.contains(PetBehavior.SOCIAL_TAIL_WAG));
        var yaml = new YamlConfiguration(); yaml.set("behaviors", List.of("greeting", "dig-gifts"));
        assertEquals(Set.of(PetBehavior.GREETING, PetBehavior.DIG_GIFTS), PetBehavior.read(yaml, EntityType.FROG, Logger.getAnonymousLogger()));
    }
    @Test void listReplacesDefaultsAndEmptyOrInvalidListFailsClosed() {
        var yaml = new YamlConfiguration(); var logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        yaml.set("behaviors", List.of("greeting", "GREETING", "typo", 123));
        assertEquals(Set.of(PetBehavior.GREETING), PetBehavior.read(yaml, EntityType.WOLF, logger));
        yaml.set("behaviors", List.of()); assertTrue(PetBehavior.read(yaml, EntityType.WOLF, logger).isEmpty());
        yaml.set("behaviors", "all"); assertTrue(PetBehavior.read(yaml, EntityType.WOLF, logger).isEmpty());
        yaml.set("behaviors", null); assertEquals(PetBehavior.defaults(EntityType.WOLF), PetBehavior.read(yaml, EntityType.WOLF, logger));
    }
}
