package net.tfminecraft.companionpets;

import org.bukkit.plugin.java.JavaPlugin;

public final class PetsPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("CompanionPets enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("CompanionPets disabled");
    }
}
