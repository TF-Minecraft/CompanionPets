package net.tfminecraft.companionpets.item;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import javax.tools.ToolProvider;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginManagerMock;

class ItemBridgeTest {
    @TempDir static Path classes;
    private ProviderServer server;
    private Bridge bridge;
    private final List<Provider> providers = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private Handler logs;

    @BeforeAll static void compileProviders() throws IOException {
        // Separate provider loaders model the API ownership used by actual plugin reloads.
        // None of these optional API classes enter the suite's application classpath.
        Path type = classes.resolve("Type.java");
        Files.writeString(type, """
                package net.Indyuce.mmoitems.api;
                public record Type(String id) {
                    public static boolean unavailable;
                    public static Type get(String name) { return unavailable ? null : new Type(name); }
                }
                """);
        Path provider = classes.resolve("Provider.java");
        Files.writeString(provider, """
                package fixture;
                import java.io.File;
                import org.bukkit.Server;
                import org.bukkit.inventory.ItemStack;
                import org.bukkit.NamespacedKey;
                import org.bukkit.persistence.PersistentDataType;
                import org.bukkit.plugin.PluginDescriptionFile;
                import org.bukkit.plugin.java.JavaPluginLoader;
                import org.mockbukkit.mockbukkit.plugin.PluginMock;
                import net.Indyuce.mmoitems.api.Type;
                public class Provider extends PluginMock {
                    public static ItemStack item;
                    public static int identifies, creates; public static boolean identifyFails, createFails;
                    public static String requestedType, requestedId;
                    public Provider(String name, Server server, File folder) {
                        super(new JavaPluginLoader(server),
                            new PluginDescriptionFile(name, "1", "fixture.Provider"), folder, folder);
                    }
                    public void active(boolean active) { setEnabled(active); }
                    public static String getTypeName(ItemStack item) {
                        identifies++; if (identifyFails) throw new IllegalStateException("MMOItems identification failed");
                        return item.getItemMeta().getPersistentDataContainer().get(
                            new NamespacedKey("fixture", "mmo_type"), PersistentDataType.STRING);
                    }
                    public static String getID(ItemStack item) {
                        return item.getItemMeta().getPersistentDataContainer().get(
                            new NamespacedKey("fixture", "mmo_id"), PersistentDataType.STRING);
                    }
                    public ItemStack getItem(Type type, String id) {
                        requestedType = type.id(); requestedId = id;
                        creates++; if (createFails) throw new IllegalStateException("MMOItems creation failed");
                        return item;
                    }
                }
                """);
        Path ia = classes.resolve("CustomStack.java");
        Files.writeString(ia, """
                package dev.lone.itemsadder.api;
                import org.bukkit.inventory.ItemStack;
                import org.bukkit.NamespacedKey;
                import org.bukkit.persistence.PersistentDataType;
                public class CustomStack {
                    public static ItemStack item;
                    public static int identifies, creates; public static boolean identifyFails, createFails, missing;
                    public static String requestedId;
                    private final ItemStack stack;
                    public CustomStack(ItemStack stack) { this.stack = stack; }
                    public static CustomStack byItemStack(ItemStack stack) {
                        identifies++; if (identifyFails) throw new IllegalStateException("ItemsAdder identification failed");
                        var custom = new CustomStack(stack);
                        return custom.getNamespacedID() == null ? null : custom;
                    }
                    public String getNamespacedID() {
                        return stack.getItemMeta().getPersistentDataContainer().get(
                            new NamespacedKey("fixture", "ia_id"), PersistentDataType.STRING);
                    }
                    public static CustomStack getInstance(String id) {
                        requestedId = id;
                        creates++; if (createFails) throw new IllegalStateException("ItemsAdder creation failed");
                        return missing ? null : new CustomStack(item);
                    }
                    public ItemStack getItemStack() { return stack; }
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Provider boundary fixtures require JDK 21");
        assertEquals(0, compiler.run(null, null, null, "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", classes.toString(),
                type.toString(), provider.toString(), ia.toString()));
    }

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(new ProviderServer());
        ItemBridge.clearCache();
        bridge = new Bridge();
        logs = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getMessage().startsWith("[CompanionPets]")) warnings.add(record.getMessage());
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        server.getLogger().addHandler(logs);
    }

    @AfterEach void cleanup() throws Exception {
        server.getLogger().removeHandler(logs);
        bridge.close();
        for (Provider provider : providers) provider.close();
        MockBukkit.unmock();
    }

    @Test void absentProvidersLeaveVanillaItemsUsableAndCustomItemsUnavailable() throws Exception {
        assertEquals(new ItemStack(Material.BONE), bridge.create("BONE"));
        assertTrue(bridge.matches("BONE", new ItemStack(Material.BONE)));
        assertNull(bridge.create("m.toy.bone"));
        assertNull(bridge.create("ia.pets:bone"));
        assertFalse(bridge.matches("m.toy.bone", new ItemStack(Material.BONE)));
        assertFalse(bridge.matches("ia.pets:bone", new ItemStack(Material.BONE)));
        assertEquals(2, warnings.size());
        assertTrue(warnings.stream().allMatch(message -> message.contains("is unavailable")));
    }

    @Test void disabledProvidersAreNotQueriedAndCanBecomeAvailableLater() throws Exception {
        var mmo = provider("MMOItems");
        var ia = provider("ItemsAdder");
        mmo.active(false); ia.active(false);
        mmo.set("identifyFails", true); ia.set("identifyFails", true);
        assertTrue(bridge.matches("BONE", new ItemStack(Material.BONE)));
        assertNull(bridge.create("m.toy.bone"));
        assertNull(bridge.create("ia.pets:bone"));
        mmo.set("identifyFails", false); ia.set("identifyFails", false);
        mmo.set("item", mmoItem(Material.BONE, "TOY", "BONE"));
        ia.set("item", iaItem(Material.STICK, "pets:bone"));
        mmo.active(true); ia.active(true);
        assertEquals(Material.BONE, bridge.create("m.toy.bone").getType());
        assertEquals(Material.STICK, bridge.create("ia.pets:bone").getType());
    }

    @Test void mmoItemsKeepTheirIdentityAndReturnIndependentSingleItemCopies() throws Exception {
        var mmo = provider("MMOItems");
        var item = mmoItem(Material.BONE, "TOY", "BONE");
        item.setAmount(12); mmo.set("item", item);
        ItemStack created = bridge.create("m.toy.bone");
        assertNotSame(item, created);
        assertEquals(1, created.getAmount());
        assertEquals(12, item.getAmount());
        assertEquals("TOY", mmo.get("requestedType"));
        assertEquals("BONE", mmo.get("requestedId"));
        assertTrue(bridge.matches("m.toy.bone", created));
        assertFalse(bridge.matches("BONE", created));
        assertFalse(bridge.matches("m.toy.stick", created));
        assertEquals(created, bridge.create("m.toy.bone"), "Cached reflection retains provider behavior");
        assertTrue(warnings.isEmpty());
    }

    @Test void itemsAdderKeepsItsIdentityAndDoesNotMutateItsTemplate() throws Exception {
        var ia = provider("ItemsAdder");
        var item = iaItem(Material.STICK, "pets:bone");
        var namedMeta = item.getItemMeta();
        namedMeta.displayName(net.kyori.adventure.text.Component.text("Favorite bone"));
        item.setItemMeta(namedMeta);
        item.setAmount(8); ia.set("item", item);
        Object configured = bridge.ref.getMethod("parse", String.class).invoke(null, "ia.pets:bone");
        assertEquals("Favorite bone", bridge.ref.getMethod("name").invoke(configured));
        assertEquals(namedMeta.displayName(), bridge.ref.getMethod("displayName").invoke(configured));
        ItemStack created = bridge.create("ia.pets:bone");
        assertNotSame(item, created);
        assertEquals(1, created.getAmount());
        assertEquals(8, item.getAmount());
        assertEquals("pets:bone", ia.get("requestedId"));
        assertTrue(bridge.matches("ia.pets:bone", created));
        assertFalse(bridge.matches("STICK", created));
        assertFalse(bridge.matches("ia.pets:other", created));
        assertEquals(created, bridge.create("ia.pets:bone"));
        assertTrue(warnings.isEmpty());
    }

    @Test void bothProvidersCanIdentifyOneItemWithoutHidingEitherIdentity() throws Exception {
        provider("MMOItems"); provider("ItemsAdder");
        var item = mmoItem(Material.BONE, "TOY", "BONE");
        tag(item, "ia_id", "pets:bone");
        assertTrue(bridge.matches("m.toy.bone", item));
        assertTrue(bridge.matches("ia.pets:bone", item));
        assertFalse(bridge.matches("BONE", item));
        assertTrue(bridge.matches("BONE", new ItemStack(Material.BONE)));
        var blankType = mmoItem(Material.BONE, " ", "BONE");
        assertTrue(bridge.matches("BONE", blankType), "Blank MMO type is not a custom identity");
    }

    @Test void missingMmoTypeAndUnknownItemsProduceNoProviderFallback() throws Exception {
        var mmo = provider("MMOItems");
        mmo.type.getField("unavailable").set(null, true);
        assertNull(bridge.create("m.unknown.bone"));
        assertNull(mmo.get("requestedId"), "Unknown types never reach the item factory");
        mmo.type.getField("unavailable").set(null, false);
        assertNull(bridge.create("m.toy.missing"));
        mmo.set("item", mmoItem(Material.BONE, "TOY", "FALLBACK"));
        assertNull(bridge.create("m.toy.requested"), "A provider fallback is not the configured toy");
        mmo.set("item", new ItemStack(Material.BONE));
        assertNull(bridge.create("m.toy.plain"));
        assertEquals(4, warnings.size());
    }

    @Test void missingItemsAdderDefinitionsNullStacksAndFallbackIdsAreUnavailable() throws Exception {
        var ia = provider("ItemsAdder");
        ia.set("missing", true);
        assertNull(bridge.create("ia.pets:missing"));
        ia.set("missing", false);
        assertNull(bridge.create("ia.pets:null_stack"));
        ia.set("item", iaItem(Material.BONE, "pets:fallback"));
        assertNull(bridge.create("ia.pets:requested"));
        ia.set("item", new ItemStack(Material.BONE));
        assertNull(bridge.create("ia.pets:plain"));
        assertEquals(4, warnings.size());
    }

    @Test void identificationFailureRefusesVanillaAndCustomMatchesAndWarnsOnlyOnce() throws Exception {
        var mmo = provider("MMOItems");
        var ia = provider("ItemsAdder");
        var item = mmoItem(Material.BONE, "TOY", "BONE");
        mmo.set("identifyFails", true);
        assertFalse(bridge.matches("BONE", item));
        assertEquals(new ItemStack(Material.BONE), bridge.create("BONE"), "Vanilla icons need no provider API");
        assertFalse(bridge.matches("m.toy.bone", item));
        mmo.set("identifyFails", false); ia.set("identifyFails", true);
        assertFalse(bridge.matches("m.toy.bone", item), "Unknown IA identity fails closed as well");
        assertFalse(bridge.matches("ia.pets:bone", item));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("refusing to match"));
        ia.set("identifyFails", false);
        assertTrue(bridge.matches("m.toy.bone", item), "Provider recovery resumes matching");
    }

    @Test void creationExceptionsAreLoggedOncePerItemAndRecoverOnLaterRequests() throws Exception {
        var mmo = provider("MMOItems"); var ia = provider("ItemsAdder");
        mmo.set("createFails", true); ia.set("createFails", true);
        assertNull(bridge.create("m.toy.bone"));
        assertNull(bridge.create("m.toy.bone"));
        assertNull(bridge.create("ia.pets:bone"));
        assertEquals(2, warnings.size());
        assertTrue(warnings.stream().allMatch(message -> message.contains("Could not create icon")
                && message.contains("InvocationTargetException")));
        mmo.set("createFails", false); ia.set("createFails", false);
        mmo.set("item", mmoItem(Material.BONE, "TOY", "BONE"));
        ia.set("item", iaItem(Material.STICK, "pets:bone"));
        assertNull(bridge.create("m.toy.bone"), "Failures are retained during the retry interval");
        assertNull(bridge.create("ia.pets:bone"));
        assertEquals(1, mmo.get("creates")); assertEquals(1, ia.get("creates"));
        bridge.expire("m.toy.bone"); bridge.expire("ia.pets:bone");
        assertNotNull(bridge.create("m.toy.bone"));
        assertNotNull(bridge.create("ia.pets:bone"));
    }

    @Test void enabledProviderWithoutCompatibleApiFailsClosedAndCanBeReplaced() throws Exception {
        server.providers.put("MMOItems", incompatiblePlugin("MMOItems"));
        assertFalse(bridge.matches("BONE", new ItemStack(Material.BONE)));
        assertNull(bridge.create("m.toy.bone"));
        var mmo = provider("MMOItems");
        mmo.set("item", mmoItem(Material.BONE, "TOY", "BONE"));
        assertNotNull(bridge.create("m.toy.bone"));
        server.providers.put("ItemsAdder", incompatiblePlugin("ItemsAdder"));
        assertFalse(bridge.matches("BONE", new ItemStack(Material.BONE)));
        assertNull(bridge.create("ia.pets:bone"));
        var ia = provider("ItemsAdder");
        ia.set("item", iaItem(Material.STICK, "pets:bone"));
        assertNotNull(bridge.create("ia.pets:bone"));
        assertEquals(3, warnings.size(), "Identification warning is shared; creation warns per configured ID");
        assertTrue(warnings.stream().anyMatch(message -> message.contains("ClassNotFoundException")));
    }

    @Test void replacingMmoItemsUsesTheNewPluginAndClassloader() throws Exception {
        assertReplacement("MMOItems", "m.toy.bone");
    }

    @Test void replacingItemsAdderUsesTheNewPluginAndClassloader() throws Exception {
        assertReplacement("ItemsAdder", "ia.pets:bone");
    }

    @Test void incompatibleMmoReplacementCannotUseThePreviousProvidersItems() throws Exception {
        assertIncompatibleReplacement("MMOItems", "m.toy.bone");
    }

    @Test void incompatibleItemsAdderReplacementCannotUseThePreviousProvidersItems() throws Exception {
        assertIncompatibleReplacement("ItemsAdder", "ia.pets:bone");
    }

    @Test void mismatchedVanillaMaterialNeverQueriesProviders() throws Exception {
        var mmo = provider("MMOItems"); var ia = provider("ItemsAdder");
        mmo.set("identifyFails", true); ia.set("identifyFails", true);
        assertFalse(bridge.matches("STICK", new ItemStack(Material.BONE)));
        assertEquals(0, mmo.get("identifies"));
        assertEquals(0, ia.get("identifies"));
        assertTrue(warnings.isEmpty());
    }

    @Test void oneIdentityRecognizesEveryCareCategoryAndBothProviders() throws Exception {
        var mmo = provider("MMOItems"); var ia = provider("ItemsAdder");
        var stack = mmoItem(Material.BONE, "pets", "care");
        tag(stack, "ia_id", "pets:care");
        var ref = ItemRef.parse("m.pets.care");
        var refs = List.of(ItemRef.parse("m.pets.other"), ref);
        var items = new net.tfminecraft.companionpets.config.PetItems(Map.of(ref, 55.0), refs, refs, refs, refs);
        try (var held = HeldItem.of(stack).scope()) {
            assertTrue(items.isTreat(held));
            assertTrue(items.isMedicine(stack));
            assertTrue(items.isBrush(stack));
            assertEquals(ref, items.toy(held));
            assertEquals(55.0, items.foodGain(held));
            assertTrue(held.matches(ItemRef.parse("ia.pets:care")));
            assertFalse(held.matches(ItemRef.parse("BONE")));
            assertEquals(List.of(ref, ItemRef.parse("ia.pets:care")), held.keys());
        }
        assertEquals(1, mmo.get("identifies"));
        assertEquals(1, ia.get("identifies"));
    }

    @Test void clickSharesIdentityBetweenHandlingAndUseAndIndicesPreserveEggPrecedence() throws Exception {
        var mmo = provider("MMOItems"); var ia = provider("ItemsAdder");
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.loadFromString("""
                pets:
                  legacy: {entity: WOLF, egg: BONE, egg-custom-model-data: 42}
                  adder: {entity: WOLF, egg: 'ia.pets:egg', toys: ['ia.pets:egg']}
                  mmo: {entity: WOLF, egg: m.pets.egg, toys: [m.pets.egg]}
                """);
        var plugin = MockBukkit.createMockPlugin();
        var config = net.tfminecraft.companionpets.config.CompanionConfig.load(plugin, yaml);
        var stack = mmoItem(Material.BONE, "PETS", "EGG");
        tag(stack, "ia_id", "pets:egg");
        var meta = stack.getItemMeta(); meta.setCustomModelData(42); stack.setItemMeta(meta);
        var held = HeldItem.of(stack);
        assertEquals("adder", config.eggType(held).id(), "Configuration order wins between custom providers");
        assertEquals(java.util.Set.of(config.type("adder"), config.type("mmo")), config.toyTypes(held));
        assertTrue(config.type("mmo").matchesEgg(held));
        assertTrue(config.type("legacy").matchesEgg(held));
        var key = new NamespacedKey(plugin, "pet");
        var visual = new net.tfminecraft.companionpets.visual.IdleVisual();
        var runtime = new net.tfminecraft.companionpets.runtime.PetRuntime(plugin, config,
                new net.tfminecraft.companionpets.store.PetStore(plugin), new net.tfminecraft.companionpets.session.Sessions(),
                new net.tfminecraft.companionpets.body.Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        var actions = new net.tfminecraft.companionpets.runtime.PetActions(runtime);
        var listener = new net.tfminecraft.companionpets.listen.PetListener(runtime, actions);
        var player = server.addPlayer();
        mmo.set("identifies", 0); ia.set("identifies", 0);
        var event = new org.bukkit.event.player.PlayerInteractEvent(player,
                org.bukkit.event.block.Action.RIGHT_CLICK_AIR, stack, null, org.bukkit.block.BlockFace.UP,
                org.bukkit.inventory.EquipmentSlot.HAND);
        listener.onUse(event);
        assertEquals(org.bukkit.event.Event.Result.DENY, event.useItemInHand());
        assertEquals("adder", runtime.sessions().hatch(player.getUniqueId()).typeId());
        assertEquals(1, mmo.get("identifies")); assertEquals(1, ia.get("identifies"));
        mmo.set("identifyFails", true);
        assertEquals("legacy", config.byEgg(stack).id(), "Legacy models still match if identification fails");
        assertTrue(config.toyTypes(HeldItem.of(stack)).isEmpty());
        var missingId = mmoItem(Material.BONE, "PETS", null);
        assertFalse(HeldItem.of(missingId).matches(ItemRef.parse("BONE")));
    }

    @Test void iconsNamesAndEggIconsShareIndependentCachedTemplatesForBothProviders() throws Exception {
        var mmo = provider("MMOItems"); var ia = provider("ItemsAdder");
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.loadFromString("""
                pets:
                  mmo: {entity: WOLF, egg: m.toy.bone}
                  adder: {entity: CAT, egg: 'ia.pets:bone'}
                  model: {entity: WOLF, egg: BONE, egg-custom-model-data: 42}
                """);
        var config = net.tfminecraft.companionpets.config.CompanionConfig.load(MockBukkit.createMockPlugin(), yaml);
        var mmoStack = mmoItem(Material.BONE, "TOY", "BONE");
        var iaStack = iaItem(Material.BONE, "pets:bone");
        for (ItemStack stack : List.of(mmoStack, iaStack)) {
            var meta = stack.getItemMeta(); meta.displayName(net.kyori.adventure.text.Component.text("Pet egg"));
            stack.setItemMeta(meta); stack.setAmount(7);
        }
        mmo.set("item", mmoStack); ia.set("item", iaStack);
        for (String id : List.of("mmo", "adder")) {
            var type = config.type(id); var ref = type.egg();
            var first = ref.icon(Material.STICK);
            var meta = first.getItemMeta(); meta.displayName(net.kyori.adventure.text.Component.text("Changed"));
            meta.setCustomModelData(99); first.setItemMeta(meta); first.setAmount(23);
            for (int i = 0; i < 3; i++) {
                assertEquals("Pet egg", ref.name());
                assertEquals(net.kyori.adventure.text.Component.text("Pet egg"), ref.displayName());
                assertEquals(1, ref.icon(Material.STICK).getAmount());
                var egg = type.eggIcon();
                assertEquals(net.kyori.adventure.text.Component.text("Pet egg"), egg.getItemMeta().displayName());
                assertFalse(egg.getItemMeta().hasCustomModelData());
                assertNotSame(first, egg);
            }
        }
        assertEquals(1, mmo.get("creates")); assertEquals(1, ia.get("creates"));
        assertEquals(7, mmoStack.getAmount()); assertEquals(7, iaStack.getAmount());
        var model = config.type("model");
        assertEquals(42, model.eggIcon().getItemMeta().getCustomModelData());
        var modified = model.eggIcon(); var meta = modified.getItemMeta(); meta.setCustomModelData(88); modified.setItemMeta(meta);
        assertEquals(42, model.eggIcon().getItemMeta().getCustomModelData());
        assertFalse(ItemRef.parse("BONE").icon(Material.STICK).getItemMeta().hasCustomModelData());
    }

    @Test void successfulSettingsReloadClearsTemplatesAndCachedNames() throws Exception {
        var mmo = provider("MMOItems");
        var plugin = MockBukkit.load(net.tfminecraft.companionpets.PetsPlugin.class);
        var ref = ItemRef.parse("m.toy.bone");
        var first = mmoItem(Material.BONE, "TOY", "BONE");
        mmo.set("item", first);
        assertEquals(Material.BONE, ref.icon(Material.STICK).getType());
        assertEquals(1, mmo.get("creates"));
        var replacement = mmoItem(Material.STICK, "TOY", "BONE");
        var meta = replacement.getItemMeta(); meta.displayName(net.kyori.adventure.text.Component.text("Reloaded"));
        replacement.setItemMeta(meta); mmo.set("item", replacement);
        assertEquals(Material.BONE, ref.create().getType());
        assertTrue(plugin.onCommand(server.getConsoleSender(), plugin.getCommand("pets"), "pets", new String[]{"reload"}));
        assertEquals(Material.STICK, ref.icon(Material.BONE).getType());
        assertEquals("Reloaded", ref.name());
        assertEquals(2, mmo.get("creates"));
    }

    @Test void absentAndUnknownIconsCacheFallbackNamesUntilExplicitReload() throws Exception {
        var ia = provider("ItemsAdder"); ia.set("missing", true);
        var ref = ItemRef.parse("ia.pets:missing_egg");
        assertEquals(Material.BONE, ref.icon(Material.BONE).getType());
        assertEquals("pets:missing egg", ref.name());
        assertEquals(net.kyori.adventure.text.Component.text("pets:missing egg"), ref.displayName());
        assertEquals(1, ia.get("creates"));
        ia.set("missing", false); ia.set("item", iaItem(Material.STICK, "pets:missing_egg"));
        assertNull(ref.create());
        ItemBridge.clearCache();
        assertEquals(Material.STICK, ref.icon(Material.BONE).getType());
        assertEquals(2, ia.get("creates"));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "companionpets.item-config", matches = ".+")
    void productionSelectorsUseOneIdentityAndIndicesAgreeWithLinearMatching() throws Exception {
        var mmo = provider("MMOItems"); var ia = provider("ItemsAdder");
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.load(new File(System.getProperty("companionpets.item-config")));
        var config = net.tfminecraft.companionpets.config.CompanionConfig.load(MockBukkit.createMockPlugin(), yaml);
        var refs = new java.util.LinkedHashSet<ItemRef>();
        for (String id : yaml.getConfigurationSection("pets").getKeys(false))
            refs.add(ItemRef.parse(yaml.getString("pets." + id + ".egg")));
        assertEquals(14, refs.size());
        for (var items : java.util.stream.Stream.concat(java.util.stream.Stream.of(config.items()),
                config.types().values().stream().map(net.tfminecraft.companionpets.config.PetTypeDef::items)).toList()) {
            refs.addAll(items.foods().keySet()); refs.addAll(items.treats()); refs.addAll(items.medicines());
            refs.addAll(items.brushes()); refs.addAll(items.toys());
        }
        for (var ref : refs) {
            ItemStack stack = switch (ref.kind()) {
                case VANILLA -> new ItemStack(ref.material());
                case MMOITEMS -> mmoItem(Material.BONE, ref.type(), ref.id());
                case ITEMSADDER -> iaItem(Material.BONE, ref.id());
            };
            mmo.set("identifies", 0); ia.set("identifies", 0);
            var held = HeldItem.of(stack);
            assertTrue(held.matches(ref));
            var egg = config.eggType(held); var toys = config.toyTypes(held);
            for (var type : config.types().values()) {
                type.isTreat(held); type.foodGain(held); type.items().isMedicine(held); type.items().isBrush(held);
            }
            assertEquals(1, mmo.get("identifies")); assertEquals(1, ia.get("identifies"));
            var expectedEgg = config.types().values().stream()
                    .filter(type -> type.egg().kind() != ItemRef.Kind.VANILLA && type.matchesEgg(stack)).findFirst()
                    .orElseGet(() -> config.types().values().stream().filter(type -> type.matchesEgg(stack)).findFirst().orElse(null));
            assertEquals(expectedEgg, egg, ref.key());
            var expectedToys = config.types().values().stream().filter(type -> type.acceptsToy(stack))
                    .collect(java.util.stream.Collectors.toSet());
            assertEquals(expectedToys, toys, ref.key());
            for (var type : config.types().values()) {
                assertEquals(type.isTreat(stack), type.isTreat(held));
                assertEquals(type.foodGain(stack), type.foodGain(held));
                assertEquals(type.items().isMedicine(stack), type.items().isMedicine(held));
                assertEquals(type.items().isBrush(stack), type.items().isBrush(held));
            }
        }
    }

    private void assertIncompatibleReplacement(String name, String token) throws Exception {
        var original = provider(name);
        var item = name.equals("MMOItems") ? mmoItem(Material.BONE, "TOY", "BONE")
                : iaItem(Material.BONE, "pets:bone");
        original.set("item", item);
        assertNotNull(bridge.create(token));
        original.active(false);
        server.providers.put(name, incompatiblePlugin(name));
        assertNull(bridge.create(token), "A replacement with an unavailable API must not reuse the previous template");
        assertFalse(bridge.matches(token, item), "A previous provider's identity does not establish the replacement's identity");
        var recovered = provider(name);
        recovered.set("item", item);
        assertNotNull(bridge.create(token));
        assertTrue(bridge.matches(token, item));
    }

    private void assertReplacement(String name, String token) throws Exception {
        var first = provider(name);
        first.set("item", name.equals("MMOItems") ? mmoItem(Material.BONE, "TOY", "BONE")
                : iaItem(Material.BONE, "pets:bone"));
        assertEquals(Material.BONE, bridge.create(token).getType());
        first.active(false);
        assertNull(bridge.create(token), "Disabled providers must not use cached reflection");
        var replacement = provider(name);
        replacement.set("item", name.equals("MMOItems") ? mmoItem(Material.STICK, "TOY", "BONE")
                : iaItem(Material.STICK, "pets:bone"));
        ItemStack reloaded = bridge.create(token);
        assertNotNull(reloaded);
        assertEquals(Material.STICK, reloaded.getType(), "Provider replacement must discard the old API cache");
        assertTrue(bridge.matches(token, reloaded));
    }

    private static Plugin incompatiblePlugin(String name) {
        return (Plugin) java.lang.reflect.Proxy.newProxyInstance(ItemBridgeTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "isEnabled" -> true;
                    case "getName", "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private Provider provider(String name) throws Exception {
        var provider = new Provider(name, server);
        providers.add(provider);
        server.providers.put(name, provider.plugin);
        provider.active(true);
        return provider;
    }

    private static ItemStack mmoItem(Material material, String type, String id) {
        var item = new ItemStack(material);
        tag(item, "mmo_type", type); tag(item, "mmo_id", id);
        return item;
    }

    private static ItemStack iaItem(Material material, String id) {
        var item = new ItemStack(material); tag(item, "ia_id", id); return item;
    }

    private static void tag(ItemStack item, String key, String value) {
        var meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(new NamespacedKey("fixture", key), PersistentDataType.STRING, value);
        item.setItemMeta(meta);
    }

    private static final class ProviderServer extends ServerMock {
        private final Map<String, Plugin> providers = new HashMap<>();
        private final PluginManagerMock plugins = new PluginManagerMock(this) {
            @Override public Plugin getPlugin(String name) {
                return providers.containsKey(name) ? providers.get(name) : super.getPlugin(name);
            }
        };
        @Override public PluginManagerMock getPluginManager() { return plugins == null ? super.getPluginManager() : plugins; }
    }

    private static final class Provider extends URLClassLoader {
        private final Class<?> api;
        private final Class<?> type;
        private final Plugin plugin;
        Provider(String name, Server server) throws Exception {
            super(new URL[]{classes.toUri().toURL()}, ItemBridgeTest.class.getClassLoader());
            Class<?> pluginClass = loadClass("fixture.Provider");
            plugin = (Plugin) pluginClass.getConstructor(String.class, Server.class, File.class)
                    .newInstance(name, server, classes.toFile());
            api = name.equals("MMOItems") ? pluginClass : loadClass("dev.lone.itemsadder.api.CustomStack");
            type = loadClass("net.Indyuce.mmoitems.api.Type");
        }
        void set(String field, Object value) throws Exception { api.getField(field).set(null, value); }
        Object get(String field) throws Exception { return api.getField(field).get(null); }
        void active(boolean active) throws Exception { plugin.getClass().getMethod("active", boolean.class).invoke(plugin, active); }
    }

    private static final class Bridge extends URLClassLoader {
        private final Class<?> ref;
        Bridge() throws Exception {
            super(new URL[0], ItemBridge.class.getClassLoader());
            ref = loadClass(ItemRef.class.getName());
        }
        ItemStack create(String token) throws Exception {
            return (ItemStack) ref.getMethod("create").invoke(ref.getMethod("parse", String.class).invoke(null, token));
        }
        @SuppressWarnings("unchecked")
        void expire(String token) throws Exception {
            var field = loadClass(ItemBridge.class.getName()).getDeclaredField("templates"); field.setAccessible(true);
            var templates = (Map<Object, Object>) field.get(null);
            var configured = ref.getMethod("parse", String.class).invoke(null, token);
            var constructor = templates.get(configured).getClass().getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            templates.put(configured, constructor.newInstance(null, null, null, 0L));
        }
        boolean matches(String token, ItemStack item) throws Exception {
            return (boolean) ref.getMethod("matches", ItemStack.class)
                    .invoke(ref.getMethod("parse", String.class).invoke(null, token), item);
        }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(ItemBridge.class.getName()) && !name.startsWith(ItemRef.class.getName()) && !name.startsWith(HeldItem.class.getName()))
                return super.loadClass(name, resolve);
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (input == null) throw new ClassNotFoundException(name);
                    byte[] bytes = input.readAllBytes();
                    loaded = defineClass(name, bytes, 0, bytes.length, ItemBridge.class.getProtectionDomain());
                } catch (IOException exception) { throw new ClassNotFoundException(name, exception); }
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }
}
