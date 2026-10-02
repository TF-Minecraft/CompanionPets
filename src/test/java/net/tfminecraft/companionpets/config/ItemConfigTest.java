package net.tfminecraft.companionpets.config;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.mockbukkit.mockbukkit.MockBukkit;
import net.tfminecraft.companionpets.item.ItemRef;

class ItemConfigTest {
    @BeforeAll static void mock() { MockBukkit.mock(); }
    @AfterAll static void unmock() { MockBukkit.unmock(); }
    private final Logger logger = Logger.getLogger("ItemConfigTest");

    @Test void legacyConfigWithBundledDefaultsRetainsItemInheritanceAndBrush() throws Exception {
        var bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        var legacy = new YamlConfiguration();
        legacy.loadFromString("""
                items: {kennel: BARREL, brush: FEATHER}
                pets:
                  wolf:
                    entity: WOLF
                    egg: WOLF_SPAWN_EGG
                    care: {foods: {BEEF: 35}, favorite: SALMON}
                    toys: [FEATHER]
                """);
        legacy.setDefaults(bundled);
        var config = CompanionConfig.load(MockBukkit.createMockPlugin(), legacy);
        var defaults = PetItems.defaults();
        assertEquals(defaults.foods(), config.items().foods());
        assertEquals(defaults.treats(), config.items().treats());
        assertEquals(defaults.medicines(), config.items().medicines());
        assertEquals(defaults.toys(), config.items().toys());
        assertEquals(java.util.List.of(ItemRef.parse("FEATHER")), config.items().brushes());
        assertEquals(Map.of(ItemRef.parse("BEEF"), 35.0), config.type("wolf").foods());
        assertEquals(java.util.List.of(ItemRef.parse("SALMON")), config.type("wolf").treats());
        assertEquals(java.util.List.of(ItemRef.parse("FEATHER")), config.type("wolf").toys());
        assertTrue(CompanionConfig.readFoods(legacy.getConfigurationSection("items"), logger).isEmpty());
    }

    @Test void absentItemsSectionWithBundledDefaultsUsesRuntimeDefaults() throws Exception {
        var bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        var legacy = new YamlConfiguration();
        legacy.loadFromString("pets: {wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG}}");
        legacy.setDefaults(bundled);
        var config = CompanionConfig.load(MockBukkit.createMockPlugin(), legacy);
        assertEquals(PetItems.defaults(), config.items());
        assertEquals(PetItems.defaults(), config.type("wolf").items());
    }

    @Test void foodListAcceptsEveryProviderAndPreservesDottedIds() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
                care:
                  foods:
                    - {item: v.beef, hunger: 35}
                    - {item: m.pets.universal_feed, hunger: 45}
                    - {item: 'ia.tfmc:pet_feed', hunger: 55}
                """);
        var foods = CompanionConfig.readFoods(yaml.getConfigurationSection("care"), logger);
        assertEquals(Map.of(ItemRef.parse("v.beef"), 35.0, ItemRef.parse("m.pets.universal_feed"), 45.0,
                ItemRef.parse("ia.tfmc:pet_feed"), 55.0), foods);
    }

    @Test void oldMaterialMapStillLoads() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("care: {foods: {BEEF: 35, COOKED_BEEF: 55}}");
        assertEquals(Map.of(ItemRef.parse("v.beef"), 35.0, ItemRef.parse("v.cooked_beef"), 55.0),
                CompanionConfig.readFoods(yaml.getConfigurationSection("care"), logger));
    }

    @Test void invalidFoodsDoNotBecomeAnotherItemOrGain() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
                care:
                  foods:
                    - {item: m.pets, hunger: 35}
                    - {item: v.beef, hunger: -1}
                    - {item: v.carrot, hunger: .nan}
                    - {item: v.potato, hunger: plenty}
                    - {item: 'ia.tfmc.pet_feed', hunger: 35}
                    - {item: v.salmon, hunger: 40}
                """);
        assertEquals(Map.of(ItemRef.parse("v.salmon"), 40.0),
                CompanionConfig.readFoods(yaml.getConfigurationSection("care"), logger));
    }

    @Test void shippedConfigContainsValidSelectorsAndFoodLists() {
        var yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        var config = CompanionConfig.load(MockBukkit.createMockPlugin(), yaml);
        assertEquals(2, config.items().treats().size());
        assertEquals(2, config.items().medicines().size());
        assertEquals(java.util.List.of(ItemRef.parse("mmoitems:PETS:CARING_ITEM")), config.items().brushes());
        for (String pet : yaml.getConfigurationSection("pets").getKeys(false)) {
            var type = yaml.getConfigurationSection("pets." + pet);
            assertNotNull(CompanionConfig.parseItem(type.getString("egg"), logger));
            assertFalse(type.contains("items"));
            assertFalse(type.contains("care"));
            assertFalse(type.contains("toys"));
            assertEquals(config.items(), config.type(pet).items());
        }
        assertEquals(4, MomentSettings.load(yaml.getConfigurationSection("moments"), logger).digLoot().size());
    }

    @Test void lootSupportsCustomItemsAndEmptyListDisablesGifts() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
                moments:
                  dig-loot:
                    - {item: m.pets.meat_treat, weight: 10}
                    - {item: 'ia.tfmc:gift', weight: 5}
                    - {item: v.stick, weight: 0.5}
                """);
        assertEquals(Map.of(ItemRef.parse("m.pets.meat_treat"), 10, ItemRef.parse("ia.tfmc:gift"), 5),
                MomentSettings.load(yaml.getConfigurationSection("moments"), logger).digLoot());
        yaml.set("moments.dig-loot", java.util.List.of());
        assertTrue(MomentSettings.load(yaml.getConfigurationSection("moments"), logger).digLoot().isEmpty());
        yaml.loadFromString("moments: {dig-loot: {STICK: 45}}");
        assertEquals(Map.of(ItemRef.parse("v.stick"), 45), MomentSettings.load(yaml.getConfigurationSection("moments"), logger).digLoot());
    }

    @Test void fullConfigUsesCustomEggsCareAndLegacyEggModelWithoutCollisions() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
                items:
                  brush: m.pets.caring_item
                  kennel: 'ia.tfmc:shelter_token'
                  kennel-block: BARREL
                pets:
                  wolf:
                    entity: WOLF
                    egg: WOLF_SPAWN_EGG
                  beagle:
                    entity: WOLF
                    egg:
                      mmoitems:PETS: PET_BEAGLE_EGG
                    care:
                      favorite: m.pets.meat_treat
                      medicine: 'ia.tfmc:pet_medicine'
                    toys: ['ia.tfmc:pet_ball']
                  legacy:
                    entity: WOLF
                    egg: WOLF_SPAWN_EGG
                    egg-custom-model-data: 12002
                  duplicate:
                    entity: WOLF
                    egg: M.PETS.PET_BEAGLE_EGG
                  invalid:
                    entity: WOLF
                    egg: m.pets.pet_other_egg
                    egg-custom-model-data: 12003
                """);
        var config = CompanionConfig.load(MockBukkit.createMockPlugin(), yaml);
        assertEquals(3, config.types().size());
        assertEquals(java.util.List.of(ItemRef.parse("m.pets.caring_item")), config.items().brushes());
        assertEquals(ItemRef.parse("ia.tfmc:shelter_token"), config.kennel());
        assertEquals(org.bukkit.Material.BARREL, config.kennelBlock());
        assertEquals(java.util.List.of(ItemRef.parse("m.pets.meat_treat")), config.type("beagle").treats());
        assertEquals(java.util.List.of(ItemRef.parse("ia.tfmc:pet_medicine")), config.type("beagle").medicines());
        var egg = new org.bukkit.inventory.ItemStack(org.bukkit.Material.WOLF_SPAWN_EGG);
        assertEquals("wolf", config.byEgg(egg).id());
        var meta = egg.getItemMeta();
        meta.setCustomModelData(12002);
        egg.setItemMeta(meta);
        assertEquals("legacy", config.byEgg(egg).id());
    }
}
