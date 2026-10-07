package net.tfminecraft.companionpets.item;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.mockbukkit.mockbukkit.MockBukkit;

class ItemRefTest {
    @BeforeAll static void mock() { MockBukkit.mock(); }
    @AfterAll static void unmock() { MockBukkit.unmock(); }
    @Test void archaeoNotationAndAliasesResolveTheSameProviderIdentity() {
        assertEquals(ItemRef.parse("STICK"), ItemRef.parse("minecraft:stick"));
        assertEquals(ItemRef.parse("m.pets.meat_treat"), ItemRef.parse("mmoitems:PETS:MEAT_TREAT"));
        assertEquals(ItemRef.parse("m.pets.meat_treat"), ItemRef.parse("mi:pets:meat_treat"));
        assertEquals(ItemRef.parse("ia.tfmc:pet_ball"), ItemRef.parse("itemsadder:TFMC:PET_BALL"));
        assertEquals(ItemRef.parse("ia.tfmc:pet_ball"), ItemRef.parse("tfmc:pet_ball"));
        assertEquals("mmoitems:PETS:MEAT_TREAT", ItemRef.parse("m.pets.meat_treat").configToken());
        assertEquals(ItemRef.parse("mmoitems:PETS:MEAT_TREAT"), ItemRef.parse(ItemRef.yamlToken(
                java.util.Map.of("mmoitems", java.util.Map.of("PETS", "MEAT_TREAT")))));
    }
    @Test void legacyMaterialsKeepSavedFavoriteToyKeys() {
        assertEquals(ItemRef.parse("STICK"), ItemRef.parse("v.stick"));
        assertEquals("STICK", ItemRef.parse("v.StIcK").key());
        assertEquals(ItemRef.parse("m.pets.caring_item"), ItemRef.parse("M.PETS.CARING_ITEM"));
    }

    @Test void sameMaterialDoesNotConfuseFeedAndGlove() {
        var glove = new ItemIdentity(Material.RABBIT_HIDE, "PETS", "CARING_ITEM", null, true);
        var feed = new ItemIdentity(Material.RABBIT_HIDE, "PETS", "UNIVERSAL_FEED", null, true);
        assertTrue(ItemRef.parse("m.pets.caring_item").matches(glove));
        assertFalse(ItemRef.parse("m.pets.caring_item").matches(feed));
        assertTrue(ItemRef.parse("m.pets.universal_feed").matches(feed));
        assertFalse(ItemRef.parse("m.pets.universal_feed").matches(glove));
        assertFalse(ItemRef.parse("v.rabbit_hide").matches(glove));
        assertFalse(ItemRef.parse("RABBIT_HIDE").matches(feed));
    }

    @Test void mmoTypeIsPartOfIdentityAndMaterialIsNot() {
        ItemRef ref = ItemRef.parse("m.pets.meat_treat");
        assertTrue(ref.matches(new ItemIdentity(Material.STICK, "pets", "meat_treat", null, true)));
        assertFalse(ref.matches(new ItemIdentity(Material.RABBIT_FOOT, "FOOD", "MEAT_TREAT", null, true)));
        assertFalse(ref.matches(new ItemIdentity(Material.RABBIT_FOOT, null, null, null, true)));
    }

    @Test void itemsAdderRequiresFullNamespaceAndNeverMatchesVanilla() {
        var custom = new ItemIdentity(Material.SLIME_BALL, null, null, "tfmc:pet_ball", true);
        assertTrue(ItemRef.parse("ia.tfmc:pet_ball").matches(custom));
        assertFalse(ItemRef.parse("ia.other:pet_ball").matches(custom));
        assertFalse(ItemRef.parse("v.slime_ball").matches(custom));
    }

    @Test void unknownIdentityIsRefusedEvenForVanilla() {
        var failed = new ItemIdentity(Material.BRUSH, null, null, null, false);
        assertFalse(ItemRef.parse("v.brush").matches(failed));
        assertFalse(ItemRef.parse("v.brush").matches((ItemIdentity) null));
        assertTrue(ItemRef.parse("v.brush").matches(new ItemIdentity(Material.BRUSH, null, null, null, true)));
    }

    @Test void malformedSelectorsAreRejected() {
        for (String value : new String[]{"", "m.pets", "m..feed", "m.pets.feed.extra", "ia.tfmc.pet_ball",
                "ia.pet_ball", "ia.TFMC:pet_ball", "v.not_a_material", "v.air", "v.water"}) {
            assertThrows(IllegalArgumentException.class, () -> ItemRef.parse(value), value);
        }
    }
    @Test void providerTokensRoundTripAndRejectMalformedNamespaceSeparators() {
        assertEquals("STICK",ItemRef.parse("minecraft:stick").configToken());
        var item = ItemRef.parse("mi:food:pet_treat");
        assertEquals("mmoitems:FOOD:PET_TREAT", item.configToken());
        assertEquals(item, ItemRef.parse(item.configToken()));
        assertThrows(IllegalArgumentException.class, () -> ItemRef.parse("mi:food:bad:extra"));
        assertThrows(IllegalArgumentException.class, () -> ItemRef.parse("itemsadder:missing-namespace"));
    }
}
