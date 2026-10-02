package net.tfminecraft.companionpets.gui;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** The last row belongs to navigation: Back at the left, Next at the right. */
public final class MenuNavigation {
    private MenuNavigation() { }
    public static int backSlot(Inventory inventory) { return inventory.getSize() - 9; }
    public static int nextSlot(Inventory inventory) { return inventory.getSize() - 1; }
    public static void back(Inventory inventory, String description) {
        inventory.setItem(backSlot(inventory), icon(Material.ITEM_FRAME, "Back", description));
    }
    public static int pages(Inventory inventory, int page, int count, String parentDescription) {
        int pages = Math.max(1, (count + backSlot(inventory) - 1) / backSlot(inventory));
        if (parentDescription != null) back(inventory, parentDescription);
        inventory.setItem(nextSlot(inventory), icon(Material.ARROW,
                "Next page · " + (page + 1) + "/" + pages,
                "Left-click: next page\nRight-click: previous page"));
        return pages;
    }
    public static boolean turn(org.bukkit.entity.Player player, int page, int pages, boolean previous) {
        if (previous ? page > 0 : page + 1 < pages) return true;
        net.tfminecraft.companionpets.fx.PetFx.cue(player, org.bukkit.Sound.ENTITY_VILLAGER_NO, 1F);
        return false;
    }
    private static ItemStack icon(Material material, String title, String description) {
        var item = new ItemStack(material); var meta = item.getItemMeta();
        meta.displayName(Component.text(title, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        if (!description.isEmpty()) meta.lore(java.util.Arrays.stream(description.split("\n"))
                .map(line -> (Component) Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta); return item;
    }
}
