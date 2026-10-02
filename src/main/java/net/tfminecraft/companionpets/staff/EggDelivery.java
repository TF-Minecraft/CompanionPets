package net.tfminecraft.companionpets.staff;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import net.tfminecraft.companionpets.item.ItemRef;

/** MMOItems owns item delivery; other providers use their configured item identity. */
final class EggDelivery {
    static boolean deliver(ItemRef ref, ItemStack prepared, Player target, int amount) {
        if (ref.kind() == ItemRef.Kind.MMOITEMS)
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mmoitems:mi give " + ref.type() + " " + ref.id() + " " + target.getName() + " " + amount);
        var item = prepared.clone(); item.setAmount(amount);
        target.getInventory().addItem(item).values().forEach(leftover -> target.getWorld().dropItem(target.getLocation(), leftover));
        return true;
    }
}
