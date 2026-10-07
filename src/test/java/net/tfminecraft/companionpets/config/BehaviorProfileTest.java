package net.tfminecraft.companionpets.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import java.util.logging.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class BehaviorProfileTest {
    @BeforeEach void setup() { MockBukkit.mock(); }
    @AfterEach void close() { MockBukkit.unmock(); }
    @Test void profilesPreserveDogAndFrogActionsAndAddSharedGiftsAndMischiefToCats() {
        var common = Set.of(PetBehavior.GREETING, PetBehavior.GREETING_APPROACH, PetBehavior.SOCIAL_GREETING,
                PetBehavior.SOCIAL_SNIFF, PetBehavior.AFFECTION, PetBehavior.RECOGNIZE_CARERS, PetBehavior.PET_FRIENDSHIPS);
        // The former WOLF/CAT defaults, written out so the profiles stay equivalent to them.
        var dog = new java.util.HashSet<>(common);
        dog.addAll(Set.of(PetBehavior.GREETING_CIRCLES, PetBehavior.GREETING_JUMPS, PetBehavior.GREETING_TAIL_WAG,
                PetBehavior.TOY_ANTICIPATION, PetBehavior.TOY_VOCALIZING, PetBehavior.TOY_JUMPS, PetBehavior.TOY_TAIL_WAG,
                PetBehavior.TOY_WIGGLE, PetBehavior.FETCH, PetBehavior.SOCIAL_CHASE, PetBehavior.SOCIAL_PROTEST,
                PetBehavior.SOCIAL_TAIL_WAG, PetBehavior.SOCIAL_JUMPS, PetBehavior.SOCIAL_VOCALIZING,
                PetBehavior.AFFECTION_JUMPS, PetBehavior.BELLY_RUB, PetBehavior.MISCHIEF, PetBehavior.DIG_GIFTS));
        assertEquals(dog, BehaviorProfile.DOG.behaviors());
        assertEquals(Set.of(PetBehavior.GREETING, PetBehavior.GREETING_APPROACH, PetBehavior.GREETING_JUMPS,
                PetBehavior.TOY_ANTICIPATION, PetBehavior.TOY_VOCALIZING, PetBehavior.TOY_JUMPS, PetBehavior.FETCH,
                PetBehavior.SOCIAL_GREETING, PetBehavior.SOCIAL_SNIFF, PetBehavior.SOCIAL_JUMPS, PetBehavior.SOCIAL_VOCALIZING,
                PetBehavior.AFFECTION, PetBehavior.RECOGNIZE_CARERS, PetBehavior.PET_FRIENDSHIPS), BehaviorProfile.BASIC.behaviors());
        var cat = new java.util.HashSet<>(common);
        cat.addAll(Set.of(PetBehavior.GREETING_CIRCLES, PetBehavior.GREETING_MEOWS, PetBehavior.TOY_ANTICIPATION,
                PetBehavior.TOY_VOCALIZING, PetBehavior.FETCH, PetBehavior.CAT_PLAY, PetBehavior.SOCIAL_CHASE,
                PetBehavior.SOCIAL_PROTEST, PetBehavior.SOCIAL_VOCALIZING, PetBehavior.BELLY_RUB));
        cat.add(PetBehavior.DIG_GIFTS); cat.add(PetBehavior.MISCHIEF);
        assertEquals(cat, BehaviorProfile.CAT.behaviors());
        assertFalse(cat.contains(PetBehavior.TOY_JUMPS)); assertFalse(cat.contains(PetBehavior.TOY_WIGGLE));
        assertFalse(cat.contains(PetBehavior.TOY_TAIL_WAG)); assertTrue(cat.contains(PetBehavior.CAT_PLAY));
    }
    @Test void speciesProfilesInheritAndPetProfilesOverrideIndependentlyOfBodiesAndVoices() throws Exception {
        var yaml = new YamlConfiguration(); yaml.loadFromString("""
            species:
              fox: {entity: WOLF, voice: fox, behavior: cat}
              hopper: {entity: CAT, behavior: basic}
            pets:
              fox: {species: fox, egg: EGG}
              exuberant: {species: fox, behavior: dog, egg: STICK}
              hopper: {species: hopper, egg: BONE}
              dog: {entity: WOLF, egg: WOLF_SPAWN_EGG}
              cat: {entity: CAT, egg: CAT_SPAWN_EGG}
            """);
        var config = CompanionConfig.load(MockBukkit.createMockPlugin(), yaml);
        assertEquals(BehaviorProfile.CAT, config.type("fox").behavior());
        assertEquals("minecraft:entity.fox.ambient", config.type("fox").sounds().cue(PetSounds.Event.GREETING).sounds().getFirst());
        assertEquals(BehaviorProfile.DOG, config.type("exuberant").behavior());
        assertEquals(BehaviorProfile.BASIC, config.type("hopper").behavior());
        assertEquals(BehaviorProfile.DOG, config.type("dog").behavior());
        assertEquals(BehaviorProfile.CAT, config.type("cat").behavior());
    }
    @Test void obsoleteListsAreIgnoredWithOneWarningAndUnknownProfilesListTheValidChoices() throws Exception {
        var warnings = new java.util.ArrayList<String>();
        var plugin = MockBukkit.createMockPlugin();
        Handler handler = new Handler() {
            public void publish(LogRecord record) { warnings.add(record.getMessage()); }
            public void flush() { } public void close() { }
        };
        plugin.getLogger().addHandler(handler);
        try {
            var yaml = new YamlConfiguration(); yaml.loadFromString("""
                species:
                  dog: {behaviors: {remove: [fetch]}}
                pets:
                  dog: {species: dog, behaviors: [], egg: EGG}
                  cat: {entity: CAT, behaviors: [fetch], behavior: unknown, egg: STICK}
                """);
            var config = CompanionConfig.load(plugin, yaml);
            assertEquals(BehaviorProfile.DOG.behaviors(), config.type("dog").behaviors());
            assertEquals(BehaviorProfile.CAT.behaviors(), config.type("cat").behaviors());
            assertEquals(1, warnings.stream().filter(s -> s.equals("behaviors is no longer configurable; use behavior: dog|cat|basic")).count());
            assertTrue(warnings.stream().anyMatch(s -> s.contains("unknown") && s.contains("dog, cat, basic") && s.contains("using cat")));
        } finally { plugin.getLogger().removeHandler(handler); }
    }
}
