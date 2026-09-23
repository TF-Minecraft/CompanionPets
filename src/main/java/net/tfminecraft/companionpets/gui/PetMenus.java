package net.tfminecraft.companionpets.gui;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.NeedBand;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetOrder;
import net.tfminecraft.companionpets.pet.PetSex;
import net.tfminecraft.companionpets.pet.Trick;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.companionpets.text.PetTexts;

public final class PetMenus {
    private static final int TRICK_ROW = 10;

    private final PetRuntime runtime;

    public PetMenus(PetRuntime runtime) {
        this.runtime = runtime;
    }

    public static int trickIndex(int slot) {
        int index = slot - TRICK_ROW;
        if (index < 0 || index >= Trick.values().length) {
            return -1;
        }
        return index;
    }

    public void openCare(org.bukkit.entity.Player player, Pet pet) {
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.CARE, pet.id(), null);
        Inventory inventory = Bukkit.createInventory(holder, 27, title(pet.name()));
        holder.inventory(inventory);
        fillCare(inventory, pet);
        player.openInventory(inventory);
    }

    public void refreshCare(org.bukkit.entity.Player player, Pet pet) {
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder holder)
                || holder.kind() != MenuHolder.Kind.CARE
                || !pet.id().equals(holder.petId())) {
            return;
        }
        fillCare(holder.getInventory(), pet);
    }

    private static void fillCare(Inventory inventory, Pet pet) {
        frame(inventory);
        inventory.setItem(4, named(Material.NAME_TAG, pet.name(), NamedTextColor.GOLD,
                line("Vínculo " + Math.round(pet.bond()), NamedTextColor.LIGHT_PURPLE),
                meter(pet.bond())));
        inventory.setItem(10, needIcon(Material.COOKED_BEEF, "Hambre", pet.need(Need.HUNGER)));
        inventory.setItem(11, needIcon(Material.SUNFLOWER, "Ánimo", pet.need(Need.MOOD)));
        inventory.setItem(12, needIcon(Material.FEATHER, "Energía", pet.need(Need.ENERGY)));
        inventory.setItem(13, needIcon(Material.BRUSH, "Limpieza", pet.need(Need.CLEANLINESS)));
        inventory.setItem(14, needIcon(Material.HONEY_BOTTLE, "Salud", pet.need(Need.HEALTH)));
        inventory.setItem(19, named(Material.COOKED_BEEF, "Alimentar", NamedTextColor.GOLD,
                line("Gasta comida del inventario", NamedTextColor.GRAY)));
        inventory.setItem(20, named(Material.SLIME_BALL, "Jugar", NamedTextColor.GREEN,
                line("Un juego corto, sin objeto", NamedTextColor.GRAY)));
        inventory.setItem(21, named(Material.WHITE_BED, pet.activity() == Activity.SLEEPING ? "Despertar" : "Acostar", NamedTextColor.AQUA,
                line(pet.activity() == Activity.SLEEPING ? "La despierta" : "Solo si tiene sueño", NamedTextColor.GRAY)));
        boolean sitting = pet.order() == PetOrder.SIT;
        inventory.setItem(22, named(Material.LEAD, sitting ? "Seguir" : "Sentar", NamedTextColor.WHITE,
                line(sitting ? "Vuelve a caminar contigo" : "Se queda donde está", NamedTextColor.GRAY)));
        inventory.setItem(23, named(Material.BRUSH, "Limpiar", NamedTextColor.YELLOW,
                line("Hace falta un cepillo", NamedTextColor.GRAY)));
        inventory.setItem(24, named(Material.GLISTERING_MELON_SLICE, "Curar", NamedTextColor.RED,
                line("Solo si está enferma", NamedTextColor.GRAY)));
        String favorite = pet.favoriteToy() == null ? "ninguno" : pet.favoriteToy().toLowerCase();
        inventory.setItem(26, named(Material.STICK, "Juguete favorito", NamedTextColor.GOLD,
                line(favorite, NamedTextColor.GRAY)));
    }

    public void openKennel(org.bukkit.entity.Player player) {
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.KENNEL, null, null);
        Inventory inventory = Bukkit.createInventory(holder, 27, title("Caseta"));
        holder.inventory(inventory);
        frame(inventory);
        int slot = 0;
        for (Pet pet : runtime.store().of(player.getUniqueId())) {
            if (slot >= inventory.getSize()) {
                break;
            }
            PetTypeDef type = runtime.config().type(pet.typeId());
            Material icon = type == null || type.egg() == null ? Material.BONE : type.egg();
            String sex = pet.sex() == PetSex.FEMALE ? "Hembra" : "Macho";
            boolean stored = pet.stored();
            ItemStack item = named(icon, pet.name(), NamedTextColor.GOLD,
                    line(sex + " · " + pet.typeId(), NamedTextColor.GRAY),
                    line(stored ? "En la caseta" : "Fuera", stored ? NamedTextColor.DARK_GRAY : NamedTextColor.GREEN),
                    meter(pet.need(Need.ENERGY)),
                    line("Izquierda: sacar o llamar", NamedTextColor.GRAY),
                    line("Derecha: guardar", NamedTextColor.GRAY),
                    line("Mayús: cambiar nombre", NamedTextColor.GRAY));
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(runtime.petKey(), PersistentDataType.STRING, pet.id().toString());
            item.setItemMeta(meta);
            inventory.setItem(slot++, item);
        }
        player.openInventory(inventory);
    }

    public void openTricks(org.bukkit.entity.Player player, Pet pet, String word) {
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.TRICK, pet.id(), word);
        Inventory inventory = Bukkit.createInventory(holder, 27, title("Enseñar: " + word));
        holder.inventory(inventory);
        frame(inventory);
        inventory.setItem(4, named(Material.BOOK, word, NamedTextColor.GOLD,
                line("Elige qué significa", NamedTextColor.GRAY)));
        Trick[] tricks = Trick.values();
        Material[] icons = {
                Material.SADDLE,
                Material.LEAD,
                Material.BARRIER,
                Material.GOAT_HORN,
                Material.RABBIT_FOOT,
                Material.COMPASS,
                Material.BONE,
                Material.PLAYER_HEAD
        };
        for (int index = 0; index < tricks.length; index++) {
            inventory.setItem(TRICK_ROW + index, named(icons[index], PetTexts.trickName(tricks[index]), NamedTextColor.WHITE,
                    line("Palabra: " + word, NamedTextColor.GRAY)));
        }
        player.openInventory(inventory);
    }

    private static void frame(Inventory inventory) {
        ItemStack edge = pane(Material.BROWN_STAINED_GLASS_PANE);
        ItemStack fill = pane(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int column = slot % 9;
            boolean border = slot < 9 || slot >= inventory.getSize() - 9 || column == 0 || column == 8;
            inventory.setItem(slot, border ? edge : fill);
        }
    }

    private static ItemStack pane(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack needIcon(Material material, String label, double value) {
        NamedTextColor color = bandColor(value);
        return named(material, label + " " + Math.round(value), color, meter(value));
    }

    private static Component meter(double value) {
        int filled = (int) Math.round(Math.max(0.0, Math.min(100.0, value)) / 10.0);
        return Component.text("●".repeat(filled), bandColor(value)).decoration(TextDecoration.ITALIC, false)
                .append(Component.text("●".repeat(10 - filled), NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
    }

    private static NamedTextColor bandColor(double value) {
        return switch (NeedBand.of(value)) {
            case STABLE -> NamedTextColor.GREEN;
            case LOW -> NamedTextColor.YELLOW;
            case CRITICAL -> NamedTextColor.RED;
        };
    }

    private static Component title(String text) {
        return Component.text(text, NamedTextColor.GOLD);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack named(Material material, String name, NamedTextColor color, Component... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>();
            for (Component component : lore) {
                lines.add(component.decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lines);
        }
        item.setItemMeta(meta);
        return item;
    }
}
