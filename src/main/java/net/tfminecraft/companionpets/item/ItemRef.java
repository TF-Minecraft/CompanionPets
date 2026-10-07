package net.tfminecraft.companionpets.item;

import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** A configured identity, independent of an item's underlying material or model. */
public record ItemRef(Kind kind, String id, String type) {
    public enum Kind { VANILLA, MMOITEMS, ITEMSADDER }

    public static ItemRef parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Item must not be empty");
        String value = raw.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        // Archaeo's provider:namespace:id / provider:TYPE:id notation.
        int colon = value.indexOf(':');
        if (colon > 0 && !lower.startsWith("ia.")) {
            String prefix = lower.substring(0, colon);
            String rest = value.substring(colon + 1).trim();
            if (prefix.equals("mmoitems") || prefix.equals("mi")) {
                String[] parts = rest.split(":", -1);
                if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9_-]+") || !parts[1].matches("[A-Za-z0-9_-]+"))
                    throw new IllegalArgumentException("MMOItems item must use mmoitems:TYPE:id: " + raw);
                return new ItemRef(Kind.MMOITEMS, parts[1].toUpperCase(Locale.ROOT), parts[0].toUpperCase(Locale.ROOT));
            }
            if (prefix.equals("minecraft")) return parse(rest);
            String namespaced = prefix.equals("itemsadder") || prefix.equals("ia") ? rest : value;
            if (!namespaced.toLowerCase(Locale.ROOT).matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw new IllegalArgumentException("ItemsAdder item must use itemsadder:namespace:id: " + raw);
            return new ItemRef(Kind.ITEMSADDER, namespaced.toLowerCase(Locale.ROOT), null);
        }
        if (lower.startsWith("m.")) {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 3 || !parts[1].matches("[A-Za-z0-9_-]+") || !parts[2].matches("[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException("MMOItems item must use m.type.id: " + raw);
            return new ItemRef(Kind.MMOITEMS, parts[2].toUpperCase(Locale.ROOT), parts[1].toUpperCase(Locale.ROOT));
        }
        if (lower.startsWith("ia.")) {
            String id = value.substring(3);
            if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw new IllegalArgumentException("ItemsAdder item must use ia.namespace:item_id: " + raw);
            return new ItemRef(Kind.ITEMSADDER, id, null);
        }
        String materialName = lower.startsWith("v.") ? value.substring(2) : value;
        Material material = Material.matchMaterial(materialName.toUpperCase(Locale.ROOT));
        if (material == null || !material.isItem() || material.isAir())
            throw new IllegalArgumentException("Unknown item material: " + raw);
        return vanilla(material);
    }

    public static ItemRef vanilla(Material material) {
        return new ItemRef(Kind.VANILLA, material.name(), null);
    }

    /** Like Archaeo, recover tokens accidentally parsed as one-key YAML maps. */
    public static String yamlToken(Object value) {
        if (value instanceof String text) return text.trim();
        if (value instanceof org.bukkit.configuration.ConfigurationSection section) return yamlToken(section.getValues(false));
        if (value instanceof Map<?, ?> map && map.size() == 1) {
            var entry = map.entrySet().iterator().next();
            String rest = yamlToken(entry.getValue());
            return entry.getKey() + (rest == null || rest.isBlank() ? "" : ":" + rest);
        }
        return null;
    }

    public String configToken() {
        return switch (kind) {
            case VANILLA -> id;
            case MMOITEMS -> "mmoitems:" + type + ":" + id;
            case ITEMSADDER -> "itemsadder:" + id;
        };
    }

    public Material material() { return kind == Kind.VANILLA ? Material.valueOf(id) : null; }

    /** Preserve existing saved vanilla favorite-toy IDs. */
    public String key() {
        return switch (kind) {
            case VANILLA -> id;
            case MMOITEMS -> "m." + type.toLowerCase(Locale.ROOT) + "." + id.toLowerCase(Locale.ROOT);
            case ITEMSADDER -> "ia." + id;
        };
    }

    /** One provider lookup for a stack; null for empty stacks, which never match a configured item. */
    public static ItemIdentity identify(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0 ? null : ItemBridge.identity(item);
    }

    public boolean matches(ItemStack item) {
        return matches(identify(item));
    }

    public boolean matches(ItemIdentity item) {
        if (item == null || !item.known()) return false;
        return switch (kind) {
            case VANILLA -> item.material() == material() && item.mmoType() == null && item.itemsAdderId() == null;
            case MMOITEMS -> type.equalsIgnoreCase(item.mmoType()) && id.equalsIgnoreCase(item.mmoId());
            case ITEMSADDER -> id.equalsIgnoreCase(item.itemsAdderId());
        };
    }

    /** A display copy for menus and particles; real items handed to players come from {@link #create()}. */
    public ItemStack icon(Material fallback) {
        ItemStack item = ItemBridge.display(this);
        return item == null ? new ItemStack(fallback) : item;
    }

    public ItemStack create() { return ItemBridge.create(this); }

    public Component displayName() {
        ItemStack item = ItemBridge.display(this);
        if (item == null) return Component.text(id.replace('_', ' ').toLowerCase(Locale.ROOT));
        var meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() ? meta.displayName() : Component.translatable(item.getType().translationKey());
    }

    public String name() {
        ItemStack item = ItemBridge.display(this);
        if (item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName())
            return PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
        return id.replace('_', ' ').toLowerCase(Locale.ROOT);
    }
}
