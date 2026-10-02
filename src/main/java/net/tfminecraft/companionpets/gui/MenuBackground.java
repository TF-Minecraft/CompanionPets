package net.tfminecraft.companionpets.gui;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

/** One neutral background for every player and staff inventory. */
public final class MenuBackground {
    private MenuBackground() { }
    public static void fill(Inventory inventory) {
        var item = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        var meta = item.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, item.clone());
    }
}
