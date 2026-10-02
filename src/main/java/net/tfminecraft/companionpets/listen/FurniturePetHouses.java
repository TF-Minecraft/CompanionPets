package net.tfminecraft.companionpets.listen;

import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.EventExecutor;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.gui.PetMenus;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.companionpets.store.PetStore;

/** Optional ItemsAdder hooks: IA owns placement, protection checks, item consumption and drops. */
public final class FurniturePetHouses implements Listener {
    private final PetRuntime runtime;
    private final PetMenus menus;

    public FurniturePetHouses(PetRuntime runtime) {
        this.runtime = runtime;
        this.menus = new PetMenus(runtime);
    }

    public void register() {
        var provider = runtime.plugin().getServer().getPluginManager().getPlugin("ItemsAdder");
        if (provider == null || !provider.isEnabled()) return;
        try {
            var loader = provider.getClass().getClassLoader();
            register(loader, "FurniturePlaceSuccessEvent", EventPriority.MONITOR, (listener, event) -> placed(event));
            register(loader, "FurnitureInteractEvent", EventPriority.HIGHEST, (listener, event) -> interact(event));
            register(loader, "FurnitureBreakEvent", EventPriority.MONITOR, (listener, event) -> broken(event));
            runtime.plugin().getLogger().info("ItemsAdder Pet House furniture hooks registered");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            runtime.plugin().getLogger().log(Level.SEVERE, "Could not register ItemsAdder Pet House furniture hooks", ex);
        }
    }

    private void register(ClassLoader loader, String name, EventPriority priority, EventExecutor executor)
            throws ClassNotFoundException {
        Class<? extends Event> event = Class.forName("dev.lone.itemsadder.api.Events." + name, true, loader).asSubclass(Event.class);
        runtime.plugin().getServer().getPluginManager().registerEvent(event, this, priority, executor, runtime.plugin(), true);
    }

    void placed(Event event) {
        Entity entity = petHouse(event);
        if (entity == null || !(event instanceof PlayerEvent placement) || placement.getPlayer() == null) return;
        runtime.store().kennel(key(entity), placement.getPlayer().getUniqueId());
        runtime.store().save();
        PetFx.bar(placement.getPlayer(), "Pet House placed. Right-click it to look after your pets");
    }

    void interact(Event event) {
        Entity entity = petHouse(event);
        if (entity == null || !(event instanceof PlayerEvent interaction) || interaction.getPlayer() == null || !(event instanceof Cancellable cancellable)) return;
        try {
            Object action = event.getClass().getMethod("getAction").invoke(event);
            if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;
        } catch (ReflectiveOperationException ex) {
            runtime.plugin().getLogger().log(Level.WARNING, "Could not read Pet House furniture click", ex);
            return;
        }
        UUID owner = runtime.store().kennelOwner(key(entity));
        if (owner == null) return;
        cancellable.setCancelled(true);
        if (!owner.equals(interaction.getPlayer().getUniqueId())) {
            PetFx.bar(interaction.getPlayer(), "This Pet House belongs to someone else");
            return;
        }
        menus.openKennel(interaction.getPlayer());
    }

    void broken(Event event) {
        Entity entity = petHouse(event);
        if (entity != null && runtime.store().removeKennel(key(entity))) runtime.store().save();
    }

    private Entity petHouse(Event event) {
        if (event instanceof Cancellable cancelled && cancelled.isCancelled()) return null;
        String configured = runtime.config().kennelFurniture();
        if (configured == null) return null;
        try {
            Object id = event.getClass().getMethod("getNamespacedID").invoke(event);
            if (!(id instanceof String name) || !configured.equalsIgnoreCase(name)) return null;
            Object entity = event.getClass().getMethod("getBukkitEntity").invoke(event);
            return entity instanceof Entity found ? found : null;
        } catch (ReflectiveOperationException ex) {
            runtime.plugin().getLogger().log(Level.WARNING, "Could not read Pet House furniture event", ex);
            return null;
        }
    }

    private static String key(Entity entity) {
        var at = entity.getLocation();
        return PetStore.kennelKey(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }
}
