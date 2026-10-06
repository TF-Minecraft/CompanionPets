package net.tfminecraft.companionpets;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class ConfigurationStartupTest {
    public static class InvalidLimitsPlugin extends PetsPlugin {
        @Override public void saveDefaultConfig() { }
        @Override public FileConfiguration getConfig() {
            var config = new YamlConfiguration();
            config.set("limits.max-out", -1);
            return config;
        }
    }

    @AfterEach void cleanup() { MockBukkit.unmock(); }

    @Test void invalidConfigDisablesPluginBeforeOpeningStoreOrSchedulingTasks() throws Exception {
        MockBukkit.mock();
        var plugin = assertDoesNotThrow(() -> MockBukkit.load(InvalidLimitsPlugin.class));
        assertFalse(plugin.isEnabled());
        assertFalse(new java.io.File(plugin.getDataFolder(), "pets.yml").exists());
        for (String name : new String[]{"store", "ticker", "visualTicker", "autosave"}) {
            var field = PetsPlugin.class.getDeclaredField(name);
            field.setAccessible(true);
            assertNull(field.get(plugin));
        }
    }
}
