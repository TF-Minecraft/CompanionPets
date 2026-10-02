package net.tfminecraft.companionpets.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import net.tfminecraft.companionpets.item.ItemRef;

/** Effective interaction lists. Each present pet list replaces its global list. */
public record PetItems(Map<ItemRef, Double> foods, List<ItemRef> treats,
        List<ItemRef> medicines, List<ItemRef> brushes, List<ItemRef> toys) {
    public PetItems {
        foods = Collections.unmodifiableMap(new LinkedHashMap<>(foods));
        treats = List.copyOf(treats);
        medicines = List.copyOf(medicines);
        brushes = List.copyOf(brushes);
        toys = List.copyOf(toys);
    }

    public static PetItems defaults() {
        return new PetItems(Map.of(ItemRef.vanilla(Material.COOKED_BEEF), 55.0),
                List.of(ItemRef.vanilla(Material.COOKED_BEEF)), List.of(ItemRef.vanilla(Material.HONEY_BOTTLE)),
                List.of(ItemRef.vanilla(Material.BRUSH)), List.of(ItemRef.vanilla(Material.STICK)));
    }

    public static PetItems read(ConfigurationSection section, PetItems inherited, Logger logger) {
        return new PetItems(has(section, "foods") ? CompanionConfig.readFoods(section, logger) : inherited.foods(),
                list(section, "treats", inherited.treats(), logger),
                list(section, "medicines", inherited.medicines(), logger),
                list(section, "brushes", inherited.brushes(), logger),
                list(section, "toys", inherited.toys(), logger));
    }

    public static PetItems forPet(ConfigurationSection pet, PetItems global, Logger logger) {
        ConfigurationSection local = pet.getConfigurationSection("items");
        PetItems current = read(local, global, logger);
        ConfigurationSection care = pet.getConfigurationSection("care");
        // Legacy settings remain per-category overrides, without appending the global entries.
        Map<ItemRef, Double> foods = !has(local, "foods") && has(care, "foods")
                ? CompanionConfig.readFoods(care, logger) : current.foods();
        List<ItemRef> treats = !has(local, "treats") && has(care, "favorite")
                ? singleton(care.get("favorite"), logger) : current.treats();
        List<ItemRef> medicines = !has(local, "medicines") && has(care, "medicine")
                ? singleton(care.get("medicine"), logger) : current.medicines();
        List<ItemRef> toys = !has(local, "toys") && pet.contains("toys")
                ? list(pet, "toys", List.of(), logger) : current.toys();
        return new PetItems(foods, treats, medicines, current.brushes(), toys);
    }

    static boolean has(ConfigurationSection section, String key) {
        return section != null && section.contains(key);
    }

    static List<ItemRef> singleton(Object raw, Logger logger) {
        ItemRef item = CompanionConfig.parseItem(ItemRef.yamlToken(raw), logger);
        return item == null ? List.of() : List.of(item);
    }

    static List<ItemRef> list(ConfigurationSection section, String key, List<ItemRef> inherited, Logger logger) {
        if (!has(section, key)) return inherited;
        if (!section.isList(key)) {
            logger.warning(section.getCurrentPath() + "." + key + " must be a list; disabling this category");
            return List.of();
        }
        List<ItemRef> items = new ArrayList<>();
        for (Object raw : section.getList(key, List.of())) {
            String token = ItemRef.yamlToken(raw);
            if (token == null || token.isBlank()) {
                logger.warning("Invalid item in " + section.getCurrentPath() + "." + key);
                continue;
            }
            ItemRef ref = CompanionConfig.parseItem(token, logger);
            if (ref != null && !items.contains(ref)) items.add(ref);
        }
        return items;
    }

    public boolean isTreat(ItemStack item) { return treats.stream().anyMatch(ref -> ref.matches(item)); }
    public boolean isMedicine(ItemStack item) { return medicines.stream().anyMatch(ref -> ref.matches(item)); }
    public boolean isBrush(ItemStack item) { return brushes.stream().anyMatch(ref -> ref.matches(item)); }
    public ItemRef toy(ItemStack item) { return toys.stream().filter(ref -> ref.matches(item)).findFirst().orElse(null); }

    public Double foodGain(ItemStack item) {
        Double gain = foods.entrySet().stream().filter(entry -> entry.getKey().matches(item))
                .map(Map.Entry::getValue).findFirst().orElse(null);
        // Retain the former favourite-food behaviour when a hungry pet eats a treat.
        return gain != null ? gain : isTreat(item) ? 30.0 : null;
    }

    public ItemRef foodIcon() { return foods.keySet().stream().findFirst().orElse(null); }
}
