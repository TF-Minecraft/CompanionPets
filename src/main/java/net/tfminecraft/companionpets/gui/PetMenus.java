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
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.NeedBand;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetSex;
import net.tfminecraft.companionpets.pet.Trick;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.companionpets.text.PetTexts;
import net.tfminecraft.companionpets.training.TrainingMath;
import net.tfminecraft.companionpets.training.TrainingSettings;

public final class PetMenus {
    private static final int TRICK_ROW = 10;
    private static final int WORD_SLOT = 4;
    public static final int SKIP_SLOT = 22;
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
        Inventory inventory = Bukkit.createInventory(holder, 45, title(pet.name()));
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

    private void fillCare(Inventory inventory, Pet pet) {
        if (inventory.getSize() < 45) {
            return;
        }
        frame(inventory);
        PetTypeDef type = runtime.config().type(pet.typeId());
        Material egg = type == null || type.egg() == null ? Material.BONE : type.egg();
        boolean female = pet.sex() == PetSex.FEMALE;
        Material food = type == null || type.favoriteFood() == null ? Material.COOKED_BEEF : type.favoriteFood();
        boolean sick = pet.illness() != Illness.NONE;

        inventory.setItem(4, named(egg, PetTexts.speciesName(pet.typeId()), NamedTextColor.GOLD,
                line("Species", NamedTextColor.GRAY)));
        inventory.setItem(11, named(
                female ? Material.PINK_DYE : Material.LIGHT_BLUE_DYE,
                PetTexts.sexName(pet.sex()),
                female ? TextColor.color(0xFF8AD4) : TextColor.color(0x55BFFF)));
        inventory.setItem(13, named(Material.NAME_TAG, pet.name(), NamedTextColor.GOLD,
                condition(pet),
                Component.empty(),
                line("Rename " + PetTexts.him(pet.sex()) + " from your kennel", NamedTextColor.DARK_GRAY)));
        inventory.setItem(15, named(Material.CLOCK, "Age", NamedTextColor.WHITE,
                line(PetTexts.age(pet.bornAt(), System.currentTimeMillis()), NamedTextColor.GRAY)));

        Component treat = type == null || type.favoriteFood() == null ? line("treats", NamedTextColor.GRAY) : item(type.favoriteFood());
        inventory.setItem(20, needIcon(food, pet, Need.HUNGER, line("Feed ", NamedTextColor.DARK_GRAY).append(foods(type))));
        inventory.setItem(21, needIcon(Material.SUNFLOWER, pet, Need.MOOD,
                line("Play fetch or give " + PetTexts.him(pet.sex()) + " ", NamedTextColor.DARK_GRAY).append(treat)));
        inventory.setItem(22, needIcon(Material.BLAZE_POWDER, pet, Need.ENERGY,
                line("Recovers while " + PetTexts.he(pet.sex()) + " sleeps", NamedTextColor.DARK_GRAY)));
        inventory.setItem(23, needIcon(Material.BRUSH, pet, Need.CLEANLINESS,
                line("Use a brush on " + PetTexts.him(pet.sex()), NamedTextColor.DARK_GRAY)));
        Material medicine = type == null || type.medicine() == null ? Material.HONEY_BOTTLE : type.medicine();
        inventory.setItem(24, needIcon(sick ? medicine : Material.GOLDEN_APPLE, pet, Need.HEALTH,
                sick
                        ? line("Cure with ", NamedTextColor.DARK_GRAY).append(item(medicine))
                        : line("Keep " + PetTexts.his(pet.sex()) + " needs up to stay healthy", NamedTextColor.DARK_GRAY),
                sick ? line(PetTexts.illness(pet.name(), pet.sex(), pet.illness()), NamedTextColor.RED) : null));

        inventory.setItem(30, named(Material.LEAD, "Bond", StatLook.BOND,
                StatLook.bar(pet.bond(), StatLook.BOND),
                line(StatLook.bondState(pet.bond()), StatLook.BOND),
                Component.empty(),
                line("Grows while you spend time together", NamedTextColor.DARK_GRAY)));
        inventory.setItem(32, toyIcon(pet));
    }

    private static Component condition(Pet pet) {
        if (pet.illness() != Illness.NONE) {
            return line("● " + PetTexts.illness(pet.name(), pet.sex(), pet.illness()), NamedTextColor.RED);
        }
        Need worst = null;
        for (Need need : Need.values()) {
            if (worst == null || pet.need(need) < pet.need(worst)) {
                worst = need;
            }
        }
        double value = pet.need(worst);
        if (NeedBand.of(value) == NeedBand.STABLE) {
            return line("● Happy and healthy", StatLook.band(value));
        }
        return line("● Needs care: " + StatLook.state(worst, value).toLowerCase(java.util.Locale.ROOT), StatLook.band(value));
    }

    private static Component item(Material material) {
        return Component.translatable(material.translationKey(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static Component foods(PetTypeDef type) {
        if (type == null || type.foods().isEmpty()) {
            return line("food", NamedTextColor.GRAY);
        }
        Component joined = Component.empty();
        int index = 0;
        for (Material food : type.foods().keySet()) {
            if (index > 0) {
                joined = joined.append(line(index == type.foods().size() - 1 ? " or " : ", ", NamedTextColor.DARK_GRAY));
            }
            joined = joined.append(item(food));
            index++;
        }
        return joined;
    }

    private static ItemStack toyIcon(Pet pet) {
        Material toy = pet.favoriteToy() == null ? null : Material.matchMaterial(pet.favoriteToy());
        if (toy == null) {
            return named(Material.BARRIER, "No favorite toy yet", NamedTextColor.DARK_GRAY);
        }
        return named(toy, "Favorite Toy", NamedTextColor.GOLD,
                Component.translatable(toy.translationKey()).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
    }

    public void openKennel(org.bukkit.entity.Player player) {
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.KENNEL, null, null);
        Inventory inventory = Bukkit.createInventory(holder, 27, title("Kennel"));
        holder.inventory(inventory);
        frame(inventory);
        int slot = 0;
        for (Pet pet : runtime.store().of(player.getUniqueId())) {
            if (slot >= inventory.getSize()) {
                break;
            }
            PetTypeDef type = runtime.config().type(pet.typeId());
            Material icon = type == null || type.egg() == null ? Material.BONE : type.egg();
            boolean stored = pet.stored();
            ItemStack item = named(icon, pet.name(), NamedTextColor.GOLD,
                    line(PetTexts.sexName(pet.sex()) + " " + PetTexts.speciesName(pet.typeId()).toLowerCase(java.util.Locale.ROOT),
                            NamedTextColor.GRAY),
                    line(stored ? "Resting in the kennel" : "Out and about", stored ? NamedTextColor.DARK_GRAY : NamedTextColor.GREEN),
                    line("Energy ", StatLook.theme(Need.ENERGY)).append(StatLook.bar(pet.need(Need.ENERGY))),
                    Component.empty(),
                    line(stored ? "Left-click to bring " + PetTexts.him(pet.sex()) + " out" : "Left-click to call " + PetTexts.him(pet.sex()),
                            NamedTextColor.YELLOW),
                    stored ? null : line("Right-click to send " + PetTexts.him(pet.sex()) + " to the kennel", NamedTextColor.YELLOW),
                    line("Shift-click to rename", NamedTextColor.YELLOW));
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(runtime.petKey(), PersistentDataType.STRING, pet.id().toString());
            item.setItemMeta(meta);
            inventory.setItem(slot++, item);
        }
        player.openInventory(inventory);
    }

    public void openTricks(org.bukkit.entity.Player player, Pet pet, String word) {
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.TRICK, pet.id(), word);
        Inventory inventory = Bukkit.createInventory(holder, 27, title("What does “" + word + "” mean?"));
        holder.inventory(inventory);
        frame(inventory);
        inventory.setItem(WORD_SLOT, named(Material.WRITABLE_BOOK, "New word: “" + word + "”", NamedTextColor.GOLD,
                line(pet.name() + " doesn't know this word yet.", NamedTextColor.GRAY),
                line("Pick the trick " + PetTexts.he(pet.sex()) + " should do", NamedTextColor.GRAY),
                line("whenever you say “" + word + "”.", NamedTextColor.GRAY)));
        TrainingSettings training = runtime.config().training();
        Trick[] tricks = Trick.values();
        for (int index = 0; index < tricks.length; index++) {
            inventory.setItem(TRICK_ROW + index, trickIcon(pet, tricks[index], word, training));
        }
        inventory.setItem(SKIP_SLOT, named(Material.BARRIER, "Not now", NamedTextColor.RED,
                line("Leave “" + word + "” unlinked", NamedTextColor.GRAY)));
        player.openInventory(inventory);
    }

    private static ItemStack trickIcon(Pet pet, Trick trick, String word, TrainingSettings training) {
        boolean learned = pet.progress(trick) >= training.learnedAt();
        ItemStack item = named(trickMaterial(trick), PetTexts.trickName(trick), learned ? NamedTextColor.GREEN : NamedTextColor.WHITE,
                line(PetTexts.trickDescription(trick), NamedTextColor.GRAY),
                Component.empty(),
                knownAs(pet, trick, training),
                Component.empty(),
                line("▶ Click to link “" + word + "”", NamedTextColor.YELLOW));
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(learned);
        meta.addItemFlags(ItemFlag.HIDE_DYE);
        item.setItemMeta(meta);
        return item;
    }

    private static Material trickMaterial(Trick trick) {
        return switch (trick) {
            case SIT -> Material.OAK_STAIRS;
            case COME -> Material.COMPASS;
            case STAY -> Material.ARMOR_STAND;
            case SPEAK -> Material.OAK_SIGN;
            case JUMP -> Material.LEATHER_BOOTS;
            case SPIN -> Material.WIND_CHARGE;
            case BEG -> Material.COOKIE;
            case PAW -> Material.RABBIT_FOOT;
        };
    }

    private static Component knownAs(Pet pet, Trick trick, TrainingSettings training) {
        List<String> words = new ArrayList<>();
        for (java.util.Map.Entry<String, Trick> entry : pet.words().entrySet()) {
            if (entry.getValue() == trick) {
                words.add("“" + entry.getKey() + "”");
            }
        }
        if (words.isEmpty()) {
            return line("Not taught yet", NamedTextColor.DARK_GRAY);
        }
        double progress = pet.progress(trick);
        Component taught = line("Taught as " + String.join(", ", words) + "  ", NamedTextColor.AQUA);
        if (progress >= training.learnedAt()) {
            return taught.append(line("✔ Learned", NamedTextColor.GREEN));
        }
        return taught.append(StatLook.bar(TrainingMath.percentLearned(progress, training), NamedTextColor.AQUA));
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

    private static ItemStack needIcon(Material material, Pet pet, Need need, Component hint, Component... extra) {
        double value = pet.need(need);
        List<Component> lore = new ArrayList<>();
        lore.add(StatLook.bar(value));
        lore.add(line(StatLook.state(need, value), StatLook.band(value)));
        for (Component component : extra) {
            if (component != null) {
                lore.add(component);
            }
        }
        lore.add(Component.empty());
        lore.add(hint);
        return named(material, PetTexts.needName(need), StatLook.theme(need), lore.toArray(new Component[0]));
    }

    private static Component title(String text) {
        return Component.text(text, NamedTextColor.GOLD);
    }

    private static Component line(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack named(Material material, String name, TextColor color, Component... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        List<Component> lines = new ArrayList<>();
        for (Component component : lore) {
            if (component != null) {
                lines.add(component.decoration(TextDecoration.ITALIC, false));
            }
        }
        if (!lines.isEmpty()) {
            meta.lore(lines);
        }
        item.setItemMeta(meta);
        return item;
    }
}
