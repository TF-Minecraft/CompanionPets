package net.tfminecraft.companionpets.listen;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataType;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import net.tfminecraft.companionpets.gui.MenuHolder;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.runtime.PetActions;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.companionpets.store.PetStore;

public final class PetListener implements Listener {
    private final PetRuntime runtime;
    private final PetActions actions;

    public PetListener(PetRuntime runtime, PetActions actions) {
        this.runtime = runtime;
        this.actions = actions;
    }

    @EventHandler
    public void onEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (actions.useOnPet(player, event.getRightClicked(), player.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        boolean air = action == Action.RIGHT_CLICK_AIR;
        if (!actions.handledWorld(event.getPlayer(), event.getItem(), event.getClickedBlock(), event.getBlockFace(), event.getPlayer().isSneaking(), air)) {
            return;
        }
        event.setCancelled(true);
        actions.useWorld(event.getPlayer(), event.getItem(), event.getClickedBlock(), event.getBlockFace(), event.getPlayer().isSneaking(), air);
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTask(runtime.plugin(), () -> {
            Player online = Bukkit.getPlayer(playerId);
            if (online != null) {
                actions.onChat(online, text);
            }
        });
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof MenuHolder menu) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        actions.clickMenu(player, menu, event.getSlot(), event.getCurrentItem(), event.isRightClick(), event.isShiftClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof MenuHolder menu
                && menu.kind() == MenuHolder.Kind.TRICK
                && event.getPlayer() instanceof Player player) {
            actions.clearPendingWord(player, menu.word());
        }
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            actions.reattach(entity);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        Pet pet = runtime.byEntity(event.getEntity());
        if (pet == null) {
            return;
        }
        pet.clearRuntimeMotion();
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        runtime.store().removeKennel(PetStore.kennelKey(
                event.getBlock().getWorld().getName(),
                event.getBlock().getX(),
                event.getBlock().getY(),
                event.getBlock().getZ()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        runtime.sessions().clearPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onToyHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball ball)) {
            return;
        }
        String data = ball.getPersistentDataContainer().get(runtime.toyKey(), PersistentDataType.STRING);
        if (data == null) {
            return;
        }
        String[] parts = data.split("\\|", 2);
        if (parts.length != 2) {
            ball.remove();
            return;
        }
        Pet pet;
        try {
            pet = runtime.store().get(UUID.fromString(parts[0]));
        } catch (IllegalArgumentException ex) {
            ball.remove();
            return;
        }
        Location at = ball.getLocation().clone();
        ball.remove();
        if (pet == null || pet.fetch() == null) {
            actions.dropPlain(at, parts[1]);
            return;
        }
        actions.dropToy(at, pet, parts[1]);
        actions.toyLanded(pet, pet.fetch().itemId());
    }

    @EventHandler
    public void onToyDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getPersistentDataContainer().has(runtime.toyKey(), PersistentDataType.STRING)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerPickup(PlayerAttemptPickupItemEvent event) {
        if (event.getItem().getPersistentDataContainer().has(runtime.toyKey(), PersistentDataType.STRING)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onMobPickup(EntityPickupItemEvent event) {
        if (event.getItem().getPersistentDataContainer().has(runtime.toyKey(), PersistentDataType.STRING)
                || runtime.byEntity(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }
}
