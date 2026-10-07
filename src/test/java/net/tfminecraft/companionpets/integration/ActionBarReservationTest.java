package net.tfminecraft.companionpets.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import javax.tools.ToolProvider;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginManagerMock;

class ActionBarReservationTest {
    @TempDir static Path classes;
    private ProviderServer server;
    private PlayerMock player;
    private Bridge bridge;
    private final List<Provider> providers = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private Handler logs;

    @BeforeAll static void compileProviders() throws IOException {
        // Signatures verified against the cached MythicLib 1.7.1-SNAPSHOT jar:
        // MMOPlayerData.get(OfflinePlayer), getActionBar(): ActionBarHandler,
        // and ActionBarHandler.hide(int, long): boolean. Missing data throws NPE.
        for (String mode : List.of("valid", "missing", "incompatible", "linkage")) {
            Path output = Files.createDirectories(classes.resolve(mode));
            List<String> sources = new ArrayList<>();
            Path provider = output.resolve("Provider.java");
            Files.writeString(provider, """
                    package fixture;
                    import java.io.File;
                    import org.bukkit.Server;
                    import org.bukkit.plugin.PluginDescriptionFile;
                    import org.bukkit.plugin.java.JavaPluginLoader;
                    import org.mockbukkit.mockbukkit.plugin.PluginMock;
                    public class Provider extends PluginMock {
                        public Provider(Server server, File folder) {
                            super(new JavaPluginLoader(server),
                                new PluginDescriptionFile("MythicLib", "1", "fixture.Provider"), folder, folder);
                        }
                        public void active(boolean active) { setEnabled(active); }
                    }
                    """);
            sources.add(provider.toString());
            if (!mode.equals("missing")) {
                Path data = output.resolve("MMOPlayerData.java");
                Files.writeString(data, """
                        package io.lumine.mythic.lib.api.player;
                        import java.util.*;
                        import org.bukkit.OfflinePlayer;
                        import io.lumine.mythic.lib.message.actionbar.ActionBarHandler;
                        public class MMOPlayerData {
                            public static final Map<UUID, MMOPlayerData> players = new HashMap<>();
                            public static int reads;
                            private final ActionBarHandler actionBar;
                            public MMOPlayerData(UUID id) { actionBar = new ActionBarHandler(id); }
                            public static void register(OfflinePlayer player) {
                                players.put(player.getUniqueId(), new MMOPlayerData(player.getUniqueId()));
                            }
                            public static MMOPlayerData get(OfflinePlayer player) {
                                reads++;
                                return Objects.requireNonNull(players.get(player.getUniqueId()), "Player data not loaded");
                            }
                            public ActionBarHandler getActionBar() { return actionBar; }
                        }
                        """.replace("public static int reads;", mode.equals("linkage")
                                ? "public static int reads; static { if (Boolean.TRUE) throw new NoClassDefFoundError(); }"
                                : "public static int reads;"));
                Path handler = output.resolve("ActionBarHandler.java");
                Files.writeString(handler, """
                        package io.lumine.mythic.lib.message.actionbar;
                        import java.util.*;
                        public class ActionBarHandler {
                            public static final Map<UUID, List<long[]>> calls = new HashMap<>();
                            public static boolean accepted = true;
                            private final UUID id;
                            public ActionBarHandler(UUID id) { this.id = id; }
                            public boolean hide(int priority, long ticks) {
                                calls.computeIfAbsent(id, key -> new ArrayList<>()).add(new long[]{priority, ticks});
                                return accepted;
                            }
                        }
                        """.replace("hide(int priority, long ticks)", mode.equals("incompatible")
                                ? "hide(int priority, int ticks)" : "hide(int priority, long ticks)"));
                sources.add(data.toString()); sources.add(handler.toString());
            }
            var arguments = new ArrayList<>(List.of("-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()));
            arguments.addAll(sources);
            var compiler = ToolProvider.getSystemJavaCompiler();
            assertNotNull(compiler);
            assertEquals(0, compiler.run(null, null, null, arguments.toArray(String[]::new)));
        }
    }

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(new ProviderServer());
        player = server.addPlayer();
        bridge = new Bridge();
        logs = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getMessage().contains("MythicLib action bar API unavailable")) warnings.add(record.getMessage());
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        server.getLogger().addHandler(logs);
    }

    @AfterEach void cleanup() throws Exception {
        if (logs != null) server.getLogger().removeHandler(logs);
        if (bridge != null) bridge.close();
        for (Provider provider : providers) provider.close();
        MockBukkit.unmock();
    }

    @Test void providerEnabledAfterAnEarlierAbsentLookupReceivesReservations() throws Exception {
        bridge.reserve(player, 60);
        var provider = provider("valid"); provider.register(player);
        bridge.reserve(player, 60);
        assertEquals(1, provider.calls(player).size(), "Absence must not be permanently cached");
        assertArrayEquals(new long[]{30, 60}, provider.calls(player).getFirst());
    }

    @Test void disabledProvidersAreSkippedEvenAfterTheirApiWasCached() throws Exception {
        var provider = provider("valid"); provider.register(player);
        bridge.reserve(player, 60);
        provider.active(false);
        bridge.reserve(player, 80);
        assertEquals(1, provider.calls(player).size(), "Disabled provider must not receive cached callbacks");
        provider.active(true);
        bridge.reserve(player, 100);
        assertEquals(2, provider.calls(player).size());
        assertArrayEquals(new long[]{30, 100}, provider.calls(player).getLast());
    }

    @Test void replacementUsesItsOwnClassloaderAndNeverTheOldPlayerData() throws Exception {
        var first = provider("valid"); first.register(player);
        bridge.reserve(player, 60);
        first.active(false);
        var replacement = provider("valid"); replacement.register(player);
        bridge.reserve(player, 90);
        assertEquals(1, replacement.calls(player).size(), "Replacement must receive the reservation");
        assertEquals(1, first.calls(player).size(), "Old provider must stop receiving reservations");
        assertArrayEquals(new long[]{30, 90}, replacement.calls(player).getFirst());
    }

    @Test void absentAndInitiallyDisabledProvidersStaySilentAndCanBeEnabledLater() throws Exception {
        bridge.reserve(player, 60); bridge.reserve(player, 60);
        assertTrue(warnings.isEmpty());
        var provider = provider("valid"); provider.register(player); provider.active(false);
        bridge.reserve(player, 60);
        assertTrue(provider.calls(player).isEmpty());
        provider.active(true);
        bridge.reserve(player, 75);
        assertArrayEquals(new long[]{30, 75}, provider.calls(player).getFirst());
        assertTrue(warnings.isEmpty());
    }

    @Test void compatibleApiKeepsPlayersDurationsAndProviderPriorityRejectionIndependent() throws Exception {
        var provider = provider("valid"); provider.register(player);
        var second = server.addPlayer(); provider.register(second);
        bridge.reserve(player, 60); bridge.reserve(player, 120); bridge.reserve(second, 35);
        assertEquals(2, provider.calls(player).size()); assertEquals(1, provider.calls(second).size());
        assertArrayEquals(new long[]{30, 60}, provider.calls(player).getFirst());
        assertArrayEquals(new long[]{30, 120}, provider.calls(player).getLast());
        assertArrayEquals(new long[]{30, 35}, provider.calls(second).getFirst());
        provider.loadClass("io.lumine.mythic.lib.message.actionbar.ActionBarHandler").getField("accepted").set(null, false);
        bridge.reserve(player, 20);
        assertArrayEquals(new long[]{30, 20}, provider.calls(player).getLast());
        assertTrue(warnings.isEmpty(), "A higher-priority provider message rejecting hide is not an API error");
        assertNull(player.nextMessage(), "Reservation does not send or replace the notice itself");
    }

    @Test void unavailablePlayerDataIsIgnoredAndLaterRegistrationRecoversWithoutRebuildingTheApi() throws Exception {
        var provider = provider("valid");
        assertDoesNotThrow(() -> bridge.reserve(player, 60));
        assertTrue(provider.calls(player).isEmpty());
        provider.register(player);
        bridge.reserve(player, 60);
        assertArrayEquals(new long[]{30, 60}, provider.calls(player).getFirst());
        assertEquals(2, provider.loadClass("io.lumine.mythic.lib.api.player.MMOPlayerData").getField("reads").get(null));
        assertTrue(warnings.isEmpty(), "Missing data is transient; it must not warn about an incompatible API");
    }

    @Test void missingApiWarnsOnceAndACompatibleReplacementRecovers() throws Exception {
        var broken = provider("missing");
        bridge.reserve(player, 60); bridge.reserve(player, 60);
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("pet notices may be overwritten"));
        broken.active(false);
        var recovered = provider("valid"); recovered.register(player);
        bridge.reserve(player, 60);
        assertEquals(1, recovered.calls(player).size());
        assertEquals(1, warnings.size());
    }

    @Test void incompatibleReplacementCannotReuseOldMethodsAndWarnsOnlyOnce() throws Exception {
        var original = provider("valid"); original.register(player);
        bridge.reserve(player, 60);
        original.active(false);
        var incompatible = provider("incompatible"); incompatible.register(player);
        bridge.reserve(player, 80); bridge.reserve(player, 80);
        assertEquals(1, warnings.size());
        assertTrue(incompatible.calls(player).isEmpty());
        assertEquals(1, original.calls(player).size());
        incompatible.active(false);
        var recovered = provider("valid"); recovered.register(player);
        bridge.reserve(player, 100);
        assertArrayEquals(new long[]{30, 100}, recovered.calls(player).getFirst());
    }

    @Test void brokenProviderLinkageFailsGracefullyAndUnregistrationDropsCachedMethods() throws Exception {
        provider("linkage");
        assertDoesNotThrow(() -> bridge.reserve(player, 60));
        assertDoesNotThrow(() -> bridge.reserve(player, 60));
        assertEquals(1, warnings.size());
        var provider = provider("valid"); provider.register(player);
        bridge.reserve(player, 60);
        server.provider = null;
        bridge.reserve(player, 90);
        assertEquals(1, provider.calls(player).size());
        server.provider = provider.plugin;
        bridge.reserve(player, 110);
        assertArrayEquals(new long[]{30, 110}, provider.calls(player).getLast());
    }

    private Provider provider(String mode) throws Exception {
        var provider = new Provider(mode);
        providers.add(provider); server.provider = provider.plugin;
        provider.active(true);
        return provider;
    }

    private static final class ProviderServer extends ServerMock {
        private Plugin provider;
        private final PluginManagerMock plugins = new PluginManagerMock(this) {
            @Override public Plugin getPlugin(String name) {
                return name.equals("MythicLib") ? provider : super.getPlugin(name);
            }
        };
        @Override public PluginManagerMock getPluginManager() { return plugins == null ? super.getPluginManager() : plugins; }
    }

    private final class Provider extends URLClassLoader {
        private final Plugin plugin;
        Provider(String mode) throws Exception {
            super(new URL[]{classes.resolve(mode).toUri().toURL()}, ActionBarReservationTest.class.getClassLoader());
            plugin = (Plugin) loadClass("fixture.Provider").getConstructor(Server.class, File.class)
                    .newInstance(server, classes.resolve(mode).toFile());
        }
        void active(boolean active) throws Exception { plugin.getClass().getMethod("active", boolean.class).invoke(plugin, active); }
        void register(Player player) throws Exception { loadClass("io.lumine.mythic.lib.api.player.MMOPlayerData").getMethod("register", OfflinePlayer.class).invoke(null, player); }
        @SuppressWarnings("unchecked") List<long[]> calls(Player player) throws Exception {
            var calls = (Map<UUID, List<long[]>>) loadClass("io.lumine.mythic.lib.message.actionbar.ActionBarHandler").getField("calls").get(null);
            return calls.getOrDefault(player.getUniqueId(), List.of());
        }
    }

    private static final class Bridge extends URLClassLoader {
        private final Class<?> bridge;
        Bridge() throws Exception {
            super(new URL[0], ActionBarReservation.class.getClassLoader());
            bridge = loadClass(ActionBarReservation.class.getName());
        }
        void reserve(Player player, long ticks) throws Exception { bridge.getMethod("reserve", Player.class, long.class).invoke(null, player, ticks); }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(ActionBarReservation.class.getName())) return super.loadClass(name, resolve);
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try (var stream = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (stream == null) throw new ClassNotFoundException(name);
                    byte[] bytes = stream.readAllBytes();
                    loaded = defineClass(name, bytes, 0, bytes.length, ActionBarReservation.class.getProtectionDomain());
                } catch (IOException ex) { throw new ClassNotFoundException(name, ex); }
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }
}
