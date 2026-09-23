package net.tfminecraft.companionpets;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.integration.ModelHook;
import net.tfminecraft.companionpets.listen.PetListener;
import net.tfminecraft.companionpets.runtime.PetActions;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.companionpets.runtime.PetTicker;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.visual.IdleVisual;
import net.tfminecraft.companionpets.visual.PetVisual;

public final class PetsPlugin extends JavaPlugin {
    private PetStore store;
    private PetActions actions;
    private BukkitTask ticker;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        CompanionConfig config = CompanionConfig.load(this);
        store = new PetStore(this);
        store.load();
        NamespacedKey petKey = new NamespacedKey(this, "pet");
        NamespacedKey toyKey = new NamespacedKey(this, "toy");
        PetVisual visual = ModelHook.available() ? new ModelHook(getLogger()) : new IdleVisual();
        Bodies bodies = new Bodies(this, petKey, visual);
        PetRuntime runtime = new PetRuntime(this, config, store, new Sessions(), bodies, visual, petKey, toyKey);
        actions = new PetActions(runtime);
        Bukkit.getPluginManager().registerEvents(new PetListener(runtime, actions), this);
        ticker = Bukkit.getScheduler().runTaskTimer(this, new PetTicker(runtime, actions), 10L, 10L);
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                actions.reattach(entity);
            }
        }
        getLogger().info("CompanionPets enabled (" + config.types().size() + " pet types)");
    }

    @Override
    public void onDisable() {
        if (ticker != null) {
            ticker.cancel();
        }
        if (actions != null) {
            actions.stashLooseToys();
        }
        if (store != null) {
            store.save();
        }
        getLogger().info("CompanionPets disabled");
    }
}
