package net.tfminecraft.companionpets;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class CommandLifecycleTest {
    private ServerMock server;
    private PetsPlugin plugin;
    private PlayerMock player;

    @BeforeEach void setup() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(PetsPlugin.class);
        player = server.addPlayer();
        player.openInventory(server.createInventory(null, 9));
    }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    private List<String> complete(String... args) {
        return plugin.onTabComplete(player, plugin.getCommand("companionpets"), "companionpets", args);
    }
    private void command(String... args) {
        assertTrue(plugin.onCommand(player, plugin.getCommand("companionpets"), "companionpets", args));
    }

    @Test void removedInspectCommandHasNoCompletionOrHelpEntry() {
        player.setOp(true);
        assertTrue(complete("ins").isEmpty());
        assertTrue(complete("inspect", "").isEmpty());
        command("inspect", "wolf");
        String message; while ((message = player.nextMessage()) != null) assertFalse(message.contains("inspect"));
    }

    @Test void testCommandsAndTypesRequirePermission() {
        player.setOp(false);
        assertTrue(complete("testpet").isEmpty());
        assertTrue(complete("testpet", "").isEmpty());
        command("testpet", "wolf");
        assertTrue(player.nextMessage().contains("staff only"));
        player.setOp(true);
        assertEquals(List.of("testpet"), complete("testpet"));
        assertEquals(List.of("beagle", "bernesse", "bordercollie", "bordercolliegray", "cat", "catblack", "catfunny",
                "catgray", "catorange", "cattabby", "chihuahua", "corgi", "fox", "frog", "golden",
                "husky", "lagottoromagnolo", "mainecoon", "wolf", "yorkshire"), complete("testpet", ""));
    }



    @Test void invalidAndMissingTypesDoNotSpawnAnything() {
        player.setOp(true);
        int before = player.getWorld().getEntities().size();
        command("testpet");
        assertTrue(player.nextMessage().contains("testpet <configured-type>"));

        command("testpet", "not_a_type");
        assertTrue(player.nextMessage().contains("Unknown pet type"));
        assertEquals(before, player.getWorld().getEntities().size());
    }

    @Test void reloadUpdatesTypeCompletionAndRejectsBrokenYaml() throws Exception {
        player.setOp(true);
        File file = new File(plugin.getDataFolder(), "config.yml");
        var yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("pets.friend.entity", "CAT");
        yaml.set("pets.friend.egg", "FROG_SPAWN_EGG");
        yaml.save(file);
        command("reload");
        assertEquals(List.of("fox", "friend", "frog"), complete("testpet", "f"));
        java.nio.file.Files.writeString(file.toPath(), "pets: [broken\n");
        command("reload");
        assertEquals(List.of("fox", "friend", "frog"), complete("testpet", "f"), "Failed reload keeps active configuration");
    }

    @Test void operatorPreviewCompletionFiltersPrefixes() {
        player.setOp(true);
        assertEquals(List.of("belly"), complete("moment", "be"));
        assertEquals(List.of("greeting"), complete("moment", "gr"));
        assertTrue(complete("social", "sn").isEmpty());
        assertTrue(complete("personality", "fr").isEmpty());
        assertTrue(complete("testpet", "unknown").isEmpty());
        assertTrue(complete("testpet", "frog", "name").isEmpty());
    }

    @Test void impossibleTrainingThresholdDoesNotActivateReloadedConfig() throws Exception {
        player.setOp(true);
        File file = new File(plugin.getDataFolder(), "config.yml");
        var yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("training.learned-at", 200);
        yaml.set("pets.friend.entity", "CAT"); yaml.set("pets.friend.egg", "FROG_SPAWN_EGG"); yaml.save(file);
        command("reload");
        assertEquals(List.of("fox", "frog"), complete("testpet", "f"));
    }

    @Test void consoleCannotUsePlayerOnlyCommands() {
        var console = server.getConsoleSender();
        for (String[] args : List.of(new String[]{"moment", "belly"}, new String[]{"testpet", "wolf"})) {
            assertTrue(plugin.onCommand(console, plugin.getCommand("companionpets"), "companionpets", args));
            assertTrue(console.nextMessage().contains("in game"));
        }
    }

    @Test void onlyCanonicalRootDispatchesWithoutNamespace() {
        player.setOp(true);
        assertNotNull(server.getPluginCommand("companionpets"));
        assertNull(server.getPluginCommand("petcompanions"));
        assertTrue(server.dispatchCommand(player, "companionpets"));
        assertTrue(player.nextMessage().contains("staff commands"));
    }

    @Test void commandTreeContainsOnlyCanonicalCommandsAndRejectsRemovedDuplicates() {
        player.setOp(true);
        assertEquals(List.of("create", "egg", "find", "list", "moment", "reload", "testpet"), complete("")); assertFalse(complete("").contains("order"));
        assertFalse(complete("test", "").contains("needs"));
        for (String old : List.of("admin", "test", "order", "testdog", "social", "personality", "follow", "calm", "animation", "cleantestpets", "freeze", "heal", "needs", "validate", "transfer", "teach", "store", "remove", "rename", "recover")) {
            command(old); assertTrue(player.nextMessage().contains("staff commands")); while (player.nextMessage() != null) { }
            assertTrue(complete(old, "").isEmpty());
        }
    }
}
