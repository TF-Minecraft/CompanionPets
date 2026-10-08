package net.tfminecraft.companionpets.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import net.tfminecraft.companionpets.item.ItemRef;

class PetItemsTest {
    private final Logger logger = Logger.getLogger("PetItemsTest");
    @BeforeAll static void mock() { MockBukkit.mock(); }
    @AfterAll static void unmock() { MockBukkit.unmock(); }
    private YamlConfiguration yaml(String value) throws Exception {
        var yaml = new YamlConfiguration(); yaml.loadFromString(value); return yaml;
    }
    private PetItems global() throws Exception {
        return PetItems.read(yaml("""
                treats: [COD, SALMON]
                foods: [{item: BEEF, hunger: 35}]
                medicines: [HONEY_BOTTLE, MILK_BUCKET]
                brushes: [BRUSH, FEATHER]
                toys: [STICK]
                """), PetItems.defaults(), logger);
    }

    @Test void omittedPetListsInheritEveryGlobalCategory() throws Exception {
        PetItems global = global();
        assertEquals(global, PetItems.forPet(yaml("entity: WOLF"), global, logger));
        assertEquals(global, PetItems.forPet(yaml("items: {}"), global, logger));
    }
    @Test void overrideReplacesOnlyItsCategoryWithoutAppending() throws Exception {
        PetItems global = global();
        var local = PetItems.forPet(yaml("items: {treats: [CARROT]}"), global, logger);
        assertEquals(List.of(ItemRef.parse("CARROT")), local.treats());
        assertFalse(local.isTreat(new ItemStack(Material.SALMON)));
        assertEquals(global.foods(), local.foods());
        assertEquals(global.medicines(), local.medicines());
        assertEquals(global.brushes(), local.brushes());
        assertEquals(global.toys(), local.toys());
    }
    @Test void emptyListDisablesRatherThanInheritingEachCategory() throws Exception {
        PetItems global = global();
        var local = PetItems.forPet(yaml("items: {foods: [], treats: [], medicines: [], brushes: [], toys: []}"), global, logger);
        assertEquals(new PetItems(Map.of(), List.of(), List.of(), List.of(), List.of()), local);
        assertNull(local.foodGain(new ItemStack(Material.BEEF)));
        assertFalse(local.isTreat(new ItemStack(Material.COD)));
        assertFalse(local.isMedicine(new ItemStack(Material.HONEY_BOTTLE)));
        assertFalse(local.isBrush(new ItemStack(Material.BRUSH)));
        assertNull(local.toy(new ItemStack(Material.STICK)));
    }
    @Test void everyTreatAndMedicineIsAcceptedAndTreatsCanBeSwapped() throws Exception {
        PetItems items = global();
        assertTrue(items.isTreat(new ItemStack(Material.COD)));
        assertTrue(items.isTreat(new ItemStack(Material.SALMON)));
        assertFalse(items.isTreat(new ItemStack(Material.BEEF)));
        assertTrue(items.isMedicine(new ItemStack(Material.HONEY_BOTTLE)));
        assertTrue(items.isMedicine(new ItemStack(Material.MILK_BUCKET)));
        assertFalse(items.isMedicine(new ItemStack(Material.COD)));
        assertTrue(items.isBrush(new ItemStack(Material.FEATHER)));
        assertEquals(30.0, items.foodGain(new ItemStack(Material.COD)));
        assertEquals(35.0, items.foodGain(new ItemStack(Material.BEEF)));
    }
    @Test void foodsOverrideDropsGlobalFoodAndItsGain() throws Exception {
        var items = PetItems.forPet(yaml("items: {foods: [{item: CARROT, hunger: 50}]}"), global(), logger);
        assertEquals(Map.of(ItemRef.parse("CARROT"), 50.0), items.foods());
        assertNull(items.foodGain(new ItemStack(Material.BEEF)));
    }
    @Test void malformedOrInvalidOverridesDoNotFallBackToGlobals() throws Exception {
        var items = PetItems.forPet(yaml("items: {treats: CARROT, medicines: [not_an_item]}"), global(), logger);
        assertTrue(items.treats().isEmpty());
        assertTrue(items.medicines().isEmpty());
        assertEquals(global().brushes(), items.brushes());
    }
    @Test void newListsTakePriorityOverLegacyFields() throws Exception {
        var pet = yaml("""
                care:
                  foods: {BEEF: 99}
                  favorite: COD
                  medicine: HONEY_BOTTLE
                toys: [FEATHER]
                items:
                  foods: []
                  treats: [SALMON]
                  medicines: [MILK_BUCKET]
                  toys: [STICK]
                """);
        var items = PetItems.forPet(pet, global(), logger);
        assertTrue(items.foods().isEmpty());
        assertEquals(List.of(ItemRef.parse("SALMON")), items.treats());
        assertEquals(List.of(ItemRef.parse("MILK_BUCKET")), items.medicines());
        assertEquals(List.of(ItemRef.parse("STICK")), items.toys());
    }
    @Test void duplicateAliasesAndYamlColonMapsAreHandledLikeArchaeo() throws Exception {
        var section = yaml("""
                treats:
                  - "mmoitems:PETS:MEAT_TREAT"
                  - "mi:pets:meat_treat"
                  - mmoitems:PETS: FISH_TREAT
                """);
        assertEquals(List.of(ItemRef.parse("mmoitems:PETS:MEAT_TREAT"), ItemRef.parse("mmoitems:PETS:FISH_TREAT")),
                PetItems.read(section, global(), logger).treats());
    }
    @Test void malformedListElementsAreDiscardedWithoutLosingValidItems() throws Exception {
        var items = PetItems.read(yaml("treats: [null, 42, '', COD, COD]"), PetItems.defaults(), logger);
        assertEquals(List.of(ItemRef.parse("COD")), items.treats());
        assertTrue(items.isTreat(new ItemStack(Material.COD)));
    }

    @Test void oneHeldItemCanBeReusedAcrossAllListsAndTreatFoodFallback() throws Exception {
        var items = global();
        var cod = net.tfminecraft.companionpets.item.HeldItem.of(new ItemStack(Material.COD));
        assertTrue(items.isTreat(cod));
        assertEquals(30.0, items.foodGain(cod));
        assertFalse(items.isBrush(cod)); assertFalse(items.isMedicine(cod)); assertNull(items.toy(cod));
        assertEquals(ItemRef.parse("STICK"), items.toy(net.tfminecraft.companionpets.item.HeldItem.of(new ItemStack(Material.STICK))));
    }
}
