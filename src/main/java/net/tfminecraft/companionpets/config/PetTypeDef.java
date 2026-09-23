package net.tfminecraft.companionpets.config;

import java.util.List;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import net.tfminecraft.companionpets.pet.SexMode;

public record PetTypeDef(
        String id,
        EntityType entity,
        String mythicMob,
        Material egg,
        SexMode sexMode,
        String model,
        Map<Material, Double> foods,
        Material favoriteFood,
        Material medicine,
        List<Material> toys,
        Map<String, String> animations) {

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
