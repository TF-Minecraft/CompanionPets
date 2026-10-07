package net.tfminecraft.companionpets.listen;

import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataType;

import org.bukkit.event.player.AsyncPlayerChatEvent;

import net.tfminecraft.companionpets.gui.MenuHolder;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.runtime.PetActions;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.companionpets.store.PetStore;

public final class PetListener implements Listener {
    private final PetRuntime runtime;
    private final PetActions actions;
    private final Map<UUID, Click> lastClicks = new HashMap<>();

    private record Click(UUID entity, int tick) { }

    public PetListener(PetRuntime runtime, PetActions actions) {
        this.runtime = runtime;
        this.actions = actions;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(EntityTeleportEvent event) {
        Pet pet = runtime.byEntity(event.getEntity());
        if (pet == null || pet.stored() || pet.dead()) return;
        boolean recoveryInProgress = runtime.bodies().recovering(event.getEntity());
        boolean recoveryTeleport = runtime.bodies().claimRecoveryTeleport(event.getEntity(), event.getTo());
        if (!recoveryTeleport && (actions.fetchingOrReturning(pet) || actions.greeting(pet) || actions.socializing(pet) || pet.activity() == net.tfminecraft.companionpets.pet.Activity.TOY_FOCUS
                || pet.staying() || pet.order() != net.tfminecraft.companionpets.pet.PetOrder.FOLLOW
                || pet.activity() == net.tfminecraft.companionpets.pet.Activity.SLEEPING
                || !runtime.followingAllowed(pet, Bukkit.getPlayer(pet.ownerId()))
                || System.currentTimeMillis() < pet.forcedSitUntilMillis())) event.setCancelled(true);
        if (event.isCancelled() || event.getTo() == null) return;
        var bounds = net.tfminecraft.companionpets.body.PetPlacement.bounds(event.getEntity());
        if (!net.tfminecraft.companionpets.body.PetPlacement.safe(event.getTo(), bounds)) {
            var safe = net.tfminecraft.companionpets.body.PetPlacement.nearest(event.getTo(), bounds, null);
            if (safe == null) event.setCancelled(true); else event.setTo(safe);
        }
        if (!event.isCancelled() && !recoveryInProgress) runtime.bodies().protect(event.getEntity());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawnSuffocation(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION
                && runtime.byEntity(event.getEntity()) != null && runtime.bodies().protectedFromSuffocation(event.getEntity())) {
            event.setCancelled(true);
            runtime.bodies().recover(event.getEntity());
        }
    }

    @EventHandler
    public void onEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (interact(player, event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    public void onModelInteract(Player player, Entity entity) {
        if (runtime.visual().attached(entity)) interact(player, entity);
    }

    private boolean interact(Player player, Entity entity) {
        Click previous = lastClicks.get(player.getUniqueId());
        int tick = Bukkit.getCurrentTick();
        if (previous != null && previous.tick() == tick && previous.entity().equals(entity.getUniqueId())) return true;
        boolean handled = actions.useOnPet(player, entity, player.getInventory().getItemInMainHand());
        if (handled) lastClicks.put(player.getUniqueId(), new Click(entity.getUniqueId(), tick));
        return handled;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        boolean air = action == Action.RIGHT_CLICK_AIR;
        // Air clicks can be pre-cancelled because vanilla has no use for the item.
        // Block interactions must respect both independent protection results.
        if (!air && (event.useInteractedBlock() == Event.Result.DENY
                || event.useItemInHand() == Event.Result.DENY)) return;
        if (!actions.handledWorld(event.getPlayer(), event.getItem(), event.getClickedBlock(), event.getBlockFace(), event.getPlayer().isSneaking(), air)) {
            return;
        }
        event.setCancelled(true);
        actions.useWorld(event.getPlayer(), event.getItem(), event.getClickedBlock(), event.getBlockFace(), event.getPlayer().isSneaking(), air);
    }

    // RPCharacters dispatches this event at MONITOR. Consume private dialogue first.
    // Paper fires the legacy event while this listener is registered; using one
    // event path also avoids processing the legacy and Adventure events twice.
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String text = event.getMessage();
        UUID playerId = player.getUniqueId();
        Object prompt = runtime.sessions().privatePrompt(playerId);
        if (prompt != null) event.setCancelled(true);
        Bukkit.getScheduler().runTask(runtime.plugin(), () -> {
            Player online = Bukkit.getPlayer(playerId);
            if (online != null && (prompt == null
                    ? runtime.sessions().privatePrompt(playerId) == null
                    : runtime.sessions().privatePrompt(playerId) == prompt)) {
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
        boolean lettingGo = event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP;
        actions.clickMenu(player, menu, event.getSlot(), event.getCurrentItem(), event.isRightClick(), event.isShiftClick(), lettingGo);
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
                && !menu.navigating()
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            Pet pet = runtime.byEntity(entity);
            if (pet != null && entity.getUniqueId().equals(pet.entityId())) {
                runtime.remember(pet, entity);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        Pet pet = runtime.byEntity(event.getEntity());
        if (pet == null) {
            // A neglect death commits its deletion before starting the model's
            // death animation, so its record is already absent here.
            UUID id = runtime.bodies().readId(event.getEntity());
            if (id != null && runtime.store().isDeleted(id)) {
                event.getDrops().clear();
                event.setDroppedExp(0);
            }
            return;
        }
        if (pet.dead()) {
            // Neglect previously removed the body without drops. Keep that behaviour
            // while allowing a modeled body to finish its death animation.
            event.getDrops().clear();
            event.setDroppedExp(0);
            return;
        }
        if (event.getEntity().isSilent()) runtime.voice().play(event.getEntity(), net.tfminecraft.companionpets.config.PetSounds.Event.DEATH);
        actions.lostBody(pet);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVisualDamage(EntityDamageEvent event) {
        if (event.getFinalDamage() <= 0) return;
        Pet victim = runtime.byEntity(event.getEntity());
        if (victim != null) {
            if (event.getEntity().isSilent()) runtime.voice().hurt(event.getEntity());
            runtime.visual().cancelAction(event.getEntity());
            runtime.visual().play(event.getEntity(), runtime.config().type(victim.typeId()), "HURT");
        }
        if (event instanceof EntityDamageByEntityEvent attack) {
            if (victim != null) actions.struck(victim, event.getEntity());
            Pet attacker = runtime.byEntity(attack.getDamager());
            if (attacker != null) {
                runtime.visual().cancelAction(attack.getDamager());
                runtime.visual().play(attack.getDamager(), runtime.config().type(attacker.typeId()), "ATTACK");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (runtime.store().removeKennel(PetStore.kennelKey(
                event.getBlock().getWorld().getName(),
                event.getBlock().getX(),
                event.getBlock().getY(),
                event.getBlock().getZ()))) {
            runtime.store().requestSave();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        actions.ownerSessionChanged(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        actions.ownerDeparted(event.getPlayer());
        actions.ownerSessionChanged(event.getPlayer());
        lastClicks.remove(event.getPlayer().getUniqueId());
        runtime.sessions().clearPlayer(event.getPlayer().getUniqueId());
        PetFx.clearPlayer(event.getPlayer().getUniqueId());
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
        actions.toyLanded(ball);
    }

    @EventHandler(ignoreCancelled = true)
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
