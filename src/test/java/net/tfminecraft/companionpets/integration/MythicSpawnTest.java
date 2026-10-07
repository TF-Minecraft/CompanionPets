package net.tfminecraft.companionpets.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.tools.ToolProvider;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

class MythicSpawnTest {
    @TempDir static Path classes;
    private static final String API = "io.lumine.mythic.bukkit.MythicBukkit";

    @BeforeAll static void compileProvider() throws IOException {
        // Keep provider classes outside the suite classpath: other tests must still see MythicMobs as absent.
        Path api = classes.resolve("MythicBukkit.java");
        Files.writeString(api, """
                package io.lumine.mythic.bukkit;
                import java.util.Optional;
                public class MythicBukkit {
                    public static Object mob, entity, lastLocation;
                    public static Number lastLevel;
                    public static String lastId;
                    public static boolean lookupFails;
                    public static MythicBukkit inst() { return new MythicBukkit(); }
                    public Manager getMobManager() { return new Manager(); }
                    public static class Manager {
                        public Optional<Object> getMythicMob(String id) {
                            lastId = id;
                            if (lookupFails) throw new IllegalStateException("Provider is unavailable");
                            return Optional.ofNullable(mob);
                        }
                    }
                    public static class FloatMob {
                        public Active spawn(BukkitAdapter.AbstractLocation at, float level) {
                            lastLocation = at; lastLevel = level; return new Active();
                        }
                    }
                    public static class DoubleMob {
                        public Active spawn(BukkitAdapter.AbstractLocation at, double level) {
                            lastLocation = at; lastLevel = level; return new Active();
                        }
                    }
                    public static class UnsupportedMob {
                        public Active spawn(BukkitAdapter.AbstractLocation at) { throw new AssertionError(); }
                        public Active spawn(BukkitAdapter.AbstractLocation at, int level) { throw new AssertionError(); }
                        public Active spawn(String at, double level) { throw new AssertionError(); }
                    }
                    public static class NullMob {
                        public Active spawn(BukkitAdapter.AbstractLocation at, double level) { return null; }
                    }
                    public static class FailingMob {
                        public Active spawn(BukkitAdapter.AbstractLocation at, double level) {
                            throw new IllegalStateException("Spawn rejected");
                        }
                    }
                    public static class Active {
                        public AbstractEntity getEntity() { return new AbstractEntity(); }
                    }
                    public static class AbstractEntity {
                        public org.bukkit.entity.Entity getBukkitEntity() {
                            return (org.bukkit.entity.Entity) entity;
                        }
                    }
                }
                """);
        Path adapter = classes.resolve("BukkitAdapter.java");
        Files.writeString(adapter, """
                package io.lumine.mythic.bukkit;
                public class BukkitAdapter {
                    public static AbstractLocation adapt(org.bukkit.Location location) {
                        return new AbstractLocation(location);
                    }
                    public record AbstractLocation(org.bukkit.Location bukkitLocation) { }
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Provider boundary tests need the project's JDK 21");
        assertEquals(0, compiler.run(null, null, null, "-proc:none", "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString(), api.toString(), adapter.toString()));
    }

    @AfterEach void cleanup() { MockBukkit.unmock(); }

    @Test void floatOnlySpawnReceivesFloatLevelAndReturnsTheProviderEntity() throws Exception {
        assertSpawn("FloatMob", Float.valueOf(1));
    }

    @Test void doubleOnlySpawnReceivesDoubleLevelAndReturnsTheProviderEntity() throws Exception {
        assertSpawn("DoubleMob", Double.valueOf(1));
    }

    private void assertSpawn(String mobClass, Number expectedLevel) throws Exception {
        var server = MockBukkit.mock();
        Entity entity = server.addPlayer();
        Location location = entity.getLocation();
        try (var fixture = new Provider(true)) {
            fixture.mob(mobClass);
            fixture.set("entity", entity);
            assertTrue(fixture.available());
            assertTrue(fixture.registered("companion"));
            assertSame(entity, fixture.spawn(location));
            assertEquals("companion", fixture.get("lastId"));
            assertEquals(expectedLevel, fixture.get("lastLevel"));
            Object adapted = fixture.get("lastLocation");
            assertSame(location, adapted.getClass().getMethod("bukkitLocation").invoke(adapted));
            assertTrue(fixture.messages.isEmpty());
        }
    }

    @Test void absentProviderIsUnavailableAndSpawnFailureIsLogged() throws Exception {
        try (var fixture = new Provider(false)) {
            assertFalse(fixture.available());
            assertFalse(fixture.registered("companion"));
            assertNull(fixture.spawn(new Location(null, 1, 2, 3)));
            assertTrue(fixture.messages.getFirst().getMessage().contains("Could not spawn MythicMob companion"));
            assertInstanceOf(ClassNotFoundException.class, fixture.messages.getFirst().getThrown());
        }
    }

    @Test void missingDefinitionIsNotRegisteredAndDoesNotAttemptToSpawn() throws Exception {
        try (var fixture = new Provider(true)) {
            assertTrue(fixture.available());
            assertFalse(fixture.registered("companion"));
            assertNull(fixture.spawn(new Location(null, 1, 2, 3)));
            assertTrue(fixture.messages.getFirst().getMessage().contains("companion is not loaded"));
            assertNull(fixture.get("lastLevel"));
        }
    }

    @Test void unrelatedSpawnOverloadsAreIgnored() throws Exception {
        try (var fixture = new Provider(true)) {
            fixture.mob("UnsupportedMob");
            assertNull(fixture.spawn(new Location(null, 1, 2, 3)));
            assertTrue(fixture.messages.getFirst().getMessage().contains("no spawn(location, level) method"));
            assertNull(fixture.messages.getFirst().getThrown(), "Do not invoke incompatible overloads");
        }
    }

    @Test void providerRefusingToSpawnReturnsNoEntity() throws Exception {
        try (var fixture = new Provider(true)) {
            fixture.mob("NullMob");
            assertNull(fixture.spawn(new Location(null, 1, 2, 3)));
        }
    }

    @Test void missingBukkitEntityDoesNotBecomeACompanionBody() throws Exception {
        try (var fixture = new Provider(true)) {
            fixture.mob("DoubleMob");
            assertNull(fixture.spawn(new Location(null, 1, 2, 3)));
            assertEquals(Double.valueOf(1), fixture.get("lastLevel"));
        }
    }

    @Test void providerLookupFailureIsNotRegisteredAndCannotSpawn() throws Exception {
        try (var fixture = new Provider(true)) {
            fixture.set("lookupFails", true);
            assertFalse(fixture.registered("companion"));
            assertNull(fixture.spawn(new Location(null, 1, 2, 3)));
            assertEquals("Provider is unavailable", fixture.messages.getFirst().getThrown().getCause().getMessage());
        }
    }

    @Test void spawnExceptionIsLoggedAndReturnsNoEntity() throws Exception {
        try (var fixture = new Provider(true)) {
            fixture.mob("FailingMob");
            assertNull(fixture.spawn(new Location(null, 1, 2, 3)));
            assertEquals("Spawn rejected", fixture.messages.getFirst().getThrown().getCause().getMessage());
        }
    }

    private static final class Provider extends URLClassLoader {
        private final boolean present;
        private final Class<?> bridge;
        private final Logger logger = Logger.getAnonymousLogger();
        private final List<LogRecord> messages = new ArrayList<>();

        Provider(boolean present) throws Exception {
            super(new URL[]{classes.toUri().toURL()}, MythicSpawn.class.getClassLoader());
            this.present = present;
            bridge = loadClass(MythicSpawn.class.getName());
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override public void publish(LogRecord record) { messages.add(record); }
                @Override public void flush() { }
                @Override public void close() { }
            });
        }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null && name.equals(MythicSpawn.class.getName())) {
                    try (var bytes = MythicSpawn.class.getResourceAsStream("MythicSpawn.class")) {
                        if (bytes == null) throw new ClassNotFoundException(name);
                        byte[] code = bytes.readAllBytes();
                        type = defineClass(name, code, 0, code.length, MythicSpawn.class.getProtectionDomain());
                    } catch (IOException ex) { throw new ClassNotFoundException(name, ex); }
                } else if (type == null && name.startsWith("io.lumine.mythic.bukkit.")) {
                    if (!present) throw new ClassNotFoundException(name);
                    type = findClass(name);
                }
                if (type == null) return super.loadClass(name, resolve);
                if (resolve) resolveClass(type);
                return type;
            }
        }

        void mob(String name) throws Exception { set("mob", loadClass(API + "$" + name).getConstructor().newInstance()); }
        void set(String field, Object value) throws Exception { loadClass(API).getField(field).set(null, value); }
        Object get(String field) throws Exception { return loadClass(API).getField(field).get(null); }
        boolean available() throws Exception { return (boolean) bridge.getMethod("available").invoke(null); }
        boolean registered(String id) throws Exception { return (boolean) bridge.getMethod("registered", String.class).invoke(null, id); }
        Entity spawn(Location location) throws Exception {
            return (Entity) bridge.getMethod("spawn", String.class, Location.class, Logger.class).invoke(null, "companion", location, logger);
        }
    }
}
