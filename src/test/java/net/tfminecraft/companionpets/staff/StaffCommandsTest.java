package net.tfminecraft.companionpets.staff;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.Files;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.runtime.*;
import net.tfminecraft.companionpets.session.*;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.visual.IdleVisual;

class StaffCommandsTest {
    private ServerMock server;
    private JavaPlugin plugin;
    private PlayerMock staff;
    private PlayerMock owner;
    private PetRuntime runtime;
    private StaffCommands commands;
    private TestCommands tests;
    private Pet pet;
    private boolean forbidOfflineScan;

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(new ServerMock() {
            @Override public org.bukkit.OfflinePlayer[] getOfflinePlayers() {
                if (forbidOfflineScan) throw new AssertionError("Staff commands must not enumerate all offline players");
                return super.getOfflinePlayers();
            }
        }); server.addSimpleWorld("world");
        plugin = MockBukkit.createMockPlugin();
        staff = server.addPlayer("Staff"); staff.setOp(true); staff.openInventory(server.createInventory(null, 9));
        owner = server.addPlayer("Owner");
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
                limits: {max-stored: 2, max-out: 2}
                items:
                  treats: [COD, SALMON]
                  foods: [{item: BEEF, hunger: 35}]
                  medicines: [MILK_BUCKET, HONEY_BOTTLE]
                  brushes: [FEATHER]
                  toys: [STICK]
                pets:
                  wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG}
                  selective: {entity: CAT, egg: CAT_SPAWN_EGG, tricks: [come]}
                """);
        var visual = new IdleVisual(); var key = new NamespacedKey(plugin, "pet");
        var store = new PetStore(plugin); assertTrue(store.load());
        runtime = new PetRuntime(plugin, CompanionConfig.load(plugin, yaml), store, new Sessions(),
                new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        var actions = new PetActions(runtime);
        commands = new StaffCommands(runtime, actions); tests = new TestCommands(runtime, actions, commands);
        server.getPluginManager().registerEvents(commands.menus(), plugin);
        pet = add(owner.getUniqueId(), "wolf", "Toby", true);
    }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void createAcceptsWhitespaceAroundCommaSeparatedTrickIds() {
        command("create", "Owner", "type=wolf", "name=Luna", "tricks=follow,", "sit,", "lay:duerme");
        var created = runtime.store().of(owner.getUniqueId()).stream().filter(p -> !p.id().equals(pet.id())).findFirst().orElseThrow();
        assertEquals(Trick.FOLLOW, created.trickFor("follow"));
        assertEquals(Trick.SIT, created.trickFor("sit"));
        assertEquals(Trick.LAY, created.trickFor("duerme"));
        assertEquals(100, created.progress(Trick.SIT));
        assertEquals(100, created.progress(Trick.LAY));
    }

    @Test void invalidEggAmountsExplainTheRangeAndDeliverNothing() {
        for (String amount : List.of("abc", "0", "65", "999999999999999999999999")) {
            command("egg", "wolf", "Owner", amount);
            assertTrue(staff.nextMessage().contains("between 1 and 64"));
            assertTrue(owner.getInventory().isEmpty());
        }
    }

    @Test void createRebuildsAPlayersPetWithSpecifiedLearningWordsAndStatsInTheirPetHouse() throws Exception {
        command("create", "Owner", "wolf", "Toby", "de", "prueba", "tricks=sit:sientate,lay:duerme", "hunger=61", "mood=72", "energy=83", "cleanliness=94", "health=55", "bond=66", "sex=female", "personality=shy", "agehours=48");
        var created = runtime.store().all().stream().filter(p -> !p.id().equals(pet.id())).findFirst().orElseThrow();
        assertEquals(owner.getUniqueId(), created.ownerId()); assertEquals("Toby de prueba", created.name());
        assertTrue(created.stored()); assertNull(created.entityId());
        assertEquals(61, created.need(Need.HUNGER)); assertEquals(72, created.need(Need.MOOD)); assertEquals(83, created.need(Need.ENERGY));
        assertEquals(94, created.need(Need.CLEANLINESS)); assertEquals(55, created.need(Need.HEALTH)); assertEquals(66, created.bond());
        assertEquals(PetSex.FEMALE, created.sex()); assertEquals(PetPersonality.SHY, created.personality());
        assertEquals(Trick.FOLLOW, created.trickFor("follow")); assertEquals(Trick.SIT, created.trickFor("sientate")); assertEquals(Trick.LAY, created.trickFor("duerme"));
        assertEquals(100, created.progress(Trick.SIT)); assertEquals(100, created.progress(Trick.LAY));
        assertTrue(Math.abs(System.currentTimeMillis() - created.bornAt() - 48 * 3_600_000L) < 2000);
        var store = new PetStore(plugin); assertTrue(store.load()); assertEquals(Trick.LAY, store.get(created.id()).trickFor("duerme"));
        var audit = Files.readString(plugin.getDataFolder().toPath().resolve("staff-audit.yml.log"));
        assertTrue(audit.contains("create")); assertTrue(audit.contains(staff.getUniqueId().toString())); assertTrue(audit.contains("bond=66.0"));
        assertEquals(100, pet.need(Need.HUNGER), "The existing pet is preserved");
    }

    @Test void createValidatesAllOptionsBeforeWritingAnythingAndChecksQuotaPermissionAndAudit() throws Exception {
        for (String bad : List.of("hunger=NaN", "energy=Infinity", "health=101", "mood=-1", "sex=other", "personality=other", "tricks=spin", "tricks=unknown", "tricks=lay:follow", "unknown=1", "bond=no", "agehours=-1", "health=20", "tricks=sit,")) {
            String[] args = bad.equals("health=20") ? new String[]{"create", "Owner", "wolf", "New", bad, bad} : new String[]{"create", "Owner", "wolf", "New", bad};
            command(args); assertEquals(1, runtime.store().all().size(), bad);
        }
        command("create", "Owner", "missing", "New"); command("create", "UnknownPlayer", "wolf", "New");
        staff.setOp(false); command("create", "Owner", "wolf", "New"); assertEquals(1, runtime.store().all().size()); staff.setOp(true);
        add(owner.getUniqueId(), "wolf", "Second", true); command("create", "Owner", "wolf", "New"); assertEquals(2, runtime.store().all().size());
        var dest = UUID.randomUUID();
        Files.createDirectory(plugin.getDataFolder().toPath().resolve("staff-audit.yml.log"));
        command("create", dest.toString(), "wolf", "New"); assertTrue(runtime.store().of(dest).isEmpty());
    }

    @Test void createSupportsAnOfflineUuidWithoutSpawningABodyAndCompletionIncludesOptions() {
        var offline = UUID.randomUUID(); command("create", offline.toString(), "wolf", "Offline", "tricks=none");
        var created = runtime.store().of(offline).getFirst(); assertTrue(created.stored()); assertNull(created.entityId());
        assertEquals(Trick.FOLLOW, created.trickFor("follow"));
        assertTrue(commands.complete(staff, new String[]{"create", "Owner", ""}).contains("type=wolf"));
        assertEquals(List.of("hunger=0", "hunger=100", "hunger=25", "hunger=50", "hunger=75"), commands.complete(staff, new String[]{"create", "Owner", "wolf", "Toby", "hu"}));
    }

    @Test void namedCreationAcceptsAnyOrderAndMultipleWordNamesAndGuidesEachValue() {
        command("create", "Owner", "name=Luna", "de", "prueba", "energy=75", "type=wolf", "tricks=all", "", "sex=female");
        var created = runtime.store().of(owner.getUniqueId()).stream().filter(p -> !p.id().equals(pet.id())).findFirst().orElseThrow();
        assertEquals("Luna de prueba", created.name()); assertEquals(75, created.need(Need.ENERGY));
        assertEquals(PetSex.FEMALE, created.sex()); assertEquals(100, created.progress(Trick.LAY)); assertEquals(100, created.progress(Trick.FOLLOW));
        assertTrue(commands.complete(staff, new String[]{"create", "Owner", ""}).containsAll(List.of("name=", "name=Toby", "type=wolf")));
        assertEquals(List.of("type=wolf"), commands.complete(staff, new String[]{"create", "Owner", "type=w"}));
        assertEquals(List.of("name=", "name=Luna", "name=Toby"), commands.complete(staff, new String[]{"create", "Owner", "type=wolf", "name="}));
        var next = commands.complete(staff, new String[]{"create", "Owner", "type=wolf", "name=Luna", ""});
        assertFalse(next.stream().anyMatch(s -> s.startsWith("name=") || s.startsWith("type=")));
        assertTrue(next.containsAll(List.of("tricks=all", "tricks=lay", "sex=female", "health=100")));
        var tricks = commands.complete(staff, new String[]{"create", "Owner", "type=wolf", "tricks=follow,"});
        assertTrue(tricks.contains("tricks=follow,lay")); assertFalse(tricks.contains("tricks=follow,follow"));
        assertEquals(List.of("sex=female"), commands.complete(staff, new String[]{"create", "Owner", "type=wolf", "sex=f"}));
    }

    @Test void missingAndRepeatedNamedArgumentsNeverCreateAPet() {
        for (String[] args : List.of(
                new String[]{"create", "Owner", "type=wolf"},
                new String[]{"create", "Owner", "name=Luna"},
                new String[]{"create", "Owner", "type=wolf", "name="},
                new String[]{"create", "Owner", "type=wolf", "name=Luna", "type=selective"},
                new String[]{"create", "Owner", "type=wolf", "name=Luna", "health="})) {
            command(args); assertEquals(1, runtime.store().all().size());
        }
    }

    @Test void staffProfileSharesThePlayerLayoutAndTricksButContainsNoManagementActions() {
        var playerMenus = new net.tfminecraft.companionpets.gui.PetMenus(runtime);
        playerMenus.openCare(owner, pet, true);
        var normal = owner.getOpenInventory().getTopInventory();
        command("list", "Owner", "Toby"); var info = menu().getInventory();
        assertEquals(normal.getSize(), info.getSize()); assertEquals(45, info.getSize());
        for (int slot : new int[]{4, 11, 15, 20, 21, 22, 23, 24, 30, 31, 32, 40})
            assertEquals(normal.getItem(slot), info.getItem(slot), "Common profile slot " + slot);
        assertEquals(Material.WHITE_DYE, info.getItem(11).getType());
        for (int slot : new int[]{38, 42, 44}) assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, info.getItem(slot).getType());
        for (int slot : new int[]{13, 38, 42, 44}) menu().click(slot);
        assertEquals("Toby", pet.name()); assertTrue(pet.stored()); assertNull(runtime.sessions().rename(staff.getUniqueId()));
        menu().click(40); assertEquals(27, menu().getInventory().getSize());
        assertEquals(Material.ITEM_FRAME, menu().getInventory().getItem(18).getType());
        commands.menus().list(staff, owner.getUniqueId(), 0);
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, menu().getInventory().getItem(45).getType());
        assertEquals(Material.ARROW, menu().getInventory().getItem(53).getType());
    }

    @Test void eggCommandSupportsSelfEachConfiguredTypeAndAllAndRequiresPermission() {
        command("egg", "wolf"); assertEquals(Material.WOLF_SPAWN_EGG, staff.getInventory().getItem(0).getType());
        command("egg", "all", "Owner", "2");
        assertEquals(2, owner.getInventory().getItem(0).getAmount()); assertEquals(Material.WOLF_SPAWN_EGG, owner.getInventory().getItem(0).getType());
        assertEquals(2, owner.getInventory().getItem(1).getAmount()); assertEquals(Material.CAT_SPAWN_EGG, owner.getInventory().getItem(1).getType());
        staff.setOp(false); command("egg", "wolf", "Owner", "3"); assertEquals(2, owner.getInventory().getItem(0).getAmount());
        staff.setOp(true); assertTrue(commands.complete(staff, new String[]{"egg", ""}).containsAll(List.of("all", "wolf", "selective")));
    }

    @Test void everyStaffInventoryUsesOneBackgroundIncludingConfirmationAndDetails() {
        command("list", "Owner", "Toby"); assertBackground();
        menu().click(40); assertBackground();
        command("list", "Owner", "Toby"); menu().click(40); assertBackground();
        command("list", "Owner"); assertBackground();
    }

    private void assertBackground() {
        for (var item : menu().getInventory().getContents()) {
            assertNotNull(item);
            if (item.getType().name().endsWith("STAINED_GLASS_PANE")) assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, item.getType());
        }
    }

    @Test void findReportsSavedAndOrphanBodiesWithoutMutatingOrCreatingPets() {
        assertTrue(PetSearch.find(runtime, null, null, "").isEmpty(), "A healthy stored pet is not considered lost");
        var petHouse = PetSearch.find(runtime, owner.getUniqueId(), null, "Toby"); assertEquals(1, petHouse.size()); assertTrue(petHouse.getFirst().description().contains("Pet House"));
        pet.stored(false); pet.place("missing_world", 1, 2, 3, 0);
        var lost = PetSearch.find(runtime, null, null, ""); assertEquals(1, lost.size()); assertTrue(lost.getFirst().description().contains("world unavailable"));
        var body = owner.getWorld().spawn(owner.getLocation(), org.bukkit.entity.Wolf.class);
        body.getPersistentDataContainer().set(runtime.petKey(), org.bukkit.persistence.PersistentDataType.STRING, pet.id().toString());
        var duplicate = owner.getWorld().spawn(owner.getLocation(), org.bukkit.entity.Wolf.class);
        duplicate.getPersistentDataContainer().set(runtime.petKey(), org.bukkit.persistence.PersistentDataType.STRING, pet.id().toString());
        assertTrue(PetSearch.find(runtime, owner.getUniqueId(), null, "").getFirst().description().contains("duplicate bodies"));
        var orphan = UUID.randomUUID(); duplicate.getPersistentDataContainer().set(runtime.petKey(), org.bukkit.persistence.PersistentDataType.STRING, orphan.toString());
        assertTrue(PetSearch.find(runtime, null, null, "").stream().anyMatch(r -> r.pet() == null && r.description().contains("no saved record")));
        int entities = owner.getWorld().getEntities().size(); command("find", "Owner", "Toby"); command("find", "Owner");
        assertEquals(entities, owner.getWorld().getEntities().size()); assertEquals(1, runtime.store().all().size()); assertEquals("missing_world", pet.worldName());
    }
    private Pet add(UUID owner, String type, String name, boolean stored) {
        var pet = new Pet(UUID.randomUUID(), owner, type, name, PetSex.MALE);
        pet.stored(stored); runtime.store().add(pet); return pet;
    }
    private void command(String... args) { assertTrue(commands.execute(staff, args)); }
    private String id() { return pet.id().toString(); }
    private StaffMenuHolder menu() { return (StaffMenuHolder) staff.getOpenInventory().getTopInventory().getHolder(); }

    @Test void removedCommandsNeitherMutatePetsNorAppearInCompletion() {
        pet.need(Need.HEALTH, 20);
        for (String removed : List.of("animation", "freeze", "cleantestpets", "heal", "needs", "transfer", "teach", "validate", "store", "remove", "rename", "recover")) {
            command(removed, id(), "all", "100");
            assertTrue(commands.complete(staff, new String[]{removed}).isEmpty());
            assertTrue(commands.complete(staff, new String[]{removed, ""}).isEmpty());
        }
        assertEquals(20, pet.need(Need.HEALTH));
        assertEquals(owner.getUniqueId(), pet.ownerId()); assertNotNull(runtime.store().get(pet.id()));
    }

    @Test void completionsShowPlayersThenOnlyTheirPetNamesIncludingMultipleWords() {
        add(staff.getUniqueId(), "wolf", "Staff pet", true);
        add(owner.getUniqueId(), "wolf", "Toby Junior", true);
        for (String action : List.of("list", "find")) {
            assertEquals(List.of("Owner", "Staff"), commands.complete(staff, new String[]{action, ""}));
            assertEquals(List.of("Toby", "Toby Junior"), commands.complete(staff, new String[]{action, "Owner", ""}));
            assertEquals(List.of("Junior"), commands.complete(staff, new String[]{action, "Owner", "Toby", "J"}));
            assertTrue(commands.complete(staff, new String[]{action, "Staff", "Toby"}).isEmpty());
        }
    }

    @Test void offlineOwnerResolutionAndCompletionUseKnownPlayersWithoutScanningHistory() {
        var unrelated = server.addPlayer("Historical"); unrelated.disconnect();
        owner.disconnect();
        forbidOfflineScan = true;
        try {
            assertEquals(owner.getUniqueId(), commands.resolveOwner("Owner"));
            assertEquals(unrelated.getUniqueId(), commands.resolveOwner("Historical"));
            UUID unknown = UUID.randomUUID();
            assertEquals(unknown, commands.resolveOwner(unknown.toString()));
            assertThrows(IllegalArgumentException.class, () -> commands.resolveOwner("NotCached"));
            assertEquals(List.of("Owner", "Staff"), commands.complete(staff, new String[]{"create", ""}));
            for (String action : List.of("list", "find")) {
                assertEquals(List.of("Owner"), commands.complete(staff, new String[]{action, ""}));
                assertEquals(List.of("Toby"), commands.complete(staff, new String[]{action, "Owner", ""}));
            }
            command("list", "Owner");
            assertEquals(owner.getUniqueId(), menu().subject());
        } finally {
            forbidOfflineScan = false;
        }
    }

    @Test void listStartsWithTheSelectedOwnersPetsAndHasNoParentBackButton() {
        var previous = staff.getOpenInventory().getTopInventory();
        command("list"); assertSame(previous, staff.getOpenInventory().getTopInventory());
        command("find"); assertSame(previous, staff.getOpenInventory().getTopInventory());
        command("list", "Owner"); assertEquals(StaffMenuHolder.Kind.LIST, menu().kind()); assertEquals(owner.getUniqueId(), menu().subject());
        menu().click(0); assertEquals(StaffMenuHolder.Kind.INSPECT, menu().kind()); assertEquals(pet.id(), menu().subject());
        for (var item : menu().getInventory().getContents()) assertFalse(item.toString().contains(id()));
        menu().click(36); assertEquals(StaffMenuHolder.Kind.LIST, menu().kind());
        var selectedList = menu(); menu().click(45); assertSame(selectedList, menu());
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, menu().getInventory().getItem(45).getType());
    }

    @Test void consoleListsNamesAndFindLocationsWithoutGuidsOrAuditDumps() {
        var console = server.getConsoleSender();
        commands.execute(console, "list", "Owner");
        assertTrue(console.nextMessage().contains("Owner")); assertTrue(console.nextMessage().contains("Toby"));
        commands.execute(console, "find", "Owner", "Toby");
        assertTrue(console.nextMessage().contains("Owner"));
        String location = console.nextMessage(); assertTrue(location.contains("Toby")); assertTrue(location.contains("Pet House"));
        assertFalse(location.contains(id())); assertFalse(location.contains(owner.getUniqueId().toString()));
    }

    @Test void duplicateNamesCannotRecoverWrongPetButMenuSelectionKeepsIdentity() {
        var other = add(owner.getUniqueId(), "wolf", "Toby", true);
        assertThrows(IllegalArgumentException.class, () -> commands.resolveNamedPet("Owner", "Toby"));
        command("list", "Owner", "Toby"); assertTrue(pet.stored()); assertTrue(other.stored());
        command("list", "Owner"); menu().click(1); assertNotNull(menu().subject());
        assertTrue(Set.of(pet.id(), other.id()).contains(menu().subject()));
    }

    @Test void readOnlyPermissionCanBrowseButCannotRecoverOrUseInventoryMutations() {
        staff.setOp(false); staff.addAttachment(plugin, "companionpets.admin.list", true);
        assertEquals(List.of("inspect", "list"), commands.complete(staff, new String[]{""}));
        command("list", "Owner", "Toby"); assertEquals(StaffMenuHolder.Kind.INSPECT, menu().kind());
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, menu().getInventory().getItem(38).getType());
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, menu().getInventory().getItem(44).getType());
    }

    @Test void listIncludesOfflineOwnersAndPaginatesEveryPet() {
        UUID offline = UUID.randomUUID();
        for (int index = 0; index < 90; index++) add(offline, "wolf", "Pet " + index, true);
        commands.menus().list(staff, offline, 0);
        assertEquals(StaffMenuHolder.Kind.LIST, menu().kind()); assertEquals(0, menu().page());
        menu().click(53); assertEquals(1, menu().page());
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, menu().getInventory().getItem(45).getType());
        assertEquals(Material.ARROW, menu().getInventory().getItem(53).getType());
        var last = menu(); int heard = staff.getHeardSounds().size();
        menu().click(53); assertSame(last, menu()); assertEquals(heard + 1, staff.getHeardSounds().size());
        var click = new InventoryClickEvent(staff.getOpenInventory(), InventoryType.SlotType.CONTAINER, 53, ClickType.RIGHT, InventoryAction.PICKUP_HALF);
        commands.menus().onClick(click); assertTrue(click.isCancelled()); assertEquals(0, menu().page());
    }

    @Test void inventoryCancelsTransfersAndRechecksPermissions() {
        command("list", "Owner", "Toby");
        var click = new InventoryClickEvent(staff.getOpenInventory(), InventoryType.SlotType.CONTAINER, 32, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        staff.setOp(false); commands.menus().onClick(click); assertTrue(click.isCancelled()); assertTrue(pet.stored());
        staff.setOp(true); command("list", "Owner", "Toby");
        var shift = new InventoryClickEvent(staff.getOpenInventory(), InventoryType.SlotType.CONTAINER, 54, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        commands.menus().onClick(shift); assertTrue(shift.isCancelled());
        var drag = new InventoryDragEvent(staff.getOpenInventory(), new ItemStack(Material.STONE), new ItemStack(Material.STONE), false, Map.of(0, new ItemStack(Material.STONE)));
        commands.menus().onDrag(drag); assertTrue(drag.isCancelled());
    }

    @Test void detailsAndRefreshKeepSelectedPetIdentity() {
        command("list", "Owner", "Toby"); menu().click(40); assertEquals(StaffMenuHolder.Kind.TRICKS, menu().kind());
        menu().click(18); assertEquals(pet.id(), menu().subject());
    }

    @Test void legacyTestFlagsAreIgnoredAndNotWrittenBack() throws Exception {
        assertTrue(runtime.store().save());
        var file = new java.io.File(plugin.getDataFolder(), "pets.yml");
        var yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("pets." + pet.id() + ".test-pet", true); yaml.set("pets." + pet.id() + ".test-frozen", true); yaml.save(file);
        var reloaded = new PetStore(plugin); assertTrue(reloaded.load());
        assertNotNull(reloaded.get(pet.id())); assertTrue(reloaded.save());
        String saved = Files.readString(file.toPath());
        assertFalse(saved.contains("test-pet")); assertFalse(saved.contains("test-frozen"));
    }

    @Test void inspectShowsInheritedModelVoiceAndMissingCapabilitiesWithoutMutatingPets() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
                species:
                  dog:
                    voice: {preset: frog, pitch: 1.3}
                    behaviors: {remove: [dig-gifts]}
                pets:
                  wolf: {species: dog, model: beagle, egg: WOLF_SPAWN_EGG}
                """);
        runtime.config(CompanionConfig.load(plugin, yaml));
        int pets = runtime.store().all().size(), entities = owner.getWorld().getEntities().size();
        command("inspect", "wolf");
        var messages = new ArrayList<String>();
        String message;
        while ((message = staff.nextMessage()) != null) messages.add(message);
        String report = String.join("\n", messages);
        assertTrue(report.contains("Species: dog"));
        assertTrue(report.contains("Voice: frog | Pitch multiplier: 1.3"));
        assertTrue(report.contains("Profile: preset"));
        assertTrue(report.contains("Behaviors active:"));
        assertFalse(report.contains("dig-gifts"));
        assertTrue(report.contains("Disabled toy-tail-wag: model unavailable"));
        assertTrue(report.contains("missing animations: belly_up, get_up, lie_back"));
        assertTrue(report.contains("Tricks configured:"));
        assertTrue(report.contains("Default learned tricks: follow"));
        assertTrue(report.contains("Model: beagle"));
        assertTrue(report.contains("Missing lie -> lie"));
        assertEquals(pets, runtime.store().all().size());
        assertEquals(entities, owner.getWorld().getEntities().size());
        assertTrue(pet.stored());
    }

    @Test void inspectIdentifiesAGeneratedVoiceAndItsResolvedEventSounds() throws Exception {
        var yaml = new YamlConfiguration(); yaml.loadFromString("""
                pets:
                  wolf: {species: dog, voice: rabbit, egg: WOLF_SPAWN_EGG}
                """);
        runtime.config(CompanionConfig.load(plugin, yaml));
        command("inspect", "wolf");
        var messages = new ArrayList<String>();
        String message;
        while ((message = staff.nextMessage()) != null) messages.add(message);
        String report = String.join("\n", messages);
        assertTrue(report.contains("Voice: rabbit"));
        assertTrue(report.contains("Profile: generated"));
        assertTrue(report.contains("minecraft:entity.rabbit.ambient"));
    }


}
