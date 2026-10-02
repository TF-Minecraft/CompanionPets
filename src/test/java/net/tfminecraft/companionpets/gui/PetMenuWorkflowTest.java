package net.tfminecraft.companionpets.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.listen.PetListener;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.runtime.*;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.visual.IdleVisual;

class PetMenuWorkflowTest {
    private PlayerMock player;
    private PetRuntime runtime;
    private PetMenus menus;
    private PetListener listener;
    private PetActions actions;

    @BeforeEach void setup() throws Exception {
        var server = MockBukkit.mock(); server.addSimpleWorld("world");
        var plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
                items:
                  treats: [COD, SALMON]
                  foods: [{item: BEEF, hunger: 35}]
                  medicines: [MILK_BUCKET, HONEY_BOTTLE]
                  brushes: [FEATHER]
                  toys: [STICK]
                pets:
                  wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG}
                  none: {entity: CAT, egg: CAT_SPAWN_EGG, tricks: [], default-tricks: []}
                """);
        for (int i = 0; i < 92; i++) yaml.set("custom-tricks.custom" + i + ".fallback-text", "A custom trick");
        var config = CompanionConfig.load(plugin, yaml);
        var visual = new IdleVisual(); var key = new NamespacedKey(plugin, "pet");
        runtime = new PetRuntime(plugin, config, new PetStore(plugin), new Sessions(),
                new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        actions = new PetActions(runtime); menus = actions.menus(); listener = new PetListener(runtime, actions);
    }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    private Pet pet(String type, UUID owner) {
        Pet pet = new Pet(UUID.randomUUID(), owner, type, "Pet", PetSex.FEMALE);
        pet.stored(true); runtime.store().add(pet); return pet;
    }
    private MenuHolder holder() { return (MenuHolder) player.getOpenInventory().getTopInventory().getHolder(); }

    @Test void careShowsConfiguredItemsAndReflectsIllnessWithoutReopening() {
        Pet pet = pet("wolf", player.getUniqueId());
        menus.openCare(player, pet);
        var inventory = holder().getInventory();
        assertEquals(Material.BEEF, inventory.getItem(20).getType());
        assertEquals(Material.FEATHER, inventory.getItem(23).getType());
        assertEquals(Material.GOLDEN_APPLE, inventory.getItem(24).getType());
        pet.illness(Illness.SICK); pet.need(Need.HEALTH, 20);
        menus.refreshCare(player, pet);
        assertSame(inventory, holder().getInventory());
        assertEquals(Material.MILK_BUCKET, inventory.getItem(24).getType());
        assertNotEquals(Material.BARREL, inventory.getItem(PetMenus.STORE_SLOT).getType(),
                "Stored pet has a placeholder instead of the store action");
    }

    @Test void shelterIncludesOnlyPlayersPetsAndCarriesStablePetIds() {
        Pet owned = pet("wolf", player.getUniqueId()); pet("wolf", UUID.randomUUID());
        menus.openKennel(player);
        var inventory = holder().getInventory();
        Set<String> ids = new java.util.HashSet<>();
        for (ItemStack item : inventory.getContents()) if (item != null) {
            String id = item.getItemMeta().getPersistentDataContainer().get(runtime.petKey(), PersistentDataType.STRING);
            if (id != null) ids.add(id);
        }
        assertEquals(Set.of(owned.id().toString()), ids);
    }

    @Test void shelterPaginationIncludesEveryPetAndClampsInvalidPages() {
        Set<String> expected = new java.util.HashSet<>();
        for (int i = 0; i < 100; i++) expected.add(pet("wolf", player.getUniqueId()).id().toString());
        Set<String> shown = new java.util.HashSet<>();
        for (int page = 0; page < 3; page++) {
            menus.openKennel(player, page);
            assertEquals(page, holder().page());
            for (int slot = 0; slot < 45; slot++) {
                var item = holder().getInventory().getItem(slot);
                if (item != null) {
                    var id = item.getItemMeta().getPersistentDataContainer().get(runtime.petKey(), PersistentDataType.STRING);
                    if (id != null) assertTrue(shown.add(id), "No duplicated pet between pages");
                }
            }
        }
        assertEquals(expected, shown);
        menus.openKennel(player, 999); assertEquals(2, holder().page());
        menus.openKennel(player, -1); assertEquals(0, holder().page());
    }

    @Test void trickPaginationRetainsEveryAvailableTrickAndClampsInvalidPages() {
        Pet pet = pet("wolf", player.getUniqueId());
        for (Trick trick : runtime.config().tricks()) { pet.progress(trick, 100); pet.bindWord(trick.name(), trick); }
        Set<Trick> shown = new java.util.HashSet<>();
        int pages = (runtime.config().tricks().size() + PetMenus.TRICKS_PER_PAGE - 1) / PetMenus.TRICKS_PER_PAGE;
        for (int page = 0; page < pages; page++) {
            menus.openLearned(player, pet, page);
            assertEquals(page, holder().page());
            assertEquals(27, holder().getInventory().getSize());
            assertNotNull(holder().trick(0));
            for (int slot = 0; slot < PetMenus.TRICKS_PER_PAGE; slot++) if (holder().trick(slot) != null) assertTrue(shown.add(holder().trick(slot)), "No repeated trick between pages");
            for (int slot = PetMenus.TRICKS_PER_PAGE; slot < 27; slot++) assertNull(holder().trick(slot), "Navigation never contains selectable tricks");
        }
        assertEquals(Set.copyOf(runtime.config().tricks()), shown);
        menus.openLearned(player, pet, 999); assertEquals(pages - 1, holder().page());
        menus.openTricks(player, pet, "saludar", -1); assertEquals(0, holder().page());
        assertEquals("saludar", holder().word());
    }

    @Test void disabledTricksDisplayAnEmptyMenuWithoutSelectableTricks() {
        menus.openTricks(player, pet("none", player.getUniqueId()), "sit", 10);
        assertEquals(0, holder().page());
        assertEquals(Material.PAPER, holder().getInventory().getItem(0).getType());
        for (int slot = 0; slot < PetMenus.TRICKS_PER_PAGE; slot++) assertNull(holder().trick(slot));
    }

    @Test void trainingNavigationPreservesThePendingWordAndSelectsTheActualSecondPageTrick() {
        Pet pet = pet("wolf", player.getUniqueId());
        var session = new net.tfminecraft.companionpets.session.TrainingSession(pet.id());
        session.pendingWord("saludar"); runtime.sessions().training(player.getUniqueId(), session);
        menus.openTricks(player, pet, "saludar");
        var previousView = player.getOpenInventory();
        var first = holder();
        assertEquals(Trick.FOLLOW, first.trick(0));
        actions.clickMenu(player, first, PetMenus.TRICKS_NEXT_SLOT, null, false, false, false);
        listener.onClose(new InventoryCloseEvent(previousView));
        assertEquals("saludar", session.pendingWord(), "Page changes do not cancel the word being taught");
        assertEquals(1, holder().page()); assertEquals("saludar", holder().word());
        Trick selected = holder().trick(0);
        assertNotEquals(first.trick(0), selected);
        actions.clickMenu(player, holder(), 0, null, false, false, false);
        assertEquals(selected, pet.trickFor("saludar")); assertNull(session.pendingWord());
    }

    @Test void learnedNavigationAndBackUseTheFooterAndClosingTrainingCancelsItsWord() {
        Pet pet = pet("wolf", player.getUniqueId()); menus.openLearned(player, pet);
        actions.clickMenu(player, holder(), PetMenus.TRICKS_NEXT_SLOT, null, false, false, false);
        assertEquals(1, holder().page());
        actions.clickMenu(player, holder(), PetMenus.TRICKS_NEXT_SLOT, null, true, false, false);
        assertEquals(0, holder().page());
        actions.clickMenu(player, holder(), PetMenus.TRICKS_BACK_SLOT, null, false, false, false);
        assertEquals(MenuHolder.Kind.CARE, holder().kind());
        var session = new net.tfminecraft.companionpets.session.TrainingSession(pet.id());
        session.pendingWord("saludar"); runtime.sessions().training(player.getUniqueId(), session);
        menus.openTricks(player, pet, "saludar"); var view = player.getOpenInventory();
        listener.onClose(new InventoryCloseEvent(view)); assertNull(session.pendingWord());
    }

    @Test void allPlayerMenusHaveOneLightBackgroundAndCareUsesSexDyesWithoutFollowButton() {
        Pet pet = pet("wolf", player.getUniqueId());
        menus.openCare(player, pet); assertUniformBackground();
        assertEquals(Material.WHITE_DYE, holder().getInventory().getItem(11).getType());
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, holder().getInventory().getItem(39).getType());
        pet.sex(PetSex.MALE); menus.refreshCare(player, pet); assertUniformBackground();
        assertEquals(Material.WHITE_DYE, holder().getInventory().getItem(11).getType());
        menus.openKennel(player); assertUniformBackground();
        menus.openLearned(player, pet); assertUniformBackground();
        menus.openTricks(player, pet, "saludar"); assertUniformBackground();
    }

    private void assertUniformBackground() {
        for (ItemStack item : holder().getInventory().getContents()) {
            assertNotNull(item, "Every empty slot has a background");
            if (item.getType().name().endsWith("STAINED_GLASS_PANE")) assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, item.getType());
        }
    }

    @Test void trickOrderGroupsLearnedThenPracticingThenUnknownAndPreservesConfiguredCustomOrder() {
        Pet pet = pet("wolf", player.getUniqueId());
        pet.progress(Trick.FOLLOW, 100); pet.progress(Trick.SIT, 100);
        pet.progress(Trick.valueOf("custom1"), 100); pet.progress(Trick.JUMP, 20);
        menus.openLearned(player, pet);
        assertEquals(Trick.FOLLOW, holder().trick(0)); assertEquals(Trick.SIT, holder().trick(1));
        assertEquals(Trick.valueOf("custom1"), holder().trick(2)); assertEquals(Trick.JUMP, holder().trick(3));
        assertEquals(Trick.COME, holder().trick(4)); assertEquals(Trick.STAY, holder().trick(5)); assertEquals(Trick.LAY, holder().trick(6));
        int custom0 = -1, custom2 = -1;
        for (int i = 0; i < PetMenus.TRICKS_PER_PAGE; i++) {
            if (Trick.valueOf("custom0").equals(holder().trick(i))) custom0 = i;
            if (Trick.valueOf("custom2").equals(holder().trick(i))) custom2 = i;
        }
        assertTrue(custom0 >= 0 && custom2 > custom0);
        var learned = holder(); menus.openTricks(player, pet, "saludar");
        for (int i = 0; i < PetMenus.TRICKS_PER_PAGE; i++) assertEquals(learned.trick(i), holder().trick(i));
    }

    @Test void footersUseFramesForBackAndKeepTheCounterOnTheNextArrow() {
        Pet pet = pet("wolf", player.getUniqueId());
        menus.openCare(player, pet, true);
        var back = holder().getInventory().getItem(36);
        assertEquals(Material.ITEM_FRAME, back.getType());
        assertEquals(Material.BOOK, holder().getInventory().getItem(40).getType());
        for (Runnable open : java.util.List.<Runnable>of(
                () -> menus.openLearned(player, pet),
                () -> menus.openTricks(player, pet, "saludar"))) {
            open.run(); var inventory = holder().getInventory(); int corner = inventory.getSize() - 9;
            var item = inventory.getItem(corner);
            assertEquals(back.getType(), item.getType()); assertEquals(back.getItemMeta().displayName(), item.getItemMeta().displayName());
            assertEquals(Material.ARROW, inventory.getItem(inventory.getSize() - 1).getType());
            for (int slot = corner + 1; slot < inventory.getSize() - 1; slot++)
                assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, inventory.getItem(slot).getType());
        }
        menus.openLearned(player, pet, 1);
        assertEquals(Material.ITEM_FRAME, holder().getInventory().getItem(18).getType());
        assertEquals(Material.ARROW, holder().getInventory().getItem(26).getType());
        menus.openKennel(player);
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, holder().getInventory().getItem(45).getType());
        assertEquals(Material.ARROW, holder().getInventory().getItem(53).getType());
        assertTrue(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(holder().getInventory().getItem(53).getItemMeta().displayName()).contains("1/1"));
    }

    @Test void nextOnTheLastPageOnlyPlaysDenialAndKeepsTheTrainingWordAndView() {
        Pet pet = pet("wolf", player.getUniqueId());
        var session = new net.tfminecraft.companionpets.session.TrainingSession(pet.id());
        session.pendingWord("saludar"); runtime.sessions().training(player.getUniqueId(), session);
        menus.openTricks(player, pet, "saludar", 999); var last = holder();
        int heard = player.getHeardSounds().size();
        actions.clickMenu(player, last, PetMenus.TRICKS_NEXT_SLOT, null, false, false, false);
        assertSame(last, holder()); assertEquals("saludar", session.pendingWord()); assertFalse(last.navigating());
        assertEquals(heard + 1, player.getHeardSounds().size());
        assertTrue(player.getHeardSounds().getLast().getSound().contains("villager.no"));
        menus.openTricks(player, pet, "saludar", 0); var first = holder();
        heard = player.getHeardSounds().size();
        actions.clickMenu(player, first, PetMenus.TRICKS_NEXT_SLOT, null, true, false, false);
        assertSame(first, holder()); assertEquals(0, holder().page()); assertEquals("saludar", session.pendingWord());
        assertEquals(heard + 1, player.getHeardSounds().size());
    }

    @Test void shelterFillsRowsInNameOrderAndReservesTheEntireFooter() {
        for (String name : java.util.List.of("Zulu", "Bravo", "Mike", "Alpha", "Golf", "Delta", "Charlie", "Echo", "Hotel", "India", "Foxtrot")) {
            var pet = pet("wolf", player.getUniqueId()); pet.name(name);
        }
        menus.openKennel(player);
        var inventory = holder().getInventory();
        var expected = java.util.List.of("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel", "India", "Mike", "Zulu");
        for (int slot = 0; slot < expected.size(); slot++)
            assertEquals(expected.get(slot), net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(inventory.getItem(slot).getItemMeta().displayName()));
        for (int slot = 45; slot < 54; slot++) assertFalse(inventory.getItem(slot).getItemMeta()
                .getPersistentDataContainer().has(runtime.petKey(), PersistentDataType.STRING));
    }

    @Test void backAlwaysReturnsToTheParentMenuAndDirectProfilesHaveNoInventedParent() {
        Pet pet = pet("wolf", player.getUniqueId()); menus.openCare(player, pet);
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, holder().getInventory().getItem(36).getType());
        menus.openKennel(player);
        actions.clickMenu(player, holder(), 0, holder().getInventory().getItem(0), false, false, false);
        assertEquals(MenuHolder.Kind.CARE, holder().kind()); assertTrue(holder().shelterBack());
        actions.clickMenu(player, holder(), PetMenus.TRICKS_SLOT, null, false, false, false);
        actions.clickMenu(player, holder(), PetMenus.TRICKS_NEXT_SLOT, null, false, false, false);
        assertEquals(1, holder().page());
        actions.clickMenu(player, holder(), PetMenus.TRICKS_BACK_SLOT, null, false, false, false);
        assertEquals(MenuHolder.Kind.CARE, holder().kind()); assertTrue(holder().shelterBack());
        actions.clickMenu(player, holder(), PetMenus.BACK_SLOT, null, false, false, false);
        assertEquals(MenuHolder.Kind.KENNEL, holder().kind());
    }

    @Test void shelterPageSurvivesProfileAndBothTrickMenuRoundTrips() {
        for (int i = 0; i < 50; i++) pet("wolf", player.getUniqueId());
        menus.openKennel(player, 1);
        actions.clickMenu(player, holder(), 0, holder().getInventory().getItem(0), false, false, false);
        var selected = runtime.store().get(holder().petId());
        assertEquals(1, holder().shelterPage());
        menus.refreshCare(player, selected);
        assertEquals(1, holder().shelterPage());
        for (boolean training : java.util.List.of(false, true)) {
            if (training) menus.openTricks(player, selected, "saludar");
            else actions.clickMenu(player, holder(), PetMenus.TRICKS_SLOT, null, false, false, false);
            actions.clickMenu(player, holder(), PetMenus.TRICKS_NEXT_SLOT, null, false, false, false);
            assertEquals(1, holder().page());
            assertEquals(1, holder().shelterPage());
            actions.clickMenu(player, holder(), PetMenus.TRICKS_BACK_SLOT, null, false, false, false);
            assertEquals(MenuHolder.Kind.CARE, holder().kind());
            assertEquals(1, holder().shelterPage());
        }
        actions.clickMenu(player, holder(), PetMenus.BACK_SLOT, null, false, false, false);
        assertEquals(MenuHolder.Kind.KENNEL, holder().kind());
        assertEquals(1, holder().page());
    }

    @Test void menusCancelItemTransfersAndRejectManagementOfSomeoneElsesPet() {
        Pet pet = pet("wolf", UUID.randomUUID()); menus.openCare(player, pet);
        var view = player.getOpenInventory();
        var click = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, PetMenus.CALL_SLOT,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onClick(click);
        assertTrue(click.isCancelled()); assertTrue(pet.stored()); assertNull(pet.entityId());
        var drag = new InventoryDragEvent(view, new ItemStack(Material.STICK), new ItemStack(Material.STICK),
                false, Map.of(20, new ItemStack(Material.STICK)));
        listener.onDrag(drag); assertTrue(drag.isCancelled());
        var bottom = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 46,
                ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        listener.onClick(bottom); assertTrue(bottom.isCancelled());
    }
}
