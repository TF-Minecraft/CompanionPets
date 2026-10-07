package net.tfminecraft.companionpets.listen;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import javax.tools.ToolProvider;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.gui.MenuHolder;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.visual.IdleVisual;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginManagerMock;

class FurniturePetHousesProviderTest {
    @TempDir static Path classes;
    private static final String API = "dev.lone.itemsadder.api.";
    private static final String PLACE = "FurniturePlaceSuccessEvent";
    private static final String CLICK = "FurnitureInteractEvent";
    private static final String BREAK = "FurnitureBreakEvent";
    private ProviderServer server;
    private PetRuntime runtime;
    private FurniturePetHouses houses;
    private PlayerMock owner;
    private PlayerMock stranger;
    private Entity furniture;
    private String key;
    private YamlConfiguration settings;
    private Provider provider;
    private final List<String> logs = new ArrayList<>();

    @BeforeAll static void compileProvider() throws Exception {
        // Consumed API signatures checked against ItemsAdder 4.0.18. Only provider behavior is doubled;
        // classloading, Bukkit event routing, the real PetStore and menus remain production paths.
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fixture.Provider", """
                import java.io.File;
                import org.bukkit.Server;
                import org.bukkit.plugin.PluginDescriptionFile;
                import org.bukkit.plugin.java.JavaPlugin;
                import org.bukkit.plugin.java.JavaPluginLoader;
                public class Provider extends JavaPlugin {
                    public Provider(Server server, File folder) {
                        super(new JavaPluginLoader(server), new PluginDescriptionFile("ItemsAdder", "1", "fixture.Provider"), folder, folder);
                    }
                    public void active(boolean value) { setEnabled(value); }
                }
                """);
        sources.put(API + "CustomStack", """
                import org.bukkit.inventory.ItemStack;
                public class CustomStack {
                    public static ItemStack item;
                    public static boolean complex, missing, fail;
                    public static int queries;
                    public static String requested;
                    public static CustomStack getInstance(String name) {
                        queries++; requested = name;
                        if (fail) throw new LinkageError("provider initialization failed");
                        return missing ? null : new CustomStack();
                    }
                    public ItemStack getItemStack() { return item; }
                    public static boolean isComplexFurniture(ItemStack item) { return complex; }
                }
                """);
        for (String name : List.of(PLACE, CLICK, BREAK)) {
            boolean cancellable = !name.equals(PLACE);
            sources.put(API + "Events." + name, """
                    import org.bukkit.entity.Entity;
                    import org.bukkit.entity.Player;
                    import org.bukkit.event.*;
                    import org.bukkit.event.block.Action;
                    import org.bukkit.event.player.PlayerEvent;
                    public class %s extends PlayerEvent %s {
                        private static final HandlerList HANDLERS = new HandlerList();
                        private final Entity entity;
                        private final String id;
                        public boolean failNamespaced, failEntity, failAction;
                        public Action action = Action.RIGHT_CLICK_BLOCK;
                        private boolean cancelled;
                        public %s(Player player, Entity entity, String id) { super(player); this.entity = entity; this.id = id; }
                        public String getNamespacedID() {
                            if (failNamespaced) throw new IllegalStateException("identifier failed");
                            return id;
                        }
                        public Entity getBukkitEntity() {
                            if (failEntity) throw new IllegalStateException("entity lookup failed");
                            return entity;
                        }
                        public Action getAction() {
                            if (failAction) throw new IllegalStateException("action lookup failed");
                            return action;
                        }
                        public boolean isCancelled() { return cancelled; }
                        public void setCancelled(boolean value) { cancelled = value; }
                        public HandlerList getHandlers() { return HANDLERS; }
                        public static HandlerList getHandlerList() { return HANDLERS; }
                    }
                    """.formatted(name, cancellable ? "implements Cancellable" : "", name));
        }
        List<String> arguments = new ArrayList<>(List.of("-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()));
        for (var source : sources.entrySet()) {
            int dot = source.getKey().lastIndexOf('.');
            Path file = classes.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "package " + source.getKey().substring(0, dot) + ";\n" + source.getValue());
            arguments.add(file.toString());
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Optional provider fixtures require a JDK");
        assertEquals(0, compiler.run(null, null, null, arguments.toArray(String[]::new)));
    }

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(new ProviderServer());
        var world = server.addSimpleWorld("houses");
        var plugin = MockBukkit.createMockPlugin("HouseCoverage");
        plugin.getLogger().addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logs.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        owner = server.addPlayer("Owner");
        stranger = server.addPlayer("Visitor");
        furniture = server.addPlayer("FurnitureEntity");
        furniture.teleport(new Location(world, -2.5, 65, 3.5));
        key = PetStore.kennelKey("houses", -3, 65, 3);
        settings = new YamlConfiguration();
        settings.loadFromString("""
                items: {kennel: 'itemsadder:tfmc:pet_house', kennel-furniture: 'tfmc:pet_house', kennel-block: BARREL}
                pets: {wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG}}
                """);
        var visual = new IdleVisual();
        var petKey = new NamespacedKey(plugin, "pet");
        var store = new PetStore(plugin);
        assertTrue(store.load());
        runtime = new PetRuntime(plugin, CompanionConfig.load(plugin, settings), store, new Sessions(),
                new Bodies(plugin, petKey, visual), visual, petKey, new NamespacedKey(plugin, "toy"));
        houses = new FurniturePetHouses(runtime);
    }

    @AfterEach void cleanup() throws Exception {
        if (runtime != null) runtime.store().close();
        if (owner != null) PetFx.clearPlayer(owner.getUniqueId());
        if (stranger != null) PetFx.clearPlayer(stranger.getUniqueId());
        MockBukkit.unmock();
        if (provider != null) provider.close();
    }

    private Provider provider(String missingClass) throws Exception {
        provider = new Provider(server, missingClass);
        server.provider = provider.plugin;
        provider.active(true);
        return provider;
    }
    private Event event(String name, Player player) throws Exception {
        return (Event) provider.loadClass(API + "Events." + name)
                .getConstructor(Player.class, Entity.class, String.class).newInstance(player, furniture, "TFMC:PET_HOUSE");
    }
    private <T extends Event> T fire(T event) { server.getPluginManager().callEvent(event); return event; }
    private List<RegisteredListener> registrations() {
        return HandlerList.getRegisteredListeners(runtime.plugin()).stream().filter(listener -> listener.getListener() == houses).toList();
    }
    private String bar(PlayerMock player) {
        var message = player.nextActionBar();
        return message == null ? null : PlainTextComponentSerializer.plainText().serialize(message);
    }

    @Test void configurationValidationRespectsAbsentDisabledSimpleMissingAndComplexItems() throws Exception {
        assertNull(FurniturePetHouses.configurationIssue(runtime));
        var ia = provider(null);
        ia.set("item", new ItemStack(Material.PAPER));
        ia.set("complex", true);
        ia.active(false);
        assertNull(FurniturePetHouses.configurationIssue(runtime));
        assertEquals(0, ia.get("queries"));
        ia.active(true);
        assertTrue(FurniturePetHouses.configurationIssue(runtime).contains("complex furniture is unsupported"));
        assertEquals("tfmc:pet_house", ia.get("requested"));
        ia.set("complex", false);
        assertNull(FurniturePetHouses.configurationIssue(runtime));
        ia.set("missing", true);
        assertNull(FurniturePetHouses.configurationIssue(runtime));
        ia.set("missing", false);
        ia.set("item", null);
        assertNull(FurniturePetHouses.configurationIssue(runtime));
        ia.set("fail", true);
        assertTrue(FurniturePetHouses.configurationIssue(runtime).startsWith("Could not validate"));
        int queries = (int) ia.get("queries");
        settings.set("items.kennel-furniture", null);
        runtime.config(CompanionConfig.load(runtime.plugin(), settings));
        assertNull(FurniturePetHouses.configurationIssue(runtime));
        assertEquals(queries, ia.get("queries"), "unconfigured furniture must not query the provider");
    }

    @Test void missingValidationApiReportsTheConfigurationProblem() throws Exception {
        provider(API + "CustomStack");
        assertTrue(FurniturePetHouses.configurationIssue(runtime).startsWith("Could not validate"));
    }

    @Test void absentOrDisabledProviderDoesNotRegisterHooks() throws Exception {
        houses.register();
        assertTrue(registrations().isEmpty());
        var ia = provider(null);
        ia.active(false);
        houses.register();
        assertTrue(registrations().isEmpty());
        assertTrue(logs.isEmpty());
    }

    @Test void providerEventsPersistPlacementProtectTheMenuAndRemoveBrokenHouses() throws Exception {
        provider(null);
        houses.register();
        assertEquals(List.of(EventPriority.MONITOR, EventPriority.HIGHEST, EventPriority.MONITOR),
                registrations().stream().map(RegisteredListener::getPriority).toList());
        assertTrue(registrations().stream().allMatch(RegisteredListener::isIgnoringCancelled));
        assertTrue(logs.contains("ItemsAdder Pet House furniture hooks registered"));
        fire(event(PLACE, owner));
        assertEquals(owner.getUniqueId(), runtime.store().kennelOwner(key));
        assertTrue(runtime.store().pending());
        assertTrue(bar(owner).startsWith("Pet House placed"));
        assertTrue(runtime.store().save());
        var restored = new PetStore(runtime.plugin());
        assertTrue(restored.load());
        assertEquals(owner.getUniqueId(), restored.kennelOwner(key));
        restored.close();
        var denied = fire(event(CLICK, stranger));
        assertTrue(((Cancellable) denied).isCancelled());
        assertEquals("This Pet House belongs to someone else", bar(stranger));
        assertNull(stranger.getOpenInventory().getTopInventory());
        var opened = fire(event(CLICK, owner));
        assertTrue(((Cancellable) opened).isCancelled());
        assertEquals(MenuHolder.Kind.KENNEL, ((MenuHolder) owner.getOpenInventory().getTopInventory().getHolder()).kind());
        Event cancelledBreak = event(BREAK, owner);
        ((Cancellable) cancelledBreak).setCancelled(true);
        fire(cancelledBreak);
        assertEquals(owner.getUniqueId(), runtime.store().kennelOwner(key));
        assertFalse(runtime.store().pending());
        fire(event(BREAK, owner));
        assertNull(runtime.store().kennelOwner(key));
        assertTrue(runtime.store().pending());
        assertTrue(runtime.store().save());
        var afterBreak = new PetStore(runtime.plugin());
        assertTrue(afterBreak.load());
        assertNull(afterBreak.kennelOwner(key));
        afterBreak.close();
    }

    @Test void protectionCancellationBeforeOurPriorityPreventsOpening() throws Exception {
        provider(null);
        houses.register();
        fire(event(PLACE, owner));
        @SuppressWarnings("unchecked") Class<? extends Event> click = (Class<? extends Event>) provider.loadClass(API + "Events." + CLICK);
        server.getPluginManager().registerEvent(click, new Listener() { }, EventPriority.NORMAL,
                (listener, event) -> ((Cancellable) event).setCancelled(true), runtime.plugin());
        assertTrue(((Cancellable) fire(event(CLICK, owner))).isCancelled());
        assertNull(owner.getOpenInventory().getTopInventory());
        assertEquals(owner.getUniqueId(), runtime.store().kennelOwner(key));
    }

    @Test void reflectionFailuresLeaveOwnershipAndMenusUnchangedAndLogTheBoundary() throws Exception {
        provider(null);
        houses.register();
        Event unreadable = event(PLACE, owner);
        unreadable.getClass().getField("failNamespaced").setBoolean(unreadable, true);
        fire(unreadable);
        assertNull(runtime.store().kennelOwner(key));
        Event noEntity = event(PLACE, owner);
        noEntity.getClass().getField("failEntity").setBoolean(noEntity, true);
        fire(noEntity);
        assertNull(runtime.store().kennelOwner(key));
        assertFalse(runtime.store().pending());
        fire(event(PLACE, owner));
        Event noAction = event(CLICK, owner);
        noAction.getClass().getField("failAction").setBoolean(noAction, true);
        fire(noAction);
        assertFalse(((Cancellable) noAction).isCancelled());
        assertNull(owner.getOpenInventory().getTopInventory());
        assertEquals(owner.getUniqueId(), runtime.store().kennelOwner(key));
        assertEquals(2, logs.stream().filter(line -> line.equals("Could not read Pet House furniture event")).count());
        assertEquals(1, logs.stream().filter(line -> line.equals("Could not read Pet House furniture click")).count());
    }

    @Test void failedRegistrationRollsBackEarlierHooksButPreservesOtherListeners() throws Exception {
        provider(API + "Events." + BREAK);
        AtomicInteger observed = new AtomicInteger();
        @SuppressWarnings("unchecked") Class<? extends Event> place = (Class<? extends Event>) provider.loadClass(API + "Events." + PLACE);
        server.getPluginManager().registerEvent(place, new Listener() { }, EventPriority.NORMAL,
                (listener, event) -> observed.incrementAndGet(), runtime.plugin());
        houses.register();
        fire(event(PLACE, owner));
        assertAll(
                () -> assertTrue(registrations().isEmpty(), "incompatible provider must leave no partial house hooks"),
                () -> assertNull(runtime.store().kennelOwner(key), "failed registration must not persist ownership without a break handler"),
                () -> assertFalse(runtime.store().pending()),
                () -> assertEquals(1, observed.get(), "rollback must preserve unrelated listeners"),
                () -> assertTrue(logs.contains("Could not register ItemsAdder Pet House furniture hooks")));
        provider.close();
        provider(null);
        houses.register();
        assertEquals(3, registrations().size(), "retry with a compatible provider installs one complete hook set");
        fire(event(PLACE, owner));
        assertEquals(owner.getUniqueId(), runtime.store().kennelOwner(key));
        fire(event(BREAK, owner));
        assertNull(runtime.store().kennelOwner(key));
    }

    private static final class ProviderServer extends ServerMock {
        private Plugin provider;
        private final PluginManagerMock plugins = new PluginManagerMock(this) {
            @Override public Plugin getPlugin(String name) {
                return name.equals("ItemsAdder") && provider != null ? provider : super.getPlugin(name);
            }
        };
        @Override public PluginManagerMock getPluginManager() { return plugins == null ? super.getPluginManager() : plugins; }
    }

    private static final class Provider extends URLClassLoader {
        private final String missingClass;
        private final Plugin plugin;
        Provider(Server server, String missingClass) throws Exception {
            super(new URL[]{classes.toUri().toURL()}, FurniturePetHousesProviderTest.class.getClassLoader());
            this.missingClass = missingClass;
            plugin = (Plugin) loadClass("fixture.Provider").getConstructor(Server.class, File.class).newInstance(server, classes.toFile());
        }
        void active(boolean active) throws Exception { plugin.getClass().getMethod("active", boolean.class).invoke(plugin, active); }
        void set(String name, Object value) throws Exception { loadClass(API + "CustomStack").getField(name).set(null, value); }
        Object get(String name) throws Exception { return loadClass(API + "CustomStack").getField(name).get(null); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(missingClass)) throw new ClassNotFoundException(name + " unavailable in this provider version");
            return super.loadClass(name, resolve);
        }
    }
}
