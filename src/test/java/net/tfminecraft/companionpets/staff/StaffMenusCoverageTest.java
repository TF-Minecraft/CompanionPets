package net.tfminecraft.companionpets.staff;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.gui.MenuNavigation;
import net.tfminecraft.companionpets.gui.PetMenus;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetSex;
import net.tfminecraft.companionpets.pet.Trick;
import net.tfminecraft.companionpets.runtime.PetActions;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.visual.IdleVisual;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class StaffMenusCoverageTest {
    private ServerMock server;
    private PlayerMock staff;
    private PlayerMock owner;
    private PetRuntime runtime;
    private PetActions actions;
    private StaffMenus menus;
    private final List<String> trickNames = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        staff = server.addPlayer("Staff");
        staff.setOp(true);
        owner = server.addPlayer("Owner");
        var plugin = MockBukkit.createMockPlugin();
        var yaml = new YamlConfiguration();
        yaml.loadFromString("pets: {wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG, default-tricks: []}}");
        var custom = new ArrayList<String>();
        for (int i = 0; i < 24; i++) {
            String id = "trick" + i;
            String name = "Custom Trick " + String.format("%02d", i);
            custom.add(id);
            trickNames.add(name);
            yaml.set("custom-tricks." + id + ".display-name", name);
            yaml.set("custom-tricks." + id + ".fallback-text", "A custom trick");
        }
        yaml.set("pets.wolf.tricks", custom);
        var store = new PetStore(plugin);
        assertTrue(store.load());
        var visual = new IdleVisual();
        var key = new NamespacedKey(plugin, "pet");
        runtime = new PetRuntime(plugin, CompanionConfig.load(plugin, yaml), store, new Sessions(),
                new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        actions = new PetActions(runtime);
        menus = new StaffCommands(runtime, actions).menus();
        server.getPluginManager().registerEvents(menus, plugin);
    }

    @AfterEach void cleanup() {
        if (actions != null) actions.holograms().clear();
        if (runtime != null) runtime.store().close();
        MockBukkit.unmock();
    }

    private Pet pet(String name) {
        var pet = new Pet(UUID.randomUUID(), owner.getUniqueId(), "wolf", name, PetSex.FEMALE);
        pet.stored(true);
        runtime.store().add(pet);
        return pet;
    }

    private StaffMenuHolder holder() {
        return assertInstanceOf(StaffMenuHolder.class, staff.getOpenInventory().getTopInventory().getHolder());
    }

    private void click(int slot, ClickType button) {
        var event = new InventoryClickEvent(staff.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                slot, button, button == ClickType.RIGHT ? InventoryAction.PICKUP_HALF : InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled(), "staff profiles and trick icons are read-only");
    }

    private static String title(ItemStack item) {
        return PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
    }

    private List<String> pageNames() {
        var result = new ArrayList<String>();
        for (int slot = 0; slot < PetMenus.TRICKS_PER_PAGE; slot++) {
            var item = holder().getInventory().getItem(slot);
            if (item.getType() != Material.LIGHT_GRAY_STAINED_GLASS_PANE) result.add(title(item));
        }
        return result;
    }

    @Test void trickPagesShowEveryConfiguredTrickOnceAndReturnToTheSelectedProfile() {
        Pet pet = pet("Buddy");
        pet.progress(Trick.valueOf("trick20"), 100);
        pet.bindWord("wave", Trick.valueOf("trick20"));
        menus.inspect(staff, pet);
        click(PetMenus.TRICKS_SLOT, ClickType.LEFT);
        assertEquals(StaffMenuHolder.Kind.TRICKS, holder().kind());
        assertEquals(pet.id(), holder().subject());
        assertEquals(0, holder().page());
        assertEquals(18, pageNames().size());
        assertEquals("Custom Trick 20", pageNames().getFirst(), "learned tricks come first");
        assertTrue(holder().getInventory().getItem(0).getItemMeta().lore().stream()
                .map(line -> PlainTextComponentSerializer.plainText().serialize(line)).anyMatch(line -> line.contains("wave")));
        Set<String> shown = new HashSet<>(pageNames());
        var firstPage = holder();
        int sounds = staff.getHeardSounds().size();
        click(PetMenus.TRICKS_NEXT_SLOT, ClickType.RIGHT);
        assertSame(firstPage, holder());
        assertEquals(sounds + 1, staff.getHeardSounds().size());

        click(PetMenus.TRICKS_NEXT_SLOT, ClickType.LEFT);
        assertEquals(1, holder().page());
        assertEquals(pet.id(), holder().subject());
        assertEquals(6, pageNames().size());
        for (String name : pageNames()) assertTrue(shown.add(name), "trick duplicated between pages: " + name);
        assertEquals(Set.copyOf(trickNames), shown);
        assertTrue(title(holder().getInventory().getItem(PetMenus.TRICKS_NEXT_SLOT)).contains("2/2"));
        var lastPage = holder();
        sounds = staff.getHeardSounds().size();
        click(PetMenus.TRICKS_NEXT_SLOT, ClickType.LEFT);
        assertSame(lastPage, holder());
        assertEquals(sounds + 1, staff.getHeardSounds().size());
        click(0, ClickType.LEFT);
        assertSame(lastPage, holder(), "read-only trick entries must not train or select a different pet");
        assertEquals(100, pet.progress(Trick.valueOf("trick20")));
        assertEquals(0, pet.progress(Trick.valueOf("trick23")));
        assertTrue(staff.getItemOnCursor().getType().isAir());

        click(PetMenus.TRICKS_NEXT_SLOT, ClickType.RIGHT);
        assertEquals(0, holder().page());
        assertEquals(firstPage.getInventory().getItem(0), holder().getInventory().getItem(0));
        click(PetMenus.TRICKS_BACK_SLOT, ClickType.LEFT);
        assertEquals(StaffMenuHolder.Kind.INSPECT, holder().kind());
        assertEquals(pet.id(), holder().subject());
        click(PetMenus.BACK_SLOT, ClickType.LEFT);
        assertEquals(StaffMenuHolder.Kind.LIST, holder().kind());
        assertEquals(owner.getUniqueId(), holder().subject());
    }

    @Test void ownerListPaginationSelectsTheActualPetOnTheSecondPage() {
        var pets = new ArrayList<Pet>();
        for (int i = 0; i < 47; i++) pets.add(pet("Pet " + String.format("%02d", i)));
        var outsider = new Pet(UUID.randomUUID(), UUID.randomUUID(), "wolf", "Pet 00", PetSex.MALE);
        outsider.stored(true);
        runtime.store().add(outsider);
        menus.list(staff, owner.getUniqueId(), 0);
        click(MenuNavigation.nextSlot(holder().getInventory()), ClickType.LEFT);
        assertEquals(1, holder().page());
        assertEquals("Pet 45", title(holder().getInventory().getItem(0)));
        assertEquals("Pet 46", title(holder().getInventory().getItem(1)));
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, holder().getInventory().getItem(2).getType());
        click(1, ClickType.LEFT);
        assertEquals(pets.get(46).id(), holder().subject());
        assertEquals(StaffMenuHolder.Kind.INSPECT, holder().kind());
        click(PetMenus.TRICKS_SLOT, ClickType.LEFT);
        click(PetMenus.TRICKS_NEXT_SLOT, ClickType.LEFT);
        assertEquals(pets.get(46).id(), holder().subject());
        click(PetMenus.TRICKS_BACK_SLOT, ClickType.LEFT);
        assertEquals(pets.get(46).id(), holder().subject());
        click(PetMenus.BACK_SLOT, ClickType.LEFT);
        assertEquals(owner.getUniqueId(), holder().subject());
        assertEquals(0, holder().page());
    }

    @Test void removedPetClosesTheOpenTrickMenuWhenStaffNavigates() {
        Pet pet = pet("Buddy");
        menus.tricks(staff, pet.id(), 0);
        assertTrue(runtime.store().remove(pet.id()));
        click(PetMenus.TRICKS_NEXT_SLOT, ClickType.LEFT);
        var top = staff.getOpenInventory().getTopInventory();
        assertTrue(top == null || !(top.getHolder() instanceof StaffMenuHolder));
        assertNull(runtime.store().get(pet.id()));
        assertTrue(runtime.store().isDeleted(pet.id()));
    }

    @Test void trickPageRequestsClampToValidPagesAndRetainPetIdentity() {
        Pet pet = pet("Buddy");
        menus.tricks(staff, pet.id(), -20);
        assertEquals(0, holder().page());
        assertEquals(pet.id(), holder().subject());
        menus.tricks(staff, pet.id(), Integer.MAX_VALUE);
        assertEquals(1, holder().page());
        assertEquals(pet.id(), holder().subject());
        assertEquals(6, pageNames().size());
        click(PetMenus.TRICKS_NEXT_SLOT, ClickType.RIGHT);
        assertEquals(0, holder().page());
    }
}
