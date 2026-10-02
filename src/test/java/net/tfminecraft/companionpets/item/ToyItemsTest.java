package net.tfminecraft.companionpets.item;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import net.kyori.adventure.text.Component;

class ToyItemsTest {
    @BeforeAll static void mock() { MockBukkit.mock(); }
    @AfterAll static void unmock() { MockBukkit.unmock(); }

    @Test void roundTripPreservesModelNameLoreEnchantmentsAndProviderData() {
        ItemStack original = new ItemStack(Material.STICK, 8);
        var meta = original.getItemMeta();
        meta.displayName(Component.text("Pet ball"));
        meta.lore(List.of(Component.text("Favourite toy")));
        meta.setCustomModelData(12022);
        meta.getPersistentDataContainer().set(new NamespacedKey("itemsadder", "id"), PersistentDataType.STRING, "pet_ball");
        meta.addEnchant(Enchantment.UNBREAKING, 2, true);
        original.setItemMeta(meta);

        ItemStack returned = ToyItems.decode(ToyItems.encode(original));
        assertEquals(8, original.getAmount(), "Encoding must not consume or change the held stack");
        assertEquals(1, returned.getAmount());
        assertTrue(original.isSimilar(returned), "All metadata must survive return/storage");
    }

    @Test void oldSavedMaterialToysStillLoad() {
        assertEquals(Material.STICK, ToyItems.decode("STICK").getType());
        assertEquals(1, ToyItems.decode("STICK").getAmount());
        assertNull(ToyItems.decode(null));
    }

    @Test void corruptSavedToyDoesNotCreateAnUnrelatedVanillaItem() {
        assertThrows(IllegalArgumentException.class, () -> ToyItems.decode("stack:not valid base64!"));
        assertThrows(IllegalArgumentException.class, () -> ToyItems.decode("NOT_AN_ITEM"));
    }
}
