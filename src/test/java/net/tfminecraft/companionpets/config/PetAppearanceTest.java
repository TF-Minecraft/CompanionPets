package net.tfminecraft.companionpets.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import net.tfminecraft.companionpets.visual.PetAnimation;

class PetAppearanceTest {
    @Test
    void currentDogCatAndFoxClipsUseSleepForExhaustion() throws Exception {
        var available = java.util.Set.of("idle", "walk", "death", "sit", "sleep", "paw",
                "head_tilt", "shake", "lie_back", "belly_up", "get_up");
        var clips = read("appearance: {type: modelengine, model: beagle}").availableClips(available);
        assertEquals("sleep", clips.get(PetAnimation.LIE).name());
        assertEquals(clips.get(PetAnimation.SLEEP), clips.get(PetAnimation.LIE));
        assertFalse(clips.containsKey(PetAnimation.JUMP));
        assertFalse(clips.containsKey(PetAnimation.SWIM));
    }

    @Test
    void frogRetainsLayAndItsOwnJumpSwimAndCroak() throws Exception {
        var clips = read("""
                appearance:
                  type: modelengine
                  model: frog
                  animations:
                    speak: croak
                """).availableClips(java.util.Set.of("idle", "walk", "jump", "swim", "lay", "croak", "tongue"));
        assertEquals("lay", clips.get(PetAnimation.LIE).name());
        assertEquals("croak", clips.get(PetAnimation.SPEAK).name());
        assertEquals("jump", clips.get(PetAnimation.JUMP).name());
        assertEquals("swim", clips.get(PetAnimation.SWIM).name());
        assertFalse(clips.containsKey(PetAnimation.SLEEP));
    }

    @Test
    void automaticRestPoseRespectsExplicitMappingsAndDisabledClips() throws Exception {
        var available = java.util.Set.of("idle", "sit", "sleep", "lay", "nap");
        for (String mapping : java.util.List.of("lie: ''", "lie: missing", "lie: nap")) {
            var clips = read("appearance: {type: modelengine, model: dog, animations: {" + mapping + "}}")
                    .availableClips(available);
            if (mapping.equals("lie: nap")) assertEquals("nap", clips.get(PetAnimation.LIE).name());
            else assertFalse(clips.containsKey(PetAnimation.LIE));
        }
        var disabledSleep = read("appearance: {type: modelengine, model: dog, animations: {sleep: ''}}")
                .availableClips(java.util.Set.of("idle", "sit", "sleep"));
        assertFalse(disabledSleep.containsKey(PetAnimation.LIE));
        var remappedSleep = read("appearance: {type: modelengine, model: dog, animations: {sleep: {name: nap, speed: 0.8, blend: 0.3}}}")
                .availableClips(java.util.Set.of("idle", "sit", "nap"));
        assertEquals(new PetAppearance.Clip("nap", 0.8, 0.3), remappedSleep.get(PetAnimation.LIE));
    }

    @Test
    void omittedAppearanceKeepsVanillaAndModelHasConventionalDefaults() throws Exception {
        assertFalse(read("entity: WOLF").modeled());
        PetAppearance appearance = read("""
                entity: WOLF
                appearance:
                  type: modelengine
                  model: beagle
                """);
        assertEquals("beagle", appearance.model());
        assertEquals("sleep", appearance.animations().get(PetAnimation.SLEEP).name());
    }

    @Test
    void mappingsCanSetPlaybackAndDisableExtras() throws Exception {
        PetAppearance appearance = read("""
                appearance:
                  type: modelengine
                  model: beagle
                  animations:
                    paw: {name: give_paw, speed: 1.2, blend: 0.3}
                    speak: bark
                    spin: ""
                """);
        assertEquals(new PetAppearance.Clip("give_paw", 1.2, 0.3), appearance.animations().get(PetAnimation.PAW));
        assertEquals("bark", appearance.animations().get(PetAnimation.SPEAK).name());
        assertFalse(appearance.animations().containsKey(PetAnimation.SPIN));
    }

    @Test
    void invalidModelAndScaleFailClearlyButEveryClipIsOptional() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> read("appearance: {type: modelengine}"));
        assertThrows(IllegalArgumentException.class, () -> read("appearance: {type: modelengine, model: dog, scale: -1}"));
        assertFalse(read("appearance: {type: modelengine, model: dog, animations: {idle: ''}}")
                .animations().containsKey(PetAnimation.IDLE));
        assertThrows(IllegalArgumentException.class, () -> read("appearance: {type: modelengine, model: dog, animations: {sick: sick}}"));
    }

    private PetAppearance read(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return PetAppearance.read(config, "test", Logger.getAnonymousLogger());
    }
}
