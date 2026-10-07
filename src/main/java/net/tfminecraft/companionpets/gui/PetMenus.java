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
    public static final int TRICKS_PER_PAGE = 18;
    public static final int TRICKS_NEXT_SLOT = 26;
    public static final int NAME_SLOT = 13;
    public static final int BACK_SLOT = 36;
    public static final int TRICKS_BACK_SLOT = 18;
    public static final int CALL_SLOT = 38;
    public static final int STORE_SLOT = 42;
    public static final int TRICKS_SLOT = 40;
    public static final int RELEASE_SLOT = 44;

    public static int careSlot(int slot, boolean petHouseBack) {
        return petHouseBack || slot == BACK_SLOT ? slot : slot - 1;
    }
    private final PetRuntime runtime;

    public PetMenus(PetRuntime runtime) {
        this.runtime = runtime;
    }

    public void openCare(org.bukkit.entity.Player player, Pet pet) {
        openCare(player, pet, false);
    }

    public void openCare(org.bukkit.entity.Player player, Pet pet, boolean petHouseBack) {
        openCare(player, pet, petHouseBack, 0);
    }

    public void openCare(org.bukkit.entity.Player player, Pet pet, boolean petHouseBack, int petHousePage) {
        openCare(player, pet, petHouseBack, petHousePage, null);
    }
    public void openCare(org.bukkit.entity.Player player, Pet pet, boolean petHouseBack, int petHousePage, org.bukkit.Location house) {
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.CARE, pet.id(), null);
        holder.house(house);
        holder.petHouseBack(petHouseBack);
        holder.petHousePage(petHousePage);
        Inventory inventory = Bukkit.createInventory(holder, 45, title(pet.name()));
        holder.inventory(inventory);
        fillCare(player, inventory, pet);
        player.openInventory(inventory);
    }

    public void refreshCare(org.bukkit.entity.Player player, Pet pet) {
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder holder)
                || holder.kind() != MenuHolder.Kind.CARE
                || !pet.id().equals(holder.petId())) {
            return;
        }
        fillCare(player, holder.getInventory(), pet);
    }

    private void fillCare(org.bukkit.entity.Player player, Inventory inventory, Pet pet) {
        fillProfile(inventory, pet, pet.ownerId().equals(player.getUniqueId()),
                inventory.getHolder() instanceof MenuHolder holder && holder.petHouseBack());
    }

    public void fillInformation(Inventory inventory, Pet pet) {
        fillProfile(inventory, pet, false, true);
    }

    private void fillProfile(Inventory inventory, Pet pet, boolean management, boolean back) {
        if (inventory.getSize() < 45) {
            return;
        }
        frame(inventory);
        PetTypeDef type = runtime.config().type(pet.typeId());
        ItemStack egg = type == null ? new ItemStack(Material.BONE) : type.eggIcon();
        ItemStack food = type == null || type.foodIcon() == null ? new ItemStack(Material.COOKED_BEEF) : type.foodIcon().icon(Material.COOKED_BEEF);
        boolean sick = pet.illness() != Illness.NONE;

        inventory.setItem(4, named(egg, PetTexts.speciesName(pet.typeId()), NamedTextColor.WHITE,
                line("Species", NamedTextColor.GRAY)));
        inventory.setItem(11, named(Material.WHITE_DYE, "Sex", NamedTextColor.WHITE,
                line(PetTexts.sexName(pet.sex()), NamedTextColor.GRAY)));
        inventory.setItem(13, named(Material.NAME_TAG, pet.name(), NamedTextColor.WHITE,
                condition(pet),
                Component.empty(),
                management ? line("Rename " + PetTexts.him(pet.sex()) + " from your Pet House", NamedTextColor.DARK_GRAY) : null));
        inventory.setItem(15, named(Material.CLOCK, "Age", NamedTextColor.WHITE,
                line(PetTexts.age(pet.bornAt(), System.currentTimeMillis()), NamedTextColor.GRAY)));

        Component treat = itemList(type == null ? List.of() : type.treats(), "treats");
        // Lore lines stay short; Minecraft widens the whole tooltip to its longest line.
        inventory.setItem(20, needIcon(food, pet, Need.HUNGER, List.of(
                line("Feed " + PetTexts.him(pet.sex()) + ":", NamedTextColor.DARK_GRAY),
                line(" ", NamedTextColor.DARK_GRAY).append(foods(type)))));
        inventory.setItem(21, needIcon(new ItemStack(Material.SUNFLOWER), pet, Need.MOOD, List.of(
                line("Throw a toy, or pet " + PetTexts.him(pet.sex()) + " by hand", NamedTextColor.DARK_GRAY),
                line("Treats: ", NamedTextColor.DARK_GRAY).append(treat))));
        inventory.setItem(22, needIcon(new ItemStack(Material.BLAZE_POWDER), pet, Need.ENERGY, List.of(
                line("Rests while sitting or lying down", NamedTextColor.DARK_GRAY),
                line(PetTexts.He(pet.sex()) + " lies down when exhausted", NamedTextColor.DARK_GRAY))));
        List<ItemRef> brushes = type == null ? List.of() : type.brushes();
        ItemRef brush = brushes.isEmpty() ? null : brushes.getFirst();
        inventory.setItem(23, needIcon(brush == null ? new ItemStack(Material.BRUSH) : brush.icon(Material.BRUSH), pet, Need.CLEANLINESS, List.of(
                line("Brush " + PetTexts.him(pet.sex()) + " with:", NamedTextColor.DARK_GRAY),
                line(" ", NamedTextColor.DARK_GRAY).append(itemList(brushes, "a cleaning item")))));
        List<ItemRef> medicines = type == null ? List.of() : type.medicines();
        ItemRef medicine = medicines.isEmpty() ? ItemRef.vanilla(Material.HONEY_BOTTLE) : medicines.getFirst();
        inventory.setItem(24, needIcon(sick ? medicine.icon(Material.HONEY_BOTTLE) : new ItemStack(Material.GOLDEN_APPLE), pet, Need.HEALTH,
                sick
                        ? List.of(line("Medicine gives a health boost:", NamedTextColor.DARK_GRAY),
                                line(" ", NamedTextColor.DARK_GRAY).append(itemList(medicines, "medicine")))
                        : List.of(line("Food and brushing restore health", NamedTextColor.DARK_GRAY)),
                line("Recovers over time while:", NamedTextColor.DARK_GRAY),
                line(" Food and cleanliness >= 25", NamedTextColor.DARK_GRAY),
                line(" Energy >= 25, or resting", NamedTextColor.DARK_GRAY),
                line(" (low mood is OK)", NamedTextColor.DARK_GRAY),
                sick ? line(PetTexts.illness(pet.name(), pet.sex(), pet.illness()), NamedTextColor.RED) : null));

        if (back) MenuNavigation.back(inventory, management ? "Return to the Pet House list" : "Return to this player's pets");
        inventory.setItem(management ? careSlot(TRICKS_SLOT, back) : TRICKS_SLOT, action(Material.BOOK, "Tricks",
                line("See what " + pet.name() + " has learned", NamedTextColor.GRAY)));
        if (management) {
            boolean stored = pet.stored();
            inventory.setItem(careSlot(CALL_SLOT, back), action(stored ? Material.LEAD : Material.COMPASS,
                    stored ? "Bring out" : "Call",
                    line(stored ? "Bring " + PetTexts.him(pet.sex()) + " out beside you" : "Call " + PetTexts.him(pet.sex()) + " to your side", NamedTextColor.GRAY)));
            if (!stored) inventory.setItem(careSlot(STORE_SLOT, back), action(Material.BARREL, "Send to Pet House",
                    line("Take " + PetTexts.him(pet.sex()) + " out of the world", NamedTextColor.GRAY)));
            else inventory.setItem(careSlot(STORE_SLOT, back), named(Material.GRAY_DYE, "In Pet House", NamedTextColor.GRAY,
                    line(pet.name() + " is already resting here", NamedTextColor.GRAY)));
            inventory.setItem(careSlot(RELEASE_SLOT, back), action(Material.BARRIER, "Release forever",
                    line(pet.name() + " leaves for good and cannot come back", NamedTextColor.GRAY)));
        }
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
        openKennel(player, 0);
    }

    public void openKennel(org.bukkit.entity.Player player, int requestedPage) {
        openKennel(player, requestedPage, null);
    }
    public void openKennel(org.bukkit.entity.Player player, int requestedPage, org.bukkit.Location house) {
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.KENNEL, null, null);
        holder.house(house);
        Inventory inventory = Bukkit.createInventory(holder, 54, title("Pet House"));
        holder.inventory(inventory);
        frame(inventory);
        int slot = 0;
        List<Pet> pets = runtime.store().of(player.getUniqueId()).stream()
                .sorted(java.util.Comparator.comparing((Pet p) -> p.name(), String.CASE_INSENSITIVE_ORDER).thenComparing(Pet::id)).toList();
        int page = Math.max(0, Math.min(requestedPage, Math.max(0, (pets.size() - 1) / 45)));
        holder.page(page);
        holder.pages(MenuNavigation.pages(inventory, page, pets.size(), null));
        for (Pet pet : pets.subList(page * 45, Math.min(pets.size(), (page + 1) * 45))) {
            PetTypeDef type = runtime.config().type(pet.typeId());
            ItemStack icon = type == null ? new ItemStack(Material.BONE) : type.eggIcon();
            boolean stored = pet.stored();
            ItemStack item = named(icon, pet.name(), NamedTextColor.GOLD,
                    line(PetTexts.sexName(pet.sex()) + " " + PetTexts.speciesName(pet.typeId()).toLowerCase(java.util.Locale.ROOT),
                            NamedTextColor.GRAY),
                    line(stored ? "Resting in the Pet House" : "Out and about", stored ? NamedTextColor.DARK_GRAY : NamedTextColor.GREEN),
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
        inheritPetHouseNavigation(player, holder);
        Inventory inventory = Bukkit.createInventory(holder, 27, title(pet.name() + "'s tricks"));
        holder.inventory(inventory);
        frame(inventory);
        TrainingSettings training = runtime.config().training();
        Trick[] tricks = pageTricks(holder, pet, page);
        for (int index = 0; index < tricks.length; index++) {
            if (allowsTrick(pet, tricks[index]))
                inventory.setItem(index, learnedIcon(pet, tricks[index], training));
        }
        player.openInventory(inventory);
    }

    public int fillLearnedInformation(Inventory inventory, Pet pet, int requestedPage) {
        MenuHolder display = new MenuHolder(MenuHolder.Kind.LEARNED, pet.id(), null);
        display.inventory(inventory);
        frame(inventory);
        Trick[] tricks = pageTricks(display, pet, requestedPage);
        for (int index = 0; index < tricks.length; index++)
            inventory.setItem(index, learnedIcon(pet, tricks[index], runtime.config().training()));
        return display.page();
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
        inheritPetHouseNavigation(player, holder);
        Inventory inventory = Bukkit.createInventory(holder, 27, title("Choose a trick"));
        holder.inventory(inventory);
        frame(inventory);
        TrainingSettings training = runtime.config().training();
        Trick[] tricks = pageTricks(holder, pet, page);
        for (int index = 0; index < tricks.length; index++) {
            if (allowsTrick(pet, tricks[index]))
                inventory.setItem(index, trickIcon(pet, tricks[index], word, training));
        }
        player.openInventory(inventory);
    }

    private void inheritPetHouseNavigation(org.bukkit.entity.Player player, MenuHolder holder) {
        Inventory previousInventory = player.getOpenInventory().getTopInventory();
        if (previousInventory != null && previousInventory.getHolder() instanceof MenuHolder previous
                && holder.petId().equals(previous.petId())) {
            holder.petHouseBack(previous.petHouseBack());
            holder.petHousePage(previous.petHousePage());
            holder.house(previous.house());
        }
    }

    private boolean allowsTrick(Pet pet, Trick trick) {
        return net.tfminecraft.companionpets.training.TrickAvailability.allows(runtime, pet, trick);
    }

    private Trick[] pageTricks(MenuHolder holder, Pet pet, int requested) {
        List<Trick> available = net.tfminecraft.companionpets.training.TrickAvailability.ordered(runtime, pet,
                runtime.config().tricks().stream().filter(t -> allowsTrick(pet, t)).toList());
        int pages = Math.max(1, (available.size() + TRICKS_PER_PAGE - 1) / TRICKS_PER_PAGE);
        int page = Math.max(0, Math.min(requested, pages - 1));
        holder.page(page);
        holder.pages(MenuNavigation.pages(holder.getInventory(), page, available.size(), "Return to " + pet.name()));
        List<Trick> subset = available.subList(page * TRICKS_PER_PAGE, Math.min(available.size(), (page + 1) * TRICKS_PER_PAGE));
        for (int i = 0; i < subset.size(); i++) holder.trick(i, subset.get(i));
        if (subset.isEmpty()) holder.getInventory().setItem(0, named(Material.PAPER, "No available tricks", NamedTextColor.GRAY));
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
            default -> Material.NETHER_STAR;
            case SIT -> Material.OAK_STAIRS;
            case FOLLOW -> Material.COMPASS;
            case COME -> Material.LEAD;
            case STAY -> Material.ARMOR_STAND;
            case SPEAK -> Material.OAK_SIGN;
            case JUMP -> Material.LEATHER_BOOTS;
            case LAY -> Material.RED_BED;
            case PAW -> Material.RABBIT_FOOT;
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

    private static void frame(Inventory inventory) { MenuBackground.fill(inventory); }

    private static ItemStack needIcon(ItemStack material, Pet pet, Need need, List<Component> hint, Component... extra) {
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
        lore.addAll(hint);
        return named(material, PetTexts.needName(need), NamedTextColor.WHITE, lore.toArray(new Component[0]));
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
}
