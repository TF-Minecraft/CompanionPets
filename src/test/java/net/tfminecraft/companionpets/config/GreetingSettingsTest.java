package net.tfminecraft.companionpets.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class GreetingSettingsTest {
    @Test void validatesIndependentGreetingSettingsAndFallsBackForInvalidNumbers() {
        var yaml = new YamlConfiguration();
        yaml.set("enabled", false);
        yaml.set("absence-seconds", 30);
        yaml.set("near-radius", Double.NaN);
        yaml.set("duration-seconds", -2);
        yaml.set("speed", 200);
        yaml.set("sound-interval-seconds", "fast");
        yaml.set("tail-wag-hz", Double.POSITIVE_INFINITY);
        yaml.set("jump-enabled", false);
        yaml.set("cat-speed", 1.5);
        yaml.set("cat-circle-radius", -1);
        yaml.set("cat-sound-interval-seconds", Double.NaN);
        var logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        var settings = GreetingSettings.read(yaml, logger);
        assertFalse(settings.enabled()); assertEquals(30, settings.absenceSeconds());
        assertEquals(8, settings.nearRadius()); assertEquals(8, settings.durationSeconds());
        assertEquals(1.35, settings.speed()); assertEquals(1.2, settings.soundIntervalSeconds());
        assertFalse(settings.jumpEnabled()); assertEquals(3, settings.tailWagHz());
        assertEquals(0.8, settings.catSpeed()); assertEquals(1.4, settings.catCircleRadius());
        assertEquals(0.65, settings.catSoundIntervalSeconds());
        assertEquals(300, GreetingSettings.read(null, logger).absenceSeconds());
    }

    @Test void catSettingsCanBeTunedWithoutChangingDogSettings() {
        var yaml = new YamlConfiguration();
        yaml.set("cat-speed", 0.6); yaml.set("cat-circle-radius", 1.2);
        yaml.set("cat-sound-interval-seconds", 0.5);
        var settings = GreetingSettings.read(yaml, Logger.getAnonymousLogger());
        assertEquals(0.6, settings.catSpeed()); assertEquals(1.2, settings.catCircleRadius());
        assertEquals(0.5, settings.catSoundIntervalSeconds());
        assertEquals(1.35, settings.speed()); assertEquals(2, settings.circleRadius());
        assertEquals(1.2, settings.soundIntervalSeconds());
    }
}
