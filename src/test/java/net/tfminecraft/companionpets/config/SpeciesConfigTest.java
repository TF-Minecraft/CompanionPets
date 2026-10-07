package net.tfminecraft.companionpets.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import net.tfminecraft.companionpets.pet.Trick;
import net.tfminecraft.companionpets.visual.PetAnimation;

class SpeciesConfigTest {
    @BeforeEach void setup() { MockBukkit.mock(); }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    private CompanionConfig load(String text) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return CompanionConfig.load(MockBukkit.createMockPlugin(), yaml);
    }

    @Test void builtinsAndLegacyPetsShareEvolvingDefaultsAndNativeVoice() throws Exception {
        var config = load("""
                pets:
                  beagle: {species: dog, model: beagle, egg: EGG}
                  cat: {species: cat, egg: CAT_SPAWN_EGG}
                  legacy: {entity: WOLF, egg: WOLF_SPAWN_EGG}
                """);
        var beagle = config.type("beagle");
        assertEquals("dog", beagle.species());
        assertEquals(EntityType.WOLF, beagle.entity());
        assertEquals("beagle", beagle.appearance().model());
        assertEquals(PetBehavior.defaults(EntityType.WOLF), beagle.behaviors());
        assertEquals(config.type("legacy").behaviors(), beagle.behaviors());
        assertTrue(beagle.sounds().nativeSounds());
        assertEquals(0, beagle.sounds().ambientIntervalSeconds());
        assertEquals(Set.of(Trick.values()), beagle.tricks());
        assertEquals(EntityType.CAT, config.type("cat").entity());
        assertEquals("minecraft:entity.cat.ambient", config.type("cat").sounds().cue(PetSounds.Event.AMBIENT).sounds().getFirst());
    }

    @Test void omittedTricksOnlyGrantBuiltinsEvenWithCustomDefinitions() throws Exception {
        var config = load("""
                custom-tricks:
                  salute: {fallback-text: Hello}
                species:
                  rabbit: {entity: CAT}
                pets:
                  legacy: {entity: WOLF, egg: EGG}
                  dog: {species: dog, egg: WOLF_SPAWN_EGG}
                  cat: {species: cat, egg: CAT_SPAWN_EGG}
                  rabbit: {species: rabbit, egg: RABBIT_SPAWN_EGG}
                """);
        assertEquals(4, config.types().size());
        for (var pet : config.types().values())
            assertEquals(Set.of(Trick.values()), pet.tricks(), pet.id());
        assertNotNull(config.customTrick(Trick.valueOf("salute")), "Opt-in does not remove the definition");
    }

    @Test void petAdditionsOptIntoCustomTricksWithOrWithoutSpecies() throws Exception {
        var config = load("""
                custom-tricks:
                  salute: {fallback-text: Hello}
                pets:
                  legacy: {entity: WOLF, egg: EGG, tricks: {add: [salute]}}
                  dog: {species: dog, egg: WOLF_SPAWN_EGG, tricks: {add: [salute]}}
                """);
        assertEquals(2, config.types().size());
        var expected = new java.util.HashSet<>(Set.of(Trick.values()));
        expected.add(Trick.valueOf("salute"));
        for (var pet : config.types().values()) assertEquals(expected, pet.tricks(), pet.id());
    }

    @Test void speciesExplicitCustomTricksAreInheritedByPets() throws Exception {
        var config = load("""
                custom-tricks:
                  salute: {fallback-text: Hello}
                species:
                  greeter: {entity: CAT, tricks: [sit, salute]}
                  dog: {tricks: {add: [salute]}}
                pets:
                  greeter: {species: greeter, egg: EGG}
                  dog: {species: dog, egg: WOLF_SPAWN_EGG}
                  plain: {species: cat, egg: CAT_SPAWN_EGG}
                  explicit: {entity: WOLF, egg: BONE, tricks: [salute]}
                """);
        var salute = Trick.valueOf("salute");
        assertEquals(Set.of(Trick.FOLLOW, Trick.SIT, salute), config.type("greeter").tricks());
        assertTrue(config.type("dog").allowsTrick(salute));
        assertFalse(config.type("plain").allowsTrick(salute));
        assertEquals(Set.of(Trick.FOLLOW, salute), config.type("explicit").tricks());
    }

    @Test void customDefaultTricksRemainAllowedEvenWhenRemovedOrNotListed() throws Exception {
        var config = load("""
                custom-tricks:
                  salute: {fallback-text: Hello}
                training:
                  default-tricks: [salute]
                species:
                  greeter: {entity: CAT, default-tricks: [salute], tricks: []}
                pets:
                  legacy: {entity: WOLF, egg: EGG}
                  dog: {species: dog, egg: WOLF_SPAWN_EGG, tricks: {remove: [salute]}}
                  greeter: {species: greeter, egg: CAT_SPAWN_EGG}
                  explicit: {entity: WOLF, egg: BONE, tricks: [], default-tricks: [salute]}
                """);
        assertEquals(4, config.types().size());
        var salute = Trick.valueOf("salute");
        for (var pet : config.types().values()) {
            assertTrue(pet.allowsTrick(salute), pet.id());
            assertEquals(List.of(salute), pet.defaultTricks(), pet.id());
        }
        assertEquals(Set.of(salute), config.type("greeter").tricks());
        assertEquals(Set.of(salute), config.type("explicit").tricks());
    }

    @Test void customSpeciesShareBodyVoiceTricksAnimationsAndKeepPetIds() throws Exception {
        var config = load("""
                custom-tricks:
                  tongue: {animation: tongue}
                  croak: {animation: croak}
                species:
                  frog:
                    entity: CAT
                    voice: frog
                    behaviors: [greeting, greeting-approach, greeting-jumps, toy-anticipation, toy-jumps, fetch]
                    tricks: [follow, come, stay, jump, lay, tongue, croak]
                    animations: {lie: lay, sleep: lay}
                pets:
                  frog: {species: frog, model: frog, egg: 'mmoitems:PETS:PET_FROG_EGG'}
                  tiny_frog:
                    species: frog
                    model: frog
                    egg: EGG
                    voice: {pitch: 1.3}
                    behaviors: {add: [social-greeting], remove: [greeting-jumps]}
                    tricks: {add: [sit], remove: [croak]}
                    animations: {eat: tongue}
                """);
        var frog = config.type("frog");
        assertNotNull(frog); assertEquals("frog", frog.id());
        assertEquals(EntityType.CAT, frog.entity());
        assertEquals("minecraft:entity.frog.ambient", frog.sounds().cue(PetSounds.Event.AMBIENT).sounds().getFirst());
        assertFalse(frog.sounds().nativeSounds());
        assertEquals(List.of("minecraft:entity.frog.ambient"), frog.sounds().cue(PetSounds.Event.AMBIENT).sounds());
        assertEquals("lay", frog.appearance().animations().get(PetAnimation.LIE).name());
        assertEquals("lay", frog.appearance().animations().get(PetAnimation.SLEEP).name());
        var tiny = config.type("tiny_frog");
        assertEquals("minecraft:entity.frog.ambient", tiny.sounds().cue(PetSounds.Event.AMBIENT).sounds().getFirst());
        assertEquals(1.3f, tiny.sounds().cue(PetSounds.Event.AMBIENT).pitch(), .001);
        assertEquals(1.43f, tiny.sounds().cue(PetSounds.Event.GREETING).pitch(), .001);
        assertFalse(tiny.behaves(PetBehavior.GREETING_JUMPS));
        assertTrue(tiny.behaves(PetBehavior.SOCIAL_GREETING));
        assertTrue(tiny.allowsTrick(Trick.SIT));
        assertFalse(tiny.allowsTrick(Trick.valueOf("croak")));
        assertEquals("tongue", tiny.appearance().animations().get(PetAnimation.EAT).name());
        assertEquals(frog.appearance().animations().get(PetAnimation.LIE), tiny.appearance().animations().get(PetAnimation.LIE));
        assertTrue(frog.allowsTrick(Trick.valueOf("croak")), "Resolving a pet never mutates its template or siblings");
    }

    @Test void speciesAndPetPatchesStackWhileListsStillReplaceAndRemovalWins() throws Exception {
        var config = load("""
                species:
                  dog:
                    behaviors: {remove: [dig-gifts]}
                    tricks: {remove: [paw]}
                    items: {treats: [COD]}
                pets:
                  dog:
                    species: dog
                    egg: EGG
                    behaviors: {add: [dig-gifts, cat-play], remove: [cat-play]}
                    tricks: {add: [paw, jump], remove: [jump]}
                  quiet:
                    species: dog
                    egg: STICK
                    behaviors: []
                    tricks: []
                    default-tricks: []
                    items: {treats: []}
                  legacy: {entity: CAT, egg: CAT_SPAWN_EGG, behaviors: [fetch], tricks: [sit]}
                """);
        var dog = config.type("dog");
        assertTrue(dog.behaves(PetBehavior.DIG_GIFTS));
        assertTrue(dog.behaves(PetBehavior.SOCIAL_TAIL_WAG));
        assertFalse(dog.behaves(PetBehavior.CAT_PLAY));
        assertTrue(dog.allowsTrick(Trick.PAW)); assertFalse(dog.allowsTrick(Trick.JUMP));
        assertEquals(1, dog.treats().size());
        assertTrue(config.type("quiet").behaviors().isEmpty());
        assertTrue(config.type("quiet").tricks().isEmpty());
        assertTrue(config.type("quiet").treats().isEmpty());
        assertEquals(Set.of(PetBehavior.FETCH), config.type("legacy").behaviors());
        assertEquals(Set.of(Trick.SIT, Trick.FOLLOW), config.type("legacy").tricks());
    }

    @Test void advancedFieldsOverrideShortcutsAndSpeciesWithoutDiscardingOtherMappings() throws Exception {
        var config = load("""
                species:
                  dog:
                    voice: {preset: wolf, pitch: 1.2, toy: false}
                    animations: {pet: pet2, paw: give_paw}
                pets:
                  beagle:
                    species: dog
                    egg: EGG
                    model: ignored
                    voice: cat
                    sounds: {preset: fox, pitch: 1.5, greeting: {sounds: 'pack:hello', pitch: 1.8}}
                    appearance:
                      type: modelengine
                      model: beagle
                      scale: 0.8
                      animations: {pet: pet3}
                  silent: {species: dog, egg: STICK, voice: false}
                  advanced: {species: dog, egg: BONE, voice: false, sounds: {preset: frog}}
                """);
        var pet = config.type("beagle");
        assertEquals("beagle", pet.appearance().model());
        assertEquals(.8, pet.appearance().scale());
        assertEquals("pet3", pet.appearance().animations().get(PetAnimation.PET).name());
        assertEquals("give_paw", pet.appearance().animations().get(PetAnimation.PAW).name());
        assertEquals("minecraft:entity.fox.ambient", pet.sounds().cue(PetSounds.Event.AMBIENT).sounds().getFirst());
        assertNull(pet.sounds().cue(PetSounds.Event.TOY));
        assertEquals(2f, pet.sounds().cue(PetSounds.Event.GREETING).pitch(), "Global pitch is clamped after per-event pitch");
        assertTrue(config.type("silent").sounds().cues().isEmpty());
        assertEquals("minecraft:entity.frog.ambient", config.type("advanced").sounds().cue(PetSounds.Event.AMBIENT).sounds().getFirst());
    }

    @Test void invalidPetAndTemplateEntitiesReportClearWarningsEvenWithAnOverride() throws Exception {
        var plugin = MockBukkit.createMockPlugin();
        var warnings = new java.util.ArrayList<String>();
        var handler = new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { warnings.add(record.getMessage()); }
            public void flush() { }
            public void close() { }
        };
        plugin.getLogger().addHandler(handler);
        try {
            var yaml = new YamlConfiguration(); yaml.loadFromString("""
                    species:
                      broken: {entity: DOGG}
                    pets:
                      invalid_pet: {entity: DOGG, egg: EGG}
                      inherited: {species: broken, egg: BONE}
                      overridden: {species: broken, entity: WOLF, egg: WOLF_SPAWN_EGG}
                      valid: {species: dog, egg: STICK}
                    """);
            var config = CompanionConfig.load(plugin, yaml);
            assertEquals(Set.of("valid"), config.types().keySet());
            for (String id : List.of("invalid_pet", "inherited", "overridden"))
                assertTrue(warnings.contains("Skipping pet type " + id + ": entity is invalid"), id);
            assertFalse(warnings.stream().anyMatch(message -> message.contains("No enum constant")));
        } finally { plugin.getLogger().removeHandler(handler); }
    }

    @Test void invalidSpeciesAreSkippedAndInvalidAdjustmentsKeepOtherDefaults() throws Exception {
        var config = load("""
                species:
                  unsupported: {entity: FROG}
                pets:
                  unknown: {species: typo, egg: EGG}
                  bad: {species: unsupported, egg: STICK}
                  valid:
                    species: dog
                    egg: WOLF_SPAWN_EGG
                    voice: {pitch: .nan}
                    behaviors: {add: [bogus, 42], remove: invalid}
                    tricks: {add: [spin, unknown], remove: [sleep]}
                """);
        assertEquals(Set.of("valid"), config.types().keySet());
        assertEquals(PetBehavior.defaults(EntityType.WOLF), config.type("valid").behaviors());
        assertEquals(1, config.type("valid").sounds().pitch());
        assertFalse(config.type("valid").allowsTrick(Trick.LAY));
    }
}
