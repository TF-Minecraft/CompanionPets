package net.tfminecraft.companionpets.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import javax.tools.ToolProvider;
import java.util.UUID;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.body.PetPlacement;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.visual.PetVisual;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.integration.MythicSpawn;
import net.tfminecraft.companionpets.listen.PetListener;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetSex;
import net.tfminecraft.companionpets.pet.Trick;
import net.tfminecraft.companionpets.session.HatchPrompt;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.testutil.GoalServerMock;
import net.tfminecraft.companionpets.testutil.CollisionWorldMock;
import net.tfminecraft.companionpets.visual.IdleVisual;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

@SuppressWarnings("deprecation")
class PetHatchingCoverageTest {
    @TempDir Path providerClasses;
    private GoalServerMock server;
    private WorldMock world;
    private PlayerMock player;
    private PetRuntime runtime;
    private PetActions actions;
    private YamlConfiguration yaml;
    private WolfMock lastSpawned;
    private final List<Boolean> taggedTeleports = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(new GoalServerMock());
        world = new CollisionWorldMock() {
            private final java.util.Map<String, BlockMock> blocks = new java.util.HashMap<>();
            @Override public BlockMock getBlockAt(int x, int y, int z) {
                return blocks.computeIfAbsent(x + ":" + y + ":" + z, key ->
                        new BlockMock(y == 63 ? Material.STONE : Material.AIR, new Location(this, x, y, z)) {
                            @Override public boolean isPassable() { return !getType().isSolid(); }
                        });
            }
            @Override public <T extends Entity> T spawn(Location at, Class<T> type) {
                if (type != Wolf.class) return super.spawn(at, type);
                var wolf = new WolfMock(server, UUID.randomUUID()) {
                    @Override public void setRemoveWhenFarAway(boolean remove) { }
                };
                server.registerEntity(wolf);
                lastSpawned = wolf;
                wolf.teleport(at);
                return type.cast(wolf);
            }
        };
        server.addWorld(world);
        player = server.addPlayer();
        player.teleport(new Location(world, 0, 64, 0));
        var plugin = MockBukkit.createMockPlugin();
        yaml = new YamlConfiguration();
        yaml.loadFromString("""
                limits: {max-out: 2, max-stored: 2}
                items: {toys: [STICK]}
                pets:
                  wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG, sex: choose, default-tricks: [follow]}
                """);
        var visual = new IdleVisual();
        var key = new NamespacedKey(plugin, "pet");
        var store = new PetStore(plugin);
        assertTrue(store.load());
        runtime = new PetRuntime(plugin, CompanionConfig.load(plugin, yaml), store, new Sessions(),
                new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        actions = new PetActions(runtime);
        server.getPluginManager().registerEvents(new PetListener(runtime, actions), plugin);
        onTaggedTeleport(EventPriority.MONITOR, event -> taggedTeleports.add(event.isCancelled()));
    }

    @AfterEach void cleanup() {
        if (actions != null) actions.holograms().clear();
        if (runtime != null) runtime.store().close();
        if (player != null) PetFx.clearPlayer(player.getUniqueId());
        MockBukkit.unmock();
    }

    private HatchPrompt begin() {
        player.getInventory().setItemInMainHand(new ItemStack(Material.WOLF_SPAWN_EGG, 2));
        var event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR,
                player.getInventory().getItemInMainHand(), null, BlockFace.SELF, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled(), "custom eggs must not also trigger vanilla spawning");
        var prompt = runtime.sessions().hatch(player.getUniqueId());
        assertNotNull(prompt);
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        return prompt;
    }

    private AsyncPlayerChatEvent queue(String text) {
        var event = new AsyncPlayerChatEvent(false, player, text, new HashSet<>(server.getOnlinePlayers()));
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled(), "private hatch answers must not leak to public chat");
        return event;
    }

    private void chat(String text) {
        queue(text);
        server.getScheduler().performOneTick();
    }

    private void namePet() {
        chat("female");
        chat("Luna");
    }

    private String messages() {
        var messages = new StringBuilder();
        String line;
        while ((line = player.nextMessage()) != null) messages.append(line).append('\n');
        return messages.toString();
    }

    private void onTaggedTeleport(EventPriority priority, java.util.function.Consumer<EntityTeleportEvent> handler) {
        server.getPluginManager().registerEvent(EntityTeleportEvent.class, new Listener() { }, priority,
                (unused, raw) -> {
                    var event = (EntityTeleportEvent) raw;
                    if (runtime.bodies().readId(event.getEntity()) != null) handler.accept(event);
                }, runtime.plugin());
    }

    private void assertSafePlacement(Entity entity) {
        Location at = entity.getLocation();
        assertEquals(64, at.getY());
        assertEquals(.5, at.getX() - Math.floor(at.getX()));
        assertEquals(.5, at.getZ() - Math.floor(at.getZ()));
        assertTrue(PetPlacement.safe(at, PetPlacement.bounds(entity)), "spawned body must fit above the solid floor");
    }

    private Pet onlyPet() {
        assertEquals(1, runtime.store().all().size());
        return runtime.store().all().iterator().next();
    }

    private void reload(String key, Object value) {
        yaml.set(key, value);
        runtime.config(CompanionConfig.load(runtime.plugin(), yaml));
    }

    @Test void invalidAnswersRetryWithoutConsumingTheEggAndConfirmationPersistsARealPet() {
        HatchPrompt prompt = begin();
        chat("neither");
        assertNull(prompt.sex());
        assertTrue(messages().contains("Type \"male\" or \"female\""));
        queue("girl");
        assertNull(prompt.sex(), "chat events must defer state mutation to the main tick");
        server.getScheduler().performOneTick();
        assertEquals(PetSex.FEMALE, prompt.sex());
        chat("   §  ");
        assertNull(prompt.name());
        assertTrue(messages().contains("That name is empty"));
        chat("  Luna   Moon  ");
        assertEquals("Luna Moon", prompt.name());
        chat("maybe");
        assertSame(prompt, runtime.sessions().hatch(player.getUniqueId()));
        assertTrue(messages().contains("Type \"yes\" to confirm"));
        assertTrue(runtime.store().all().isEmpty());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());

        chat("yes");

        Pet pet = onlyPet();
        assertEquals(List.of(false), taggedTeleports,
                "the pet listener must allow verified initial placement before the new pet's location is remembered");
        assertNull(runtime.sessions().hatch(player.getUniqueId()));
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
        assertEquals("Luna Moon", pet.name());
        assertEquals(PetSex.FEMALE, pet.sex());
        assertEquals(player.getUniqueId(), pet.ownerId());
        assertEquals(100, pet.progress(Trick.FOLLOW));
        assertEquals("STICK", pet.favoriteToy());
        assertTrue(pet.bornAt() > 0);
        assertFalse(pet.stored());
        assertInstanceOf(Wolf.class, runtime.entity(pet));
        assertSafePlacement(runtime.entity(pet));
        assertEquals(player.getUniqueId(), ((Wolf) runtime.entity(pet)).getOwner().getUniqueId());
        assertEquals(pet.id(), runtime.bodies().readId(runtime.entity(pet)));
        assertTrue(runtime.store().pending());
        assertTrue(messages().contains("Already learned:"));
        assertTrue(runtime.store().save());
        assertTrue(runtime.store().load());
        assertEquals(pet.id(), onlyPet().id());
        assertEquals("Luna Moon", onlyPet().name());
    }

    @Test void removingATypeDuringTheDialogueClearsThePromptAndKeepsTheEgg() {
        begin();
        reload("pets.wolf", null);
        assertNull(runtime.config().type("wolf"));
        chat("female");
        assertNull(runtime.sessions().hatch(player.getUniqueId()));
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertTrue(runtime.store().all().isEmpty());
        assertFalse(runtime.store().pending());
    }

    @Test void providerSpawnFailureKeepsTheConsumedEggAsASavedPetInTheHouse() {
        MockBukkit.createMockPlugin("MythicMobs");
        reload("pets.wolf.mythic-mob", "UnavailableWolf");
        assertNotNull(runtime.config().type("wolf"));
        assertFalse(MythicSpawn.available(), "fixture plugin advertises integration while its provider API is unavailable");
        begin();
        namePet();
        chat("yes");

        Pet pet = onlyPet();
        assertTrue(pet.stored());
        assertNull(pet.entityId());
        assertNull(runtime.entity(pet));
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
        assertTrue(messages().contains("waiting for you in the Pet House"));
        assertTrue(runtime.store().pending());
        assertTrue(runtime.store().save());
        assertTrue(runtime.store().load());
        assertTrue(onlyPet().stored());
        assertEquals(pet.id(), onlyPet().id());
        assertEquals("Luna", onlyPet().name());
        assertEquals(100, onlyPet().progress(Trick.FOLLOW));
    }

    @Test void externalCancellationStillStopsInitialPlacementAndStoresThePet() {
        onTaggedTeleport(EventPriority.HIGH, event -> event.setCancelled(true));
        begin(); namePet(); chat("yes");
        assertEquals(List.of(true), taggedTeleports);
        assertStoredAfterRejectedPlacement();
    }

    @Test void anExternalUnsafeRetargetCannotLeaveANewPetInsideTheFloor() {
        onTaggedTeleport(EventPriority.HIGHEST, event -> event.setTo(event.getTo().clone().subtract(0, 1, 0)));
        begin(); namePet(); chat("yes");
        assertEquals(List.of(false), taggedTeleports, "external retarget is checked after the actual teleport");
        assertStoredAfterRejectedPlacement();
    }

    @Test void anExternalSafeRetargetIsRememberedAfterInitialPlacementCompletes() {
        Location destination = new Location(world, 4.5, 64, 4.5);
        onTaggedTeleport(EventPriority.HIGHEST, event -> {
            Pet pending = onlyPet();
            assertNull(pending.entityId(), "a spawn must not be remembered before teleport listeners finish");
            assertNull(runtime.entity(pending));
            event.setTo(destination.clone());
        });
        begin(); namePet(); chat("yes");
        Pet pet = onlyPet();
        assertEquals(List.of(false), taggedTeleports);
        assertFalse(pet.stored());
        assertEquals(destination, runtime.entity(pet).getLocation());
        assertEquals(destination.getX(), pet.x());
        assertEquals(destination.getY(), pet.y());
        assertEquals(destination.getZ(), pet.z());
        assertSafePlacement(runtime.entity(pet));
        assertFalse(runtime.bodies().recovering(lastSpawned));
        assertFalse(runtime.bodies().claimRecoveryTeleport(lastSpawned, destination));
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
    }

    private void assertStoredAfterRejectedPlacement() {
        Pet pet = onlyPet();
        assertTrue(pet.stored());
        assertNull(pet.entityId());
        assertNull(runtime.entity(pet));
        assertNotNull(lastSpawned);
        assertFalse(lastSpawned.isValid(), "rejected body must not remain orphaned");
        assertFalse(runtime.bodies().recovering(lastSpawned), "initial placement authorization must be scoped to the teleport");
        assertFalse(runtime.bodies().claimRecoveryTeleport(lastSpawned, lastSpawned.getLocation()));
        assertFalse(runtime.bodies().protectedFromSuffocation(lastSpawned));
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
        assertTrue(runtime.store().pending());
        assertTrue(messages().contains("waiting for you in the Pet House"));
    }

    @Test void droppingTheEggBeforeQueuedConfirmationLeavesTheWorldItemIntact() {
        begin();
        namePet();
        queue("yes");
        var dropped = world.dropItem(player.getLocation(), player.getInventory().getItemInMainHand().clone());
        player.getInventory().setItemInMainHand(null);
        server.getScheduler().performOneTick();
        assertNull(runtime.sessions().hatch(player.getUniqueId()));
        assertTrue(runtime.store().all().isEmpty());
        assertTrue(dropped.isValid());
        assertEquals(new ItemStack(Material.WOLF_SPAWN_EGG, 2), dropped.getItemStack());
        assertTrue(messages().contains("egg left your hand"));
        assertFalse(runtime.store().pending());
    }

    @Test void consumingTheEggElsewhereBeforeQueuedConfirmationCannotCreateAFreePet() {
        begin();
        namePet();
        queue("yes");
        player.getInventory().getItemInMainHand().setAmount(0);
        server.getScheduler().performOneTick();
        assertNull(runtime.sessions().hatch(player.getUniqueId()));
        assertTrue(runtime.store().all().isEmpty());
        assertTrue(messages().contains("egg left your hand"));
        assertFalse(runtime.store().pending());
    }

    @Test void creativeConfirmationKeepsTheEggAndRandomSexSkipsTheFirstQuestion() {
        reload("pets.wolf.sex", "random");
        player.setGameMode(GameMode.CREATIVE);
        runtime.random().setSeed(0);
        HatchPrompt prompt = begin();
        PetSex announced = prompt.sex();
        assertNotNull(announced);
        assertTrue(messages().contains("Type a name in chat"));
        chat("Toby");
        chat("yes");
        assertEquals(announced, onlyPet().sex());
        assertEquals("Toby", onlyPet().name());
        assertNotNull(runtime.entity(onlyPet()));
        assertSafePlacement(runtime.entity(onlyPet()));
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
    }

    @Test void cancellingAndExpiringRealPromptsPreserveEggsAndReleasePrivateChat() {
        begin();
        chat("male");
        chat("Toby");
        chat("no");
        assertNull(runtime.sessions().hatch(player.getUniqueId()));
        assertTrue(messages().contains("Hatching cancelled"));
        HatchPrompt expiring = begin();
        assertTrue(new PetHatching(runtime).chat(player, "female", expiring.expiresAt() + 1));
        assertNull(runtime.sessions().hatch(player.getUniqueId()));
        assertTrue(messages().contains("You took too long"));
        assertFalse(new PetHatching(runtime).chat(player, "ordinary chat", expiring.expiresAt() + 1));
        assertTrue(runtime.store().all().isEmpty());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
    }

    @Test void mythicWolfWithoutNativeFollowingIsRemovedBeforeItCanBecomeAPet() throws Exception {
        MockBukkit.createMockPlugin("MythicMobs");
        reload("pets.wolf.mythic-mob", "UntrainableWolf");
        compileMythicProvider();
        var logs = new ArrayList<LogRecord>();
        var handler = new Handler() {
            @Override public void publish(LogRecord record) { logs.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        runtime.plugin().getLogger().addHandler(handler);
        // Child-load only this integration and its API. No provider classes leak into other tests.
        try (var loader = new URLClassLoader(new URL[]{providerClasses.toUri().toURL()}, Bodies.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    boolean production = name.startsWith("net.tfminecraft.companionpets.body.")
                            || name.equals(MythicSpawn.class.getName());
                    if (loaded == null && production) {
                        try (var source = Bodies.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (source == null) throw new ClassNotFoundException(name);
                            byte[] bytes = source.readAllBytes();
                            loaded = defineClass(name, bytes, 0, bytes.length, Bodies.class.getProtectionDomain());
                        } catch (IOException ex) { throw new ClassNotFoundException(name, ex); }
                    } else if (loaded == null && name.startsWith("io.lumine.mythic.bukkit.")) loaded = findClass(name);
                    if (loaded == null) return super.loadClass(name, resolve);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        }) {
            Class<?> api = loader.loadClass("io.lumine.mythic.bukkit.MythicBukkit");
            Entity wolf = world.spawn(player.getLocation(), Wolf.class);
            api.getField("entity").set(null, wolf);
            Class<?> bodiesType = loader.loadClass(Bodies.class.getName());
            Object bodies = bodiesType.getConstructor(JavaPlugin.class, NamespacedKey.class, PetVisual.class)
                    .newInstance(runtime.plugin(), runtime.petKey(), runtime.visual());
            Pet candidate = new Pet(UUID.randomUUID(), player.getUniqueId(), "wolf", "Luna", PetSex.FEMALE);

            Object spawned = bodiesType.getMethod("spawn", Pet.class, PetTypeDef.class, Location.class, Player.class)
                    .invoke(bodies, candidate, runtime.config().type("wolf"), player.getLocation(), player);

            assertNull(spawned);
            assertFalse(wolf.isValid(), "a provider body without native following must not remain orphaned");
            assertNull(runtime.bodies().readId(wolf));
            assertNull(candidate.entityId());
            assertEquals("UntrainableWolf", api.getField("lastId").get(null));
            assertEquals(1, api.getField("spawns").getInt(null));
            assertTrue(logs.stream().anyMatch(log -> log.getMessage().contains("missing the native FollowOwner goal")));
            assertTrue(runtime.store().all().isEmpty());
        } finally {
            runtime.plugin().getLogger().removeHandler(handler);
        }
        assertFalse(MythicSpawn.available(), "isolated provider fixture must not affect the main test classpath");
    }

    private void compileMythicProvider() throws IOException {
        Path api = providerClasses.resolve("MythicBukkit.java");
        Files.writeString(api, """
                package io.lumine.mythic.bukkit;
                public class MythicBukkit {
                    public static org.bukkit.entity.Entity entity;
                    public static String lastId;
                    public static int spawns;
                    public static MythicBukkit inst() { return new MythicBukkit(); }
                    public Manager getMobManager() { return new Manager(); }
                    public static class Manager {
                        public java.util.Optional<Definition> getMythicMob(String id) {
                            lastId = id; return java.util.Optional.of(new Definition());
                        }
                    }
                    public static class Definition {
                        public Active spawn(BukkitAdapter.AbstractLocation location, double level) {
                            spawns++; return new Active();
                        }
                    }
                    public static class Active {
                        public AbstractEntity getEntity() { return new AbstractEntity(); }
                    }
                    public static class AbstractEntity {
                        public org.bukkit.entity.Entity getBukkitEntity() { return entity; }
                    }
                }
                """);
        Path adapter = providerClasses.resolve("BukkitAdapter.java");
        Files.writeString(adapter, """
                package io.lumine.mythic.bukkit;
                public class BukkitAdapter {
                    public static AbstractLocation adapt(org.bukkit.Location location) { return new AbstractLocation(location); }
                    public record AbstractLocation(org.bukkit.Location location) { }
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "provider boundary fixture requires the project JDK");
        assertEquals(0, compiler.run(null, null, null, "-proc:none", "-classpath", System.getProperty("java.class.path"),
                "-d", providerClasses.toString(), api.toString(), adapter.toString()));
    }

    @Test void disconnectingBeforeQueuedConfirmationCannotHatchAPet() {
        begin();
        namePet();
        queue("yes");
        player.disconnect();
        server.getScheduler().performOneTick();
        assertNull(runtime.sessions().hatch(player.getUniqueId()));
        assertTrue(runtime.store().all().isEmpty());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
    }
}
