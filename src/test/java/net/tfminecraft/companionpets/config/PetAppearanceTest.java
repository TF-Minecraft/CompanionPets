package net.tfminecraft.companionpets.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import net.tfminecraft.companionpets.visual.PetAnimation;

class PetAppearanceTest {
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
