package net.tfminecraft.companionpets.gui;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.kyori.adventure.text.Component;

import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetSex;
import net.tfminecraft.companionpets.pet.Trick;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.companionpets.text.PetTexts;

public final class PetMenus {
    private final PetRuntime runtime;

    public PetMenus(PetRuntime runtime) {
        this.runtime = runtime;
    }

    public void openCare(org.bukkit.entity.Player player, Pet pet) {
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.CARE, pet.id(), null);
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text(pet.name()));
        holder.inventory(inventory);
        ItemStack fill = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, fill);
        }
        inventory.setItem(4, named(Material.NAME_TAG, pet.name(),
                "Vínculo " + Math.round(pet.bond()),
                PetTexts.summary(pet)));
        inventory.setItem(10, needIcon(Material.COOKED_BEEF, "Hambre", pet.need(Need.HUNGER)));
        inventory.setItem(11, needIcon(Material.SUNFLOWER, "Ánimo", pet.need(Need.MOOD)));
        inventory.setItem(12, needIcon(Material.FEATHER, "Energía", pet.need(Need.ENERGY)));
        inventory.setItem(13, needIcon(Material.BRUSH, "Limpieza", pet.need(Need.CLEANLINESS)));
        inventory.setItem(14, needIcon(Material.HONEY_BOTTLE, "Salud", pet.need(Need.HEALTH)));
        inventory.setItem(19, named(Material.COOKED_BEEF, "Alimentar", "Gasta comida del inventario"));
        inventory.setItem(20, named(Material.SLIME_BALL, "Jugar", "Un juego corto, sin objeto"));
        inventory.setItem(21, named(Material.WHITE_BED, pet.activity() == Activity.SLEEPING ? "Despertar" : "Acostar"));
        inventory.setItem(22, named(Material.LEAD, pet.order() == net.tfminecraft.companionpets.pet.PetOrder.SIT ? "Seguir" : "Sentar"));
        inventory.setItem(23, named(Material.BRUSH, "Limpiar", "Hace falta un cepillo"));
        inventory.setItem(24, named(Material.GLISTERING_MELON_SLICE, "Curar", "Solo si está enferma"));
        String favorite = pet.favoriteToy() == null ? "ninguno" : pet.favoriteToy().toLowerCase();
        inventory.setItem(26, named(Material.STICK, "Juguete favorito", favorite));
        player.openInventory(inventory);
    }

    public void openKennel(org.bukkit.entity.Player player) {
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.KENNEL, null, null);
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text("Caseta"));
        holder.inventory(inventory);
        int slot = 0;
        for (Pet pet : runtime.store().of(player.getUniqueId())) {
            if (slot >= inventory.getSize()) {
                break;
            }
            PetTypeDef type = runtime.config().type(pet.typeId());
            Material icon = type == null || type.egg() == null ? Material.BONE : type.egg();
            String sex = pet.sex() == PetSex.FEMALE ? "Hembra" : "Macho";
            String where = pet.stored() ? "En la caseta" : "Fuera";
            ItemStack item = named(icon, pet.name(),
                    "Tipo: " + pet.typeId(),
                    "Sexo: " + sex,
                    where,
                    PetTexts.summary(pet),
                    "Clic izquierdo: sacar o llamar",
                    "Clic derecho: guardar",
                    "Mayús + clic: cambiar nombre");
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(runtime.petKey(), PersistentDataType.STRING, pet.id().toString());
            item.setItemMeta(meta);
            inventory.setItem(slot++, item);
        }
        player.openInventory(inventory);
    }

    public void openTricks(org.bukkit.entity.Player player, Pet pet, String word) {
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.TRICK, pet.id(), word);
        Inventory inventory = Bukkit.createInventory(holder, 9, Component.text("Enseñar: " + word));
        holder.inventory(inventory);
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
            inventory.setItem(index, named(icons[index], PetTexts.trickName(tricks[index]), word));
        }
        player.openInventory(inventory);
    }

    private static ItemStack needIcon(Material material, String label, double value) {
        return named(material, label + " " + Math.round(value));
    }

    private static ItemStack named(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(Component.text(line));
            }
            meta.lore(lines);
        }
        item.setItemMeta(meta);
        return item;
    }
}
