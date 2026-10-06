package net.tfminecraft.companionpets.config;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class TrainingValidationTest {
    @BeforeEach void mock() { MockBukkit.mock(); }
    @AfterEach void unmock() { MockBukkit.unmock(); }
    @Test void impossibleTrainingThresholdsAreRejected() throws Exception {
        for (String training : new String[]{"{learned-at: 200}", "{learned-at: 0}", "{sometimes-at: 90, learned-at: 80}",
                "{attempts-before-bored: 0}", "{reward-window-seconds: 0}", "{session-distance: 0}"}) {
            var yaml = new YamlConfiguration(); yaml.loadFromString("training: " + training);
            assertThrows(IllegalArgumentException.class, () -> CompanionConfig.load(MockBukkit.createMockPlugin(), yaml), training);
        }
    }
    @Test void boundaryThresholdsLoadAndFullProgressCanObey() throws Exception {
        var yaml = new YamlConfiguration(); yaml.loadFromString("training: {sometimes-at: 0, learned-at: 100}");
        var config = CompanionConfig.load(MockBukkit.createMockPlugin(), yaml);
        assertEquals(net.tfminecraft.companionpets.training.TrainingMath.Understanding.OBEYS,
                net.tfminecraft.companionpets.training.TrainingMath.understanding(100, config.training()));
    }
    @Test void reversedThrowSpeedsAndNegativeLimitsAreRejected() throws Exception {
        for (String source : new String[]{"play: {throw-speed-low: 2, throw-speed-high: 1}",
                "play: {toy-attention-seconds: 0}", "play: {favorite-toy-attention-seconds: 0}",
                "play: {fetch-speed-multiplier: 0}", "play: {fetch-speed-multiplier: 4}",
                "limits: {max-stored: -1}", "limits: {max-out: -1}"}) {
            var yaml = new YamlConfiguration(); yaml.loadFromString(source);
            assertThrows(IllegalArgumentException.class, () -> CompanionConfig.load(MockBukkit.createMockPlugin(), yaml), source);
        }
    }
    @Test void invalidToyAttentionNumbersFallBackToDefaults() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("play: {toy-attention-seconds: -1, favorite-toy-attention-seconds: .nan}");
        var config = CompanionConfig.load(MockBukkit.createMockPlugin(), yaml);
        assertEquals(20, config.play().toyAttentionSeconds()); assertEquals(35, config.play().favoriteToyAttentionSeconds());
    }
}
