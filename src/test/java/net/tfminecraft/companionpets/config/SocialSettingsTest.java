package net.tfminecraft.companionpets.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SocialSettingsTest {
    @Test void oldConfigurationsGainMeetingDefaultsAndInvalidValuesAreBounded() {
        var config = new YamlConfiguration(); config.set("encounter-radius", 3);
        var settings = SocialSettings.load(config, Logger.getAnonymousLogger());
        assertEquals(3, settings.greetingRadius()); assertEquals(3, settings.greetingSeconds());
        assertEquals(10, settings.separationSeconds()); assertEquals(12, settings.maxFriendshipGainPerMinute());
        config.set("greeting-radius", 99); config.set("greeting-seconds", -10);
        config.set("separation-seconds", Double.NaN); config.set("greeting-friendship-gain", -10);
        config.set("sniff-friendship-gain", 2); config.set("chase-friendship-gain", 3);
        config.set("max-friendship-gain-per-minute", Double.POSITIVE_INFINITY);
        settings = SocialSettings.load(config, Logger.getAnonymousLogger());
        assertEquals(3, settings.greetingRadius()); assertEquals(2, settings.greetingSeconds());
        assertEquals(10, settings.separationSeconds()); assertEquals(0, settings.greetingFriendshipGain());
        assertEquals(2, settings.sniffFriendshipGain()); assertEquals(3, settings.chaseFriendshipGain());
        assertEquals(12, settings.maxFriendshipGainPerMinute());
    }
}
