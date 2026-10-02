package net.tfminecraft.companionpets.item;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class HandItems {
    private HandItems() { }
    public static boolean consume(Player player, ItemStack hand) {
        if (hand == null || hand.getType().isAir() || hand.getAmount() <= 0) return false;
        if (player.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        return true;
    }
}
