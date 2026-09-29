package net.tfminecraft.companionpets.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.tfminecraft.companionpets.pet.SexMode;
import net.tfminecraft.companionpets.pet.Trick;

public record PetTypeDef(
        String id,
        EntityType entity,
        String mythicMob,
        Material egg,
        Integer eggCustomModelData,
        SexMode sexMode,
        PetAppearance appearance,
        Map<Material, Double> foods,
        Material favoriteFood,
        Material medicine,
        List<Material> toys,
        Set<Trick> tricks) {

    public PetTypeDef {
        tricks = Set.copyOf(tricks);
    }

    public boolean allowsTrick(Trick trick) {
        return trick != null && tricks.contains(trick);
    }

    public boolean matchesEgg(ItemStack item) {
        if (item == null || item.getType() != egg) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        boolean hasModelData = meta != null && meta.hasCustomModelData();
        return eggCustomModelData == null ? !hasModelData
                : hasModelData && meta.getCustomModelData() == eggCustomModelData;
    }

    public boolean acceptsToy(Material material) {
        return material != null && toys.contains(material);
    }

    public Double foodGain(Material material) {
        if (material == null) {
            return null;
        }
        return foods.get(material);
    }
}
