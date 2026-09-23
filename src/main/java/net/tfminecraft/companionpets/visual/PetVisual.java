package net.tfminecraft.companionpets.visual;

import org.bukkit.entity.Entity;

import net.tfminecraft.companionpets.config.PetTypeDef;

public interface PetVisual {
    void apply(Entity entity, PetTypeDef type);

    void play(Entity entity, PetTypeDef type, String state);
}
