package net.tfminecraft.companionpets.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

import net.tfminecraft.companionpets.item.ItemRef;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.tfminecraft.companionpets.pet.SexMode;
import net.tfminecraft.companionpets.pet.Trick;

public record PetTypeDef(
        String id,
        EntityType entity,
        String mythicMob,
        ItemRef egg,
        Integer eggCustomModelData,
        SexMode sexMode,
        PetAppearance appearance,
        PetItems items,
        Set<Trick> tricks) {

    public PetTypeDef {
        tricks = Set.copyOf(tricks);

    }

    public boolean allowsTrick(Trick trick) {
        return trick != null && tricks.contains(trick);
    }

    public boolean matchesEgg(ItemStack item) {
        if (item == null || item.getAmount() <= 0) return false;
        if (eggCustomModelData == null && egg.kind() != ItemRef.Kind.VANILLA) return egg.matches(item);
        // Legacy material + model eggs also match provider-created eggs.
        if (eggCustomModelData != null ? item.getType() != egg.material() : !egg.matches(item)) return false;
        ItemMeta meta = item.getItemMeta();
        boolean hasModelData = meta != null && meta.hasCustomModelData();
        return eggCustomModelData == null ? !hasModelData
                : hasModelData && meta.getCustomModelData() == eggCustomModelData;
    }

    public ItemStack eggIcon() {
        ItemStack item = egg.icon(org.bukkit.Material.BONE);
        if (eggCustomModelData != null) {
            ItemMeta meta = item.getItemMeta();
            meta.setCustomModelData(eggCustomModelData);
            item.setItemMeta(meta);
        }
        return item;
    }

    public ItemRef toy(ItemStack item) {
        return items.toy(item);
    }

    public boolean acceptsToy(ItemStack item) { return toy(item) != null; }
    public boolean isTreat(ItemStack item) { return items.isTreat(item); }
    public Map<ItemRef, Double> foods() { return items.foods(); }
    public List<ItemRef> treats() { return items.treats(); }
    public List<ItemRef> medicines() { return items.medicines(); }
    public List<ItemRef> brushes() { return items.brushes(); }
    public List<ItemRef> toys() { return items.toys(); }
    public ItemRef foodIcon() { return items.foodIcon(); }

    public Double foodGain(ItemStack item) {
        return items.foodGain(item);
    }
}
