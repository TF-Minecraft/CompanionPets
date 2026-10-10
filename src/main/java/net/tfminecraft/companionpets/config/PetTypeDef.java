package net.tfminecraft.companionpets.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

import net.tfminecraft.companionpets.item.ItemRef;
import net.tfminecraft.companionpets.item.HeldItem;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.Permissible;

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
        Set<Trick> tricks,
        List<Trick> defaultTricks,
        BehaviorProfile behavior,
        PetSounds sounds,
        boolean nativeCombat,
        String species,
        String hatchPermission) {

    public PetTypeDef {
        tricks = Set.copyOf(tricks);
        defaultTricks = List.copyOf(defaultTricks);
        java.util.Objects.requireNonNull(behavior);
    }

    /** A null hatch permission lets anyone hatch this type's egg. */
    public boolean canHatch(Permissible player) {
        return hatchPermission == null || player.hasPermission(hatchPermission);
    }

    public Set<PetBehavior> behaviors() { return behavior.behaviors(); }
    public boolean behaves(PetBehavior behavior) { return behaviors().contains(behavior); }

    public boolean allowsTrick(Trick trick) {
        return trick != null && tricks.contains(trick);
    }

    public boolean matchesEgg(ItemStack item) { return matchesEgg(HeldItem.of(item)); }

    public boolean matchesEgg(HeldItem item) {
        if (item.empty()) return false;
        if (eggCustomModelData == null && egg.kind() != ItemRef.Kind.VANILLA) return item.matches(egg);
        // Legacy material + model eggs also match provider-created eggs.
        if (eggCustomModelData != null ? item.material() != egg.material() : !item.matches(egg)) return false;
        return java.util.Objects.equals(eggCustomModelData, item.model());
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

    public ItemRef toy(HeldItem item) { return items.toy(item); }
    public boolean acceptsToy(HeldItem item) { return toy(item) != null; }
    public boolean isTreat(HeldItem item) { return items.isTreat(item); }
    public Double foodGain(HeldItem item) { return items.foodGain(item); }

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
