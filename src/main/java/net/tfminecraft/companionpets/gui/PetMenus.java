package net.tfminecraft.companionpets.gui;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import net.tfminecraft.companionpets.item.ItemRef;
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
import net.tfminecraft.companionpets.pet.Trick;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.companionpets.text.PetTexts;
import net.tfminecraft.companionpets.training.TrainingMath;
import net.tfminecraft.companionpets.training.TrainingSettings;

public final class PetMenus {
    private static final int TRICK_ROW = 9;
    public static final int NAME_SLOT = 13;
    public static final int BACK_SLOT = 36;
    public static final int TRICKS_BACK_SLOT = 22;
    public static final int CALL_SLOT = 38;
    public static final int STORE_SLOT = 40;
    public static final int TRICKS_SLOT = 42;
    public static final int RELEASE_SLOT = 44;
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
        ItemStack egg = type == null ? new ItemStack(Material.BONE) : type.eggIcon();
        ItemStack food = type == null || type.foodIcon() == null ? new ItemStack(Material.COOKED_BEEF) : type.foodIcon().icon(Material.COOKED_BEEF);
        boolean sick = pet.illness() != Illness.NONE;

        inventory.setItem(37, null);
        inventory.setItem(39, null);
        inventory.setItem(41, null);
        inventory.setItem(43, null);

        inventory.setItem(4, named(egg, PetTexts.speciesName(pet.typeId()), NamedTextColor.WHITE,
                line("Species", NamedTextColor.GRAY)));
        inventory.setItem(11, named(Material.PLAYER_HEAD, PetTexts.sexName(pet.sex()), NamedTextColor.WHITE));
        inventory.setItem(13, named(Material.NAME_TAG, pet.name(), NamedTextColor.WHITE,
                condition(pet),
                Component.empty(),
                line("Rename " + PetTexts.him(pet.sex()) + " from your shelter", NamedTextColor.DARK_GRAY)));
        inventory.setItem(15, named(Material.CLOCK, "Age", NamedTextColor.WHITE,
                line(PetTexts.age(pet.bornAt(), System.currentTimeMillis()), NamedTextColor.GRAY)));

        Component treat = itemList(type == null ? List.of() : type.treats(), "treats");
        inventory.setItem(20, needIcon(food, pet, Need.HUNGER, line("Feed ", NamedTextColor.DARK_GRAY).append(foods(type))));
        inventory.setItem(21, needIcon(Material.SUNFLOWER, pet, Need.MOOD,
                line("Throw a toy, or pet " + PetTexts.him(pet.sex()) + " with an empty hand. Favorite: ", NamedTextColor.DARK_GRAY).append(treat)));
        inventory.setItem(22, needIcon(Material.BLAZE_POWDER, pet, Need.ENERGY,
                line("Teach " + PetTexts.him(pet.sex()) + " to rest, or " + PetTexts.he(pet.sex()) + " lies down when exhausted", NamedTextColor.DARK_GRAY)));
        List<ItemRef> brushes = type == null ? List.of() : type.brushes();
        ItemRef brush = brushes.isEmpty() ? null : brushes.getFirst();
        inventory.setItem(23, needIcon(brush == null ? new ItemStack(Material.BRUSH) : brush.icon(Material.BRUSH), pet, Need.CLEANLINESS,
                line("Use ", NamedTextColor.DARK_GRAY).append(itemList(brushes, "a cleaning item"))
                        .append(line(" on " + PetTexts.him(pet.sex()), NamedTextColor.DARK_GRAY))));
        List<ItemRef> medicines = type == null ? List.of() : type.medicines();
        ItemRef medicine = medicines.isEmpty() ? ItemRef.vanilla(Material.HONEY_BOTTLE) : medicines.getFirst();
        inventory.setItem(24, needIcon(sick ? medicine.icon(Material.HONEY_BOTTLE) : new ItemStack(Material.GOLDEN_APPLE), pet, Need.HEALTH,
                sick
                        ? line("Cure with ", NamedTextColor.DARK_GRAY).append(itemList(medicines, "medicine"))
                        : line("Keep " + PetTexts.his(pet.sex()) + " needs up to stay healthy", NamedTextColor.DARK_GRAY),
                sick ? line(PetTexts.illness(pet.name(), pet.sex(), pet.illness()), NamedTextColor.RED) : null));

        boolean stored = pet.stored();
        inventory.setItem(BACK_SLOT, action(Material.ARROW, "Back",
                line("Return to the shelter list", NamedTextColor.GRAY)));
        inventory.setItem(CALL_SLOT, action(stored ? Material.LEAD : Material.COMPASS,
                stored ? "Bring out" : "Call",
                line(stored ? "Bring " + PetTexts.him(pet.sex()) + " out beside you" : "Call " + PetTexts.him(pet.sex()) + " to your side",
                        NamedTextColor.GRAY)));
        if (!stored) {
            inventory.setItem(STORE_SLOT, action(Material.BARREL, "Send to shelter",
                    line("Take " + PetTexts.him(pet.sex()) + " out of the world", NamedTextColor.GRAY)));
        }
        inventory.setItem(TRICKS_SLOT, action(Material.BOOK, "Tricks",
                line("See what " + pet.name() + " has learned", NamedTextColor.GRAY)));
        inventory.setItem(RELEASE_SLOT, action(Material.BARRIER, "Release forever",
                line(pet.name() + " leaves for good and cannot come back", NamedTextColor.GRAY)));

        inventory.setItem(30, named(Material.LEAD, "Bond", NamedTextColor.WHITE,
                StatLook.bar(pet.bond(), StatLook.BOND),
                line(StatLook.bondState(pet.bond()), StatLook.BOND),
                Component.empty(),
                line("Grows while you spend time together", NamedTextColor.DARK_GRAY)));
        inventory.setItem(31, named(Material.AMETHYST_SHARD, "Personality", NamedTextColor.WHITE,
                line(pet.personality().label(), NamedTextColor.AQUA),
                line(pet.personality().description(), NamedTextColor.GRAY)));
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

    private static Component item(ItemRef ref) {
        return ref.displayName().color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static Component foods(PetTypeDef type) {
        return itemList(type == null ? List.of() : List.copyOf(type.foods().keySet()), "food");
    }

    private static ItemStack toyIcon(Pet pet) {
        ItemRef toy = null;
        try { if (pet.favoriteToy() != null) toy = ItemRef.parse(pet.favoriteToy()); }
        catch (IllegalArgumentException ignored) { /* Old/removed favourites are reconciled on the next tick. */ }
        if (toy == null) {
            return named(Material.BARRIER, "No favorite toy yet", NamedTextColor.WHITE);
        }
        return named(toy.icon(Material.BONE), "Favorite Toy", NamedTextColor.WHITE, item(toy));
    }

    public void openKennel(org.bukkit.entity.Player player) {
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.KENNEL, null, null);
        Inventory inventory = Bukkit.createInventory(holder, 27, title("Shelter"));
        holder.inventory(inventory);
        frame(inventory);
        int slot = 0;
        for (Pet pet : runtime.store().of(player.getUniqueId())) {
            if (slot >= inventory.getSize()) {
                break;
            }
            PetTypeDef type = runtime.config().type(pet.typeId());
            ItemStack icon = type == null ? new ItemStack(Material.BONE) : type.eggIcon();
            boolean stored = pet.stored();
            ItemStack item = named(icon, pet.name(), NamedTextColor.GOLD,
                    line(PetTexts.sexName(pet.sex()) + " " + PetTexts.speciesName(pet.typeId()).toLowerCase(java.util.Locale.ROOT),
                            NamedTextColor.GRAY),
                    line(stored ? "Resting in the shelter" : "Out and about", stored ? NamedTextColor.DARK_GRAY : NamedTextColor.GREEN),
                    line("Energy ", StatLook.theme(Need.ENERGY)).append(StatLook.bar(pet.need(Need.ENERGY))),
                    Component.empty(),
                    line("Click to open", NamedTextColor.YELLOW));
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(runtime.petKey(), PersistentDataType.STRING, pet.id().toString());
            item.setItemMeta(meta);
            inventory.setItem(slot++, item);
        }
        player.openInventory(inventory);
    }

    public void openLearned(org.bukkit.entity.Player player, Pet pet) {
        openLearned(player, pet, 0);
    }

    public void openLearned(org.bukkit.entity.Player player, Pet pet, int page) {
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.LEARNED, pet.id(), null);
        Inventory inventory = Bukkit.createInventory(holder, 27, title(pet.name() + "'s tricks"));
        holder.inventory(inventory);
        frame(inventory);
        TrainingSettings training = runtime.config().training();
        Trick[] tricks = pageTricks(holder, pet, page);
        for (int index = 0; index < tricks.length; index++) {
            if (allowsTrick(pet, tricks[index]))
                inventory.setItem(TRICK_ROW + index, learnedIcon(pet, tricks[index], training));
        }
        inventory.setItem(TRICKS_BACK_SLOT, named(Material.ARROW, "Back", NamedTextColor.YELLOW,
                line("Return to " + pet.name(), NamedTextColor.GRAY)));
        player.openInventory(inventory);
    }

    private ItemStack learnedIcon(Pet pet, Trick trick, TrainingSettings training) {
        boolean learned = pet.progress(trick) >= training.learnedAt();
        List<String> words = new ArrayList<>();
        for (java.util.Map.Entry<String, Trick> entry : pet.words().entrySet()) {
            if (entry.getValue().equals(trick)) {
                words.add("“" + entry.getKey() + "”");
            }
        }
        ItemStack item = named(trickMaterial(trick), net.tfminecraft.companionpets.training.TrickAvailability.name(runtime, trick), learned ? NamedTextColor.GREEN : NamedTextColor.GRAY,
                line(PetTexts.trickDescription(trick), NamedTextColor.GRAY),
                Component.empty(),
                words.isEmpty()
                        ? line("Not taught yet", NamedTextColor.DARK_GRAY)
                        : line("Say " + String.join(", ", words), NamedTextColor.AQUA),
                learned
                        ? line("Learned", NamedTextColor.GREEN)
                        : StatLook.bar(TrainingMath.percentLearned(pet.progress(trick), training), NamedTextColor.AQUA));
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(learned);
        meta.addItemFlags(ItemFlag.HIDE_DYE);
        item.setItemMeta(meta);
        return item;
    }

    public void openTricks(org.bukkit.entity.Player player, Pet pet, String word) {
        openTricks(player, pet, word, 0);
    }

    public void openTricks(org.bukkit.entity.Player player, Pet pet, String word, int page) {
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.TRICK, pet.id(), word);
        Inventory inventory = Bukkit.createInventory(holder, 27, title("Choose a trick"));
        holder.inventory(inventory);
        frame(inventory);
        TrainingSettings training = runtime.config().training();
        Trick[] tricks = pageTricks(holder, pet, page);
        for (int index = 0; index < tricks.length; index++) {
            if (allowsTrick(pet, tricks[index]))
                inventory.setItem(TRICK_ROW + index, trickIcon(pet, tricks[index], word, training));
        }
        player.openInventory(inventory);
    }

    private boolean allowsTrick(Pet pet, Trick trick) {
        return net.tfminecraft.companionpets.training.TrickAvailability.allows(runtime, pet, trick);
    }

    private Trick[] pageTricks(MenuHolder holder, Pet pet, int requested) {
        List<Trick> available = runtime.config().tricks().stream().filter(t -> allowsTrick(pet, t)).toList();
        int page = Math.max(0, Math.min(requested, Math.max(0, (available.size() - 1) / 9)));
        holder.page(page);
        if (page > 0) holder.getInventory().setItem(18, named(Material.ARROW, "Previous page", NamedTextColor.YELLOW));
        if ((page + 1) * 9 < available.size()) holder.getInventory().setItem(26, named(Material.ARROW, "Next page", NamedTextColor.YELLOW));
        List<Trick> subset = available.subList(page * 9, Math.min(available.size(), (page + 1) * 9));
        for (int i = 0; i < subset.size(); i++) holder.trick(TRICK_ROW + i, subset.get(i));
        if (subset.isEmpty()) holder.getInventory().setItem(13, named(Material.PAPER, "No available tricks", NamedTextColor.GRAY));
        return subset.toArray(Trick[]::new);
    }

    private ItemStack trickIcon(Pet pet, Trick trick, String word, TrainingSettings training) {
        boolean learned = pet.progress(trick) >= training.learnedAt();
        ItemStack item = named(trickMaterial(trick), net.tfminecraft.companionpets.training.TrickAvailability.name(runtime, trick), learned ? NamedTextColor.GREEN : NamedTextColor.WHITE,
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
        return switch (trick.kind()) {
            case CUSTOM -> Material.NETHER_STAR;
            case SIT -> Material.OAK_STAIRS;
            case COME -> Material.COMPASS;
            case STAY -> Material.ARMOR_STAND;
            case SPEAK -> Material.OAK_SIGN;
            case JUMP -> Material.LEATHER_BOOTS;
            case SPIN -> Material.WIND_CHARGE;
            case SLEEP -> Material.RED_BED;
            case PAW -> Material.RABBIT_FOOT;
            case BEG -> Material.COOKIE;
        };
    }

    private static Component knownAs(Pet pet, Trick trick, TrainingSettings training) {
        List<String> words = new ArrayList<>();
        for (java.util.Map.Entry<String, Trick> entry : pet.words().entrySet()) {
            if (entry.getValue().equals(trick)) {
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
        return needIcon(new ItemStack(material), pet, need, hint, extra);
    }

    private static ItemStack action(Material material, String name, Component... lore) {
        ItemStack item = named(material, name, NamedTextColor.WHITE, lore);
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    private static Component title(String text) {
        return Component.text(text, NamedTextColor.GOLD);
    }

    private static Component line(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack named(Material material, String name, TextColor color, Component... lore) {
        return named(new ItemStack(material), name, color, lore);
    }

    private static Component itemList(List<ItemRef> items, String fallback) {
        if (items.isEmpty()) return line(fallback, NamedTextColor.GRAY);
        Component joined = Component.empty();
        int index = 0;
        for (ItemRef food : items) {
            if (index > 0) {
                joined = joined.append(line(index == items.size() - 1 ? " or " : ", ", NamedTextColor.DARK_GRAY));
            }
            joined = joined.append(item(food));
            index++;
        }
        return joined;
    }

    private static ItemStack named(ItemStack original, String name, TextColor color, Component... lore) {
        ItemStack item = original.clone();
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

    private static ItemStack needIcon(ItemStack material, Pet pet, Need need, Component hint, Component... extra) {
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
        return named(material, PetTexts.needName(need), NamedTextColor.WHITE, lore.toArray(new Component[0]));
    }
}
