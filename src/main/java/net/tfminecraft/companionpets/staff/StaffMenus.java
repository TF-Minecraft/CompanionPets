package net.tfminecraft.companionpets.staff;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.companionpets.gui.MenuNavigation;

/** A selected player's pets and their read-only profiles. */
public final class StaffMenus implements Listener {
    private final PetRuntime runtime;
    private final StaffCommands commands;
    public StaffMenus(PetRuntime runtime, StaffCommands commands) { this.runtime = runtime; this.commands = commands; }

    public void list(Player player, UUID owner, int requestedPage) {
        if (!allowed(player, "list")) return;
        var pets = commands.owned(owner);
        int page = page(requestedPage, pets.size());
        var holder = create(player, StaffMenuHolder.Kind.LIST, owner, page, "Pets: " + commands.ownerName(owner));
        int slot = 0;
        for (Pet pet : pets.subList(page * 45, Math.min(pets.size(), (page + 1) * 45))) {
            var type = runtime.config().type(pet.typeId());
            var icon = type == null ? new ItemStack(Material.BONE) : type.eggIcon();
            button(holder, slot++, named(icon, pet.name(), "Owner: " + commands.ownerName(owner), "Type: " + pet.typeId(),
                    pet.stored() ? "In Pet House" : "Outside", "Click to view this pet"), () -> refresh(player, pet.id()));
        }
        navigation(player, holder, pets.size(), () -> list(player, owner, page - 1), () -> list(player, owner, page + 1));
        player.openInventory(holder.getInventory());
    }

    public void inspect(Player player, Pet pet) {
        if (!allowed(player, "list")) return;
        var holder = create(player, StaffMenuHolder.Kind.INSPECT, pet.id(), 0, pet.name(), 45);
        new net.tfminecraft.companionpets.gui.PetMenus(runtime).fillInformation(holder.getInventory(), pet);
        holder.button(net.tfminecraft.companionpets.gui.PetMenus.BACK_SLOT, () -> list(player, pet.ownerId(), 0));
        holder.button(net.tfminecraft.companionpets.gui.PetMenus.TRICKS_SLOT, () -> tricks(player, pet.id(), 0));
        player.openInventory(holder.getInventory());
    }

    public void tricks(Player player, UUID petId, int requestedPage) {
        if (!allowed(player, "list")) return;
        Pet pet = runtime.store().get(petId);
        if (pet == null) { player.closeInventory(); return; }
        int count = (int) runtime.config().tricks().stream().filter(t -> net.tfminecraft.companionpets.training.TrickAvailability.allows(runtime, pet, t)).count();
        int selectedPage = Math.max(0, Math.min(requestedPage, Math.max(0, (count - 1) / net.tfminecraft.companionpets.gui.PetMenus.TRICKS_PER_PAGE)));
        var holder = create(player, StaffMenuHolder.Kind.TRICKS, petId, selectedPage, pet.name() + "'s tricks", 27);
        var renderer = new net.tfminecraft.companionpets.gui.PetMenus(runtime);
        int page = renderer.fillLearnedInformation(holder.getInventory(), pet, selectedPage);
        holder.button(net.tfminecraft.companionpets.gui.PetMenus.TRICKS_BACK_SLOT, () -> refresh(player, petId));
        holder.button(net.tfminecraft.companionpets.gui.PetMenus.TRICKS_NEXT_SLOT, () -> {
            if (MenuNavigation.turn(player, page, Math.max(1, (count + 17) / 18), false)) tricks(player, petId, page + 1);
        });
        holder.rightButton(net.tfminecraft.companionpets.gui.PetMenus.TRICKS_NEXT_SLOT, () -> {
            if (MenuNavigation.turn(player, page, Math.max(1, (count + 17) / 18), true)) tricks(player, petId, page - 1);
        });
        player.openInventory(holder.getInventory());
    }
    private void refresh(Player player, UUID petId) {
        Pet pet = runtime.store().get(petId);
        if (pet != null) inspect(player, pet); else player.closeInventory();
    }
    private boolean allowed(Player player, String action) {
        if (player.hasPermission(StaffCommands.permission(action))) return true;
        player.closeInventory(); player.sendMessage("You do not have permission: " + StaffCommands.permission(action)); return false;
    }
    private static int page(int requested, int size) { return Math.max(0, Math.min(requested, Math.max(0, (size - 1) / 45))); }
    private static StaffMenuHolder create(Player player, StaffMenuHolder.Kind kind, UUID subject, int page, String title) {
        return create(player, kind, subject, page, title, 54);
    }
    private static StaffMenuHolder create(Player player, StaffMenuHolder.Kind kind, UUID subject, int page, String title, int size) {
        var holder = new StaffMenuHolder(player.getUniqueId(), kind, subject, page);
        holder.inventory(Bukkit.createInventory(holder, size, Component.text(title, NamedTextColor.GOLD)));
        net.tfminecraft.companionpets.gui.MenuBackground.fill(holder.getInventory());
        return holder;
    }
    private static void button(StaffMenuHolder holder, int slot, ItemStack item, Runnable action) {
        holder.getInventory().setItem(slot, item); holder.button(slot, action);
    }
    private static void navigation(Player player, StaffMenuHolder holder, int size, Runnable previous, Runnable next) {
        int pages = MenuNavigation.pages(holder.getInventory(), holder.page(), size, null);
        holder.button(MenuNavigation.nextSlot(holder.getInventory()), () -> {
            if (MenuNavigation.turn(player, holder.page(), pages, false)) next.run();
        });
        holder.rightButton(MenuNavigation.nextSlot(holder.getInventory()), () -> {
            if (MenuNavigation.turn(player, holder.page(), pages, true)) previous.run();
        });
    }
    private static ItemStack named(Material material, String title, String... lore) { return named(new ItemStack(material), title, lore); }
    private static ItemStack named(ItemStack source, String title, String... lore) {
        var item = source.clone(); item.setAmount(1);
        var meta = item.getItemMeta(); meta.displayName(Component.text(title, NamedTextColor.GOLD));
        meta.lore(Arrays.stream(lore).map(line -> (Component) Component.text(line, NamedTextColor.GRAY)).toList()); item.setItemMeta(meta); return item;
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof StaffMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.viewer().equals(player.getUniqueId())) return;
        if (event.getClickedInventory() != event.getView().getTopInventory() || !(event.getClick().isLeftClick() || event.getClick().isRightClick())
                || event.isShiftClick()) return;
        if (allowed(player, "list")) holder.click(event.getSlot(), event.getClick().isRightClick());
    }
    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof StaffMenuHolder) event.setCancelled(true);
    }
}
