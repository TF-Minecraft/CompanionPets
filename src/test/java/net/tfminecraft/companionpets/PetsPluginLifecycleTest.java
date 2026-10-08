package net.tfminecraft.companionpets;

import static org.junit.jupiter.api.Assertions.*;

import com.destroystokyo.paper.entity.ai.GoalKey;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.gui.MenuHolder;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetSex;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.companionpets.session.RenamePrompt;
import net.tfminecraft.companionpets.staff.StaffMenuHolder;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.testutil.GoalServerMock;
import net.tfminecraft.companionpets.visual.IdleVisual;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;
import org.mockbukkit.mockbukkit.world.WorldMock;

@ExtendWith(PetsPluginLifecycleTest.FailUnimplemented.class)
class PetsPluginLifecycleTest {
    private LifecycleServer server;
    private WorldMock world;
    private JavaPlugin plugin;
    private final List<LogRecord> messages = new ArrayList<>();
    private Handler logs;

    @BeforeEach void setup() {
        server = MockBukkit.mock(new LifecycleServer());
        world = server.addSimpleWorld("world");
    }

    @AfterEach void cleanup() {
        if (plugin != null && logs != null) plugin.getLogger().removeHandler(logs);
        MockBukkit.unmock();
    }

    private PetsPlugin prepare() { return prepare(PetsPlugin.class); }
    private <T extends PetsPlugin> T prepare(Class<T> type) {
        var description = new PluginDescriptionFile("CompanionPets", "test", type.getName());
        T loaded = type.cast(server.getPluginManager().loadPlugin(type, description, new Object[0]));
        plugin = loaded;
        plugin.saveDefaultConfig();
        logs = new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        plugin.getLogger().addHandler(logs);
        return loaded;
    }
    private void enable() { server.getPluginManager().enablePlugin(plugin); assertTrue(plugin.isEnabled()); }
    private Path file(String name) { return plugin.getDataFolder().toPath().resolve(name); }
    private YamlConfiguration config() { return YamlConfiguration.loadConfiguration(file("config.yml").toFile()); }
    private PetRuntime runtime() throws Exception {
        for (Class<?> type = plugin.getClass(); type != JavaPlugin.class; type = type.getSuperclass()) {
            try {
                var field = type.getDeclaredField("runtime"); field.setAccessible(true);
                return (PetRuntime) field.get(plugin);
            } catch (NoSuchFieldException inherited) { }
        }
        throw new AssertionError("No runtime field");
    }
    private String reload() {
        var console = server.getConsoleSender();
        while (console.nextMessage() != null) { }
        assertTrue(plugin.onCommand(console, null, "companionpets", new String[]{"reload"}));
        return console.nextMessage();
    }
    private Pet pet(UUID owner) { return new Pet(UUID.randomUUID(), owner, "wolf", "Toby", PetSex.MALE); }
    private void saveBeforeEnable(Pet pet) {
        var stored = new PetStore(plugin);
        assertTrue(stored.load()); stored.add(pet); assertTrue(stored.close());
    }
    private WolfMock body(Pet pet, Location at) {
        var wolf = new WolfMock(server, UUID.randomUUID()) {
            @Override public com.destroystokyo.paper.entity.Pathfinder getPathfinder() {
                return (com.destroystokyo.paper.entity.Pathfinder) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{com.destroystokyo.paper.entity.Pathfinder.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "stopPathfinding" -> null;
                        case "hasPath" -> false;
                        case "getCurrentPath" -> null;
                        case "getEntity" -> this;
                        default -> throw new AssertionError(method.getName());
                    });
            }
            @Override public boolean isInWater() { return false; }
            @Override public void setRemoveWhenFarAway(boolean remove) { }
            @Override public float getBodyYaw() { return 0; }
            @Override public void setBodyYaw(float value) { }
        };
        wolf.setLocation(at); server.registerEntity(wolf);
        wolf.getPersistentDataContainer().set(new NamespacedKey(plugin, "pet"), PersistentDataType.STRING, pet.id().toString());
        return wolf;
    }

    @Test void recoveryGuardDisablesStartupAndPreservesFilesWithoutSchedulingActions() throws Exception {
        prepare();
        Files.writeString(file("pets-recovery-required"), "unclean shutdown evidence\n");
        Files.writeString(file("pets.yml"), "pets: {}\n");
        server.getPluginManager().enablePlugin(plugin);
        assertFalse(plugin.isEnabled()); assertNull(runtime());
        assertEquals("unclean shutdown evidence\n", Files.readString(file("pets-recovery-required")));
        assertEquals("pets: {}\n", Files.readString(file("pets.yml")));
        assertTrue(server.tasks.stream().noneMatch(task -> task.getOwner() == plugin));
        assertTrue(messages.stream().anyMatch(record -> record.getMessage().contains("did not commit a clean shutdown")));
    }

    @Test void corruptSavedPetsDisableStartupWithoutOverwritingTheEvidence() throws Exception {
        prepare(); Files.writeString(file("pets.yml"), "pets: [broken\n");
        server.getPluginManager().enablePlugin(plugin);
        assertFalse(plugin.isEnabled()); assertNull(runtime());
        assertEquals("pets: [broken\n", Files.readString(file("pets.yml")));
        assertFalse(Files.exists(file("pets-recovery-required")));
    }

    @Test void invalidInitialSettingsDisableBeforeOpeningTheStore() throws Exception {
        prepare(); var yaml = config(); yaml.set("training.learned-at", 200); yaml.save(file("config.yml").toFile());
        server.getPluginManager().enablePlugin(plugin);
        assertFalse(plugin.isEnabled()); assertNull(runtime());
        assertFalse(Files.exists(file("pets-recovery-required")));
        assertTrue(messages.stream().anyMatch(record -> record.getMessage().contains("Invalid CompanionPets config")));
    }

    @Test void startupAdoptsSavedBodiesAndShutdownCommitsTheirLatestPositionAndReleasesLookGoal() throws Exception {
        prepare(); var owner = server.addPlayer(); var pet = pet(owner.getUniqueId());
        var body = body(pet, new Location(world, 4, 65, 8)); saveBeforeEnable(pet);
        enable();
        var restored = runtime().store().get(pet.id());
        assertEquals(body.getUniqueId(), restored.entityId());
        assertEquals(owner.getUniqueId(), body.getOwnerUniqueId());
        assertEquals("Toby", PlainTextComponentSerializer.plainText().serialize(body.customName()));
        assertTrue(Files.exists(file("pets-recovery-required")));
        assertTrue(server.tasks.stream().filter(task -> task.getOwner() == plugin).count() >= 5);
        var goal = GoalKey.of(Mob.class, new NamespacedKey("companionpets", "look"));
        PetFx.look(body, owner);
        assertTrue(server.getMobGoals().hasGoal(body, goal));
        body.setLocation(new Location(world, 15, 66, -9, 45, 0));
        server.getPluginManager().disablePlugin(plugin);
        assertNull(server.getMobGoals().getGoal(body, goal), "A disabled plugin leaves no goals on loaded bodies");
        assertFalse(Files.exists(file("pets-recovery-required")));
        assertTrue(server.tasks.stream().noneMatch(task -> task.getOwner() == plugin && !task.isCancelled()));
        var reopened = new PetStore(plugin); assertTrue(reopened.load());
        var saved = reopened.get(pet.id());
        assertEquals(15, saved.x()); assertEquals(66, saved.y()); assertEquals(-9, saved.z()); assertEquals(45, saved.yaw());
        assertEquals(body.getUniqueId(), saved.entityId());
        assertTrue(reopened.close());
    }

    @Test void failedShutdownSaveRetainsTheRecoveryGuardAndPreviousSnapshot() throws Exception {
        prepare(); enable();
        var pet = pet(UUID.randomUUID()); pet.stored(true); runtime().store().add(pet);
        assertTrue(runtime().store().save());
        String saved = Files.readString(file("pets.yml"));
        Files.createDirectory(file("pets.yml.tmp")); Files.writeString(file("pets.yml.tmp/blocker"), "filesystem conflict");
        pet.name("Unsaved rename");
        server.getPluginManager().disablePlugin(plugin);
        assertTrue(Files.exists(file("pets-recovery-required")));
        assertEquals(saved, Files.readString(file("pets.yml")));
        assertFalse(new PetStore(plugin).load(), "Uncommitted shutdown requires reconciliation before reopening");
    }

    @Test void emptyTypeReloadKeepsTheActiveConfigurationAndPrompts() throws Exception {
        prepare(); enable(); var active = runtime().config();
        var player = server.addPlayer(); var prompt = new RenamePrompt(UUID.randomUUID(), Long.MAX_VALUE);
        runtime().sessions().rename(player.getUniqueId(), prompt);
        var yaml = config(); yaml.set("pets", java.util.Map.of()); yaml.save(file("config.yml").toFile());
        assertTrue(reload().contains("No valid pet types"));
        assertSame(active, runtime().config()); assertSame(prompt, runtime().sessions().rename(player.getUniqueId()));
    }

    @Test void reloadCannotRemoveATypeStillUsedByASavedPet() throws Exception {
        prepare(); enable(); var pet = pet(UUID.randomUUID()); pet.stored(true); runtime().store().add(pet);
        var active = runtime().config(); var yaml = config(); yaml.set("pets.wolf", null); yaml.save(file("config.yml").toFile());
        assertTrue(reload().contains("still used by saved pets"));
        assertSame(active, runtime().config()); assertSame(pet, runtime().store().get(pet.id()));
    }

    @Test void missingConfigurationFileKeepsActiveSettings() throws Exception {
        prepare(); enable(); var active = runtime().config(); Files.delete(file("config.yml"));
        assertTrue(reload().contains("could not be loaded")); assertSame(active, runtime().config());
    }

    @Test void resourceFailureDuringActivationKeepsRuntimeAndInteractionState() throws Exception {
        var failing = prepare(ResourceFailurePlugin.class); enable();
        var active = runtime().config(); var owner = server.addPlayer();
        var prompt = new RenamePrompt(UUID.randomUUID(), Long.MAX_VALUE); runtime().sessions().rename(owner.getUniqueId(), prompt);
        var yaml = config(); yaml.set("pets.friend.entity", "CAT"); yaml.set("pets.friend.egg", "FROG_SPAWN_EGG");
        yaml.save(file("config.yml").toFile()); failing.failResource = true;
        assertTrue(reload().contains("could not be activated"));
        assertSame(active, runtime().config()); assertSame(prompt, runtime().sessions().rename(owner.getUniqueId()));
        failing.failResource = false;
    }

    @Test void acceptedReloadClosesPetMenusClearsPromptsAndReconfiguresExistingBodies() throws Exception {
        prepare(); var owner = server.addPlayer(); var pet = pet(owner.getUniqueId());
        var body = body(pet, new Location(world, 2, 65, 2)); saveBeforeEnable(pet); enable();
        var care = new MenuHolder(MenuHolder.Kind.CARE, pet.id(), null);
        var careInventory = server.createInventory(care, 9); care.inventory(careInventory); owner.openInventory(careInventory);
        var staff = server.addPlayer(); var holder = new StaffMenuHolder(staff.getUniqueId(), StaffMenuHolder.Kind.LIST, owner.getUniqueId(), 0);
        var staffInventory = server.createInventory(holder, 9); holder.inventory(staffInventory); staff.openInventory(staffInventory);
        var other = server.addPlayer(); var unrelated = server.createInventory(null, 9); other.openInventory(unrelated);
        runtime().sessions().rename(owner.getUniqueId(), new RenamePrompt(pet.id(), Long.MAX_VALUE));
        runtime().sessions().rest(pet.id(), System.currentTimeMillis() + 60_000);
        var yaml = config(); yaml.set("pets.friend.entity", "CAT"); yaml.set("pets.friend.egg", "FROG_SPAWN_EGG");
        yaml.set("pets.wolf.sounds", false); yaml.save(file("config.yml").toFile());
        assertTrue(reload().contains("settings reloaded"));
        assertNotNull(runtime().config().type("friend"));
        assertNotSame(careInventory, owner.getOpenInventory().getTopInventory());
        assertNotSame(staffInventory, staff.getOpenInventory().getTopInventory());
        assertSame(unrelated, other.getOpenInventory().getTopInventory());
        assertNull(runtime().sessions().rename(owner.getUniqueId()));
        assertTrue(runtime().sessions().resting(pet.id(), System.currentTimeMillis()));
        assertSame(body, runtime().entity(runtime().store().get(pet.id())));
        assertFalse(runtime().config().type("wolf").sounds().nativeSounds());
        assertTrue(body.isSilent(), "Reload applies the disabled native voice to the existing body");
        server.getScheduler().performOneTick();
    }

    @Test void missingModelProviderUsesVanillaBodiesAndDelayedValidationReportsTheModel() throws Exception {
        prepare(); var yaml = config(); yaml.set("pets.wolf.model", "missing_wolf"); yaml.save(file("config.yml").toFile());
        enable(); assertInstanceOf(IdleVisual.class, runtime().visual());
        assertTrue(messages.stream().anyMatch(record -> record.getMessage().contains("ModelEngine is not enabled")));
        server.getScheduler().performTicks(200);
        assertTrue(messages.stream().anyMatch(record -> record.getMessage().contains("Configuration validation:")
                && record.getMessage().contains("missing_wolf")));
    }

    @Test void enabledModelProviderWithUnavailableApiFallsBackWithoutDisablingPets() throws Exception {
        MockBukkit.createMockPlugin("ModelEngine"); prepare(); enable();
        assertInstanceOf(IdleVisual.class, runtime().visual());
        assertTrue(messages.stream().anyMatch(record -> record.getMessage().contains("ModelEngine 4 integration unavailable")));
        assertTrue(Files.exists(file("pets-recovery-required")));
    }

    @org.junit.jupiter.api.io.TempDir static Path modelClasses;
    private static final String MODEL_API = "com.ticxo.modelengine.api.";

    @BeforeAll static void compileModelProvider() throws Exception {
        // The provider API is isolated from other tests and exposes observable renderer ownership.
        var sources = new java.util.LinkedHashMap<String, String>();
        sources.put("ModelEngineAPI", """
            import java.util.*;
            import org.bukkit.entity.Entity;
            import com.ticxo.modelengine.api.model.*;
            import com.ticxo.modelengine.api.generator.blueprint.ModelBlueprint;
            public class ModelEngineAPI {
                public static final Map<UUID, ModeledEntity> owners = new HashMap<>();
                public static final List<ActiveModel> created = new ArrayList<>();
                public static ModelBlueprint getBlueprint(String id) { return id.equals("fixture_wolf") ? new ModelBlueprint() : null; }
                public static ModeledEntity getModeledEntity(UUID id) { return owners.get(id); }
                public static ModeledEntity createModeledEntity(Entity entity) {
                    var owner = new ModeledEntity(entity.getUniqueId()); owners.put(entity.getUniqueId(), owner); return owner;
                }
                public static ActiveModel createActiveModel(String id) { var model = new ActiveModel(id); created.add(model); return model; }
                public static ModelEngineAPI getAPI() { return new ModelEngineAPI(); }
                public ModelUpdaters getModelUpdaters() { return new ModelUpdaters(); }
            }
            """);
        sources.put("model.ModeledEntity", """
            import java.util.*;
            import com.ticxo.modelengine.api.entity.BaseEntity;
            public class ModeledEntity {
                public final BaseEntity base;
                public final Map<String, ActiveModel> models = new HashMap<>();
                public boolean visible = true, saved, removed;
                public ModeledEntity(UUID id) { base = new BaseEntity(id); }
                public BaseEntity getBase() { return base; }
                public ActiveModel getModel(String id) { return models.get(id); }
                public ActiveModel addModel(ActiveModel model, boolean hitbox) { return models.put(model.id, model); }
                public ActiveModel removeModel(String id) { return models.remove(id); }
                public Map<String, ActiveModel> getModels() { return models; }
                public void setSaved(boolean value) { saved = value; }
                public void setBaseEntityVisible(boolean value) { visible = value; }
            }
            """);
        sources.put("model.ActiveModel", """
            import com.ticxo.modelengine.api.animation.handler.AnimationHandler;
            public class ActiveModel {
                public final String id;
                public boolean destroyed, hitbox;
                public Boolean locked;
                public final AnimationHandler handler = new AnimationHandler();
                public ActiveModel(String id) { this.id = id; }
                public boolean isDestroyed() { return destroyed; }
                public boolean isModelRotationLocked() { return Boolean.TRUE.equals(locked); }
                public void setModelRotationLocked(Boolean value) { locked = value; }
                public void setLockedYBodyRot(float value) { }
                public void setLockedYHeadRot(float value) { }
                public void setLockedXHeadRot(float value) { }
                public void setScale(double value) { }
                public void setHitboxScale(double value) { }
                public void setHitboxVisible(boolean value) { hitbox = value; }
                public AnimationHandler getAnimationHandler() { return handler; }
                public void destroy() { destroyed = true; }
            }
            """);
        sources.put("model.ModelUpdaters", """
            import com.ticxo.modelengine.api.ModelEngineAPI;
            public class ModelUpdaters {
                public void forceRemoveModeledEntity(ModeledEntity owner) {
                    owner.removed = true; owner.models.values().forEach(ActiveModel::destroy);
                    owner.models.clear(); ModelEngineAPI.owners.remove(owner.base.getUUID());
                }
            }
            """);
        sources.put("entity.BaseEntity", """
            import java.util.UUID;
            import com.ticxo.modelengine.api.entity.data.IEntityData;
            public class BaseEntity {
                private final UUID id;
                private final IEntityData data = new IEntityData();
                public BaseEntity(UUID id) { this.id = id; }
                public UUID getUUID() { return id; }
                public IEntityData getData() { return data; }
            }
            """);
        sources.put("entity.data.IEntityData", "public class IEntityData { public void setBackCull(Boolean value) { } }");
        sources.put("animation.ModelState", "public enum ModelState { IDLE, WALK }");
        sources.put("animation.BlueprintAnimation", """
            public class BlueprintAnimation {
                public enum LoopMode { ONCE, HOLD, LOOP }
                public enum OverrideMode { OVERRIDE }
                public double getLength() { return 1; }
            }
            """);
        sources.put("animation.property.IAnimationProperty", """
            import com.ticxo.modelengine.api.animation.BlueprintAnimation.*;
            public class IAnimationProperty {
                public void setForceLoopMode(LoopMode value) { }
                public void setForceOverride(OverrideMode value) { }
            }
            """);
        sources.put("animation.handler.AnimationHandler", """
            import com.ticxo.modelengine.api.animation.ModelState;
            import com.ticxo.modelengine.api.animation.property.IAnimationProperty;
            public class AnimationHandler {
                public IAnimationProperty playAnimation(String name, double in, double out, double speed, boolean force) { return new IAnimationProperty(); }
                public void stopAnimation(String name) { }
                public boolean isPlayingAnimation(String name) { return false; }
                public void forceStopAllAnimations() { }
                public void setDefaultProperty(DefaultProperty property) { }
                public static class DefaultProperty {
                    public DefaultProperty(ModelState state, String name, double in, double out, double speed) { }
                }
            }
            """);
        sources.put("generator.blueprint.ModelBlueprint", """
            import java.util.*;
            import com.ticxo.modelengine.api.animation.BlueprintAnimation;
            public class ModelBlueprint {
                public Map<String, BlueprintAnimation> getAnimations() {
                    return Map.of("idle", new BlueprintAnimation(), "walk", new BlueprintAnimation());
                }
                public Map<String, Object> getFlatMap() { return Map.of(); }
            }
            """);
        sources.put("events.BaseEntityInteractEvent", """
            import org.bukkit.event.*;
            import org.bukkit.entity.Player;
            import org.bukkit.inventory.EquipmentSlot;
            import com.ticxo.modelengine.api.entity.BaseEntity;
            public class BaseEntityInteractEvent extends Event {
                private static final HandlerList HANDLERS = new HandlerList();
                private final Player player;
                private final BaseEntity base;
                public enum Action { INTERACT }
                public BaseEntityInteractEvent(Player player, BaseEntity base) { this.player = player; this.base = base; }
                public Player getPlayer() { return player; }
                public BaseEntity getBaseEntity() { return base; }
                public EquipmentSlot getSlot() { return EquipmentSlot.HAND; }
                public Action getAction() { return Action.INTERACT; }
                public HandlerList getHandlers() { return HANDLERS; }
                public static HandlerList getHandlerList() { return HANDLERS; }
            }
            """);
        var args = new ArrayList<>(List.of("-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", modelClasses.toString()));
        for (var entry : sources.entrySet()) {
            String name = MODEL_API + entry.getKey();
            Path source = modelClasses.resolve(name.replace('.', '/') + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package " + name.substring(0, name.lastIndexOf('.')) + ";\n" + entry.getValue());
            args.add(source.toString());
        }
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
    }

    @Test void modelStartupRoutesInteractionAndReloadAndShutdownReleaseOwnedRenderers() throws Exception {
        MockBukkit.createMockPlugin("ModelEngine");
        var description = new PluginDescriptionFile("CompanionPets", "test", PetsPlugin.class.getName());
        var data = Files.createTempDirectory(modelClasses, "pets-data-");
        var loader = new ModelPluginLoader(server, description, data);
        plugin = (JavaPlugin) loader.loadClass(PetsPlugin.class.getName()).getConstructor().newInstance();
        server.getPluginManager().registerLoadedPlugin(plugin);
        plugin.saveDefaultConfig();
        var yaml = config(); yaml.set("pets.wolf.model", "fixture_wolf"); yaml.save(file("config.yml").toFile());
        var owner = server.addPlayer(); owner.teleport(new Location(world, 0, 65, 0));
        var pet = pet(owner.getUniqueId()); var body = body(pet, new Location(world, 2, 65, 2));
        saveBeforeEnable(pet); enable();
        assertEquals("net.tfminecraft.companionpets.integration.ModelHook", runtime().visual().getClass().getName());
        assertTrue(runtime().visual().attached(body));
        Class<?> api = loader.loadClass(MODEL_API + "ModelEngineAPI");
        Object renderer = api.getMethod("getModeledEntity", UUID.class).invoke(null, body.getUniqueId());
        assertFalse(renderer.getClass().getField("visible").getBoolean(renderer));
        Class<?> base = loader.loadClass(MODEL_API + "entity.BaseEntity");
        Class<?> event = loader.loadClass(MODEL_API + "events.BaseEntityInteractEvent");
        owner.setSneaking(true);
        server.getPluginManager().callEvent((org.bukkit.event.Event) event.getConstructor(Player.class, base)
                .newInstance(owner, base.getConstructor(UUID.class).newInstance(body.getUniqueId())));
        var menu = assertInstanceOf(MenuHolder.class, owner.getOpenInventory().getTopInventory().getHolder());
        assertEquals(MenuHolder.Kind.CARE, menu.kind()); assertEquals(pet.id(), menu.petId());
        assertTrue(reload().contains("settings reloaded"));
        assertTrue(renderer.getClass().getField("removed").getBoolean(renderer));
        assertTrue(renderer.getClass().getField("visible").getBoolean(renderer), "Reload releases the old hidden vanilla body");
        Object replacement = api.getMethod("getModeledEntity", UUID.class).invoke(null, body.getUniqueId());
        assertNotSame(renderer, replacement); assertNotNull(replacement);
        assertTrue(runtime().visual().attached(body));
        server.getPluginManager().disablePlugin(plugin);
        assertTrue(replacement.getClass().getField("removed").getBoolean(replacement));
        assertNull(api.getMethod("getModeledEntity", UUID.class).invoke(null, body.getUniqueId()));
        assertTrue(body.isValid(), "Stopping the plugin leaves the persisted pet's vanilla body intact");
        assertFalse(Files.exists(file("pets-recovery-required")));
    }

    private static final class ModelPluginLoader extends org.mockbukkit.mockbukkit.plugin.MockBukkitConfiguredPluginClassLoader {
        ModelPluginLoader(GoalServerMock server, PluginDescriptionFile description, Path folder) {
            super(server, description, folder.toFile(), folder.resolve("plugin.jar").toFile());
        }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            boolean provider = name.startsWith(MODEL_API);
            boolean isolated = name.equals(PetsPlugin.class.getName())
                || name.startsWith("net.tfminecraft.companionpets.integration.ModelHook")
                || name.startsWith("net.tfminecraft.companionpets.integration.ModelEngineBridge");
            if (!provider && !isolated) return super.loadClass(name, resolve);
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try {
                    byte[] bytes;
                    if (provider) bytes = Files.readAllBytes(modelClasses.resolve(name.replace('.', '/') + ".class"));
                    else try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        bytes = input.readAllBytes();
                    }
                    loaded = defineClass(name, bytes, 0, bytes.length, PetsPlugin.class.getProtectionDomain());
                } catch (java.io.IOException exception) { throw new ClassNotFoundException(name, exception); }
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }

    private static class LifecycleServer extends GoalServerMock {
        final List<org.bukkit.scheduler.BukkitTask> tasks = new ArrayList<>();
        private final org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock scheduler =
                new org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock() {
            @Override public synchronized org.bukkit.scheduler.BukkitTask runTaskTimer(org.bukkit.plugin.Plugin owner,
                    Runnable task, long delay, long period) {
                var scheduled = super.runTaskTimer(owner, task, delay, period); tasks.add(scheduled); return scheduled;
            }
            @Override public synchronized org.bukkit.scheduler.BukkitTask runTaskLater(org.bukkit.plugin.Plugin owner,
                    Runnable task, long delay) {
                var scheduled = super.runTaskLater(owner, task, delay); tasks.add(scheduled); return scheduled;
            }
        };
        @Override public org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock getScheduler() {
            return scheduler == null ? super.getScheduler() : scheduler;
        }
    }

    public static class ResourceFailurePlugin extends PetsPlugin {
        boolean failResource;
        @Override public InputStream getResource(String name) {
            if (failResource && name.equals("config.yml")) throw new IllegalStateException("Bundled config stream is unavailable");
            return super.getResource(name);
        }
    }

    public static class FailUnimplemented implements TestExecutionExceptionHandler, LifecycleMethodExecutionExceptionHandler {
        @Override public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable { fail(throwable); }
        @Override public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable { fail(throwable); }
        private void fail(Throwable throwable) throws Throwable {
            if (throwable instanceof UnimplementedOperationException) throw new AssertionError("Implement the required Bukkit fixture boundary", throwable);
            throw throwable;
        }
    }
}
