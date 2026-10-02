package net.tfminecraft.companionpets.item;

import java.util.Base64;
import org.bukkit.inventory.ItemStack;

/** Stores the exact thrown item, including custom identity, lore and model data. */
public final class ToyItems {
    private static final String PREFIX = "stack:";
    private ToyItems() {}

    public static String encode(ItemStack item) {
        ItemStack single = item.clone();
        single.setAmount(1);
        return PREFIX + Base64.getEncoder().encodeToString(single.serializeAsBytes());
    }

    public static ItemStack decode(String saved) {
        if (saved == null) return null;
        if (saved.startsWith(PREFIX)) {
            ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(saved.substring(PREFIX.length())));
            item.setAmount(1);
            return item;
        }
        // Older pets persisted only the vanilla material name.
        return ItemBridge.create(ItemRef.parse(saved));
    }
}
