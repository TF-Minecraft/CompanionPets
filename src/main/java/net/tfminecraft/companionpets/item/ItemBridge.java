package net.tfminecraft.companionpets.item;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Optional integrations loaded from their owning plugins, without bundling their APIs. */
public final class ItemBridge {
    private ItemBridge() { }
    record Identity(Material material, String mmoType, String mmoId, String itemsAdderId, boolean known) {}
    private static final Set<String> warned = new HashSet<>();
    private static Mmo mmo;
    private static Ia ia;
    private static Plugin mmoProvider, iaProvider;
    private static final Map<ItemRef, Template> templates = new HashMap<>();
    private static final long RETRY_MILLIS = 30_000;
    private record Template(ItemStack item, Component displayName, String name, long retryAt) { }

    private record Mmo(Method type, Method id, Method getType, Method getItem, Plugin plugin) {}
    private record Ia(Method identify, Method id, Method create, Method stack, Plugin plugin) {}

    static Identity identity(ItemStack item) {
        String mmoType = null, mmoId = null, iaId = null;
        try {
            refreshProviders();
            Mmo api = mmo();
            if (api != null) {
                String value = (String) api.type().invoke(null, item);
                if (value != null && !value.isBlank()) {
                    mmoType = value;
                    mmoId = (String) api.id().invoke(null, item);
                }
            }
            Ia iaApi = ia();
            if (iaApi != null) {
                Object custom = iaApi.identify().invoke(null, item);
                if (custom != null) iaId = (String) iaApi.id().invoke(custom);
            }
            return new Identity(item.getType(), mmoType, mmoId, iaId, true);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            warn("identify", "Could not identify custom item; refusing to match it", ex);
            return new Identity(item.getType(), null, null, null, false);
        }
    }

    static ItemStack create(ItemRef ref) {
        ItemStack item = template(ref).item();
        return item == null ? null : item.clone();
    }

    static Component displayName(ItemRef ref) { return template(ref).displayName(); }
    static String name(ItemRef ref) { return template(ref).name(); }

    public static void clearCache() {
        templates.clear();
        warned.clear();
    }

    private static void refreshProviders() {
        Plugin nextMmo = enabled("MMOItems"), nextIa = enabled("ItemsAdder");
        if (mmoProvider != nextMmo || iaProvider != nextIa) {
            templates.clear();
            mmoProvider = nextMmo;
            iaProvider = nextIa;
        }
    }

    private static Template template(ItemRef ref) {
        refreshProviders();
        long now = System.currentTimeMillis();
        Template cached = templates.get(ref);
        if (cached != null && (cached.item() != null || now < cached.retryAt())) return cached;
        ItemStack item = build(ref);
        String name = ref.id().replace('_', ' ').toLowerCase(Locale.ROOT);
        Component displayName = Component.text(name);
        if (item != null) {
            var meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                displayName = meta.displayName();
                name = PlainTextComponentSerializer.plainText().serialize(displayName);
            } else displayName = Component.translatable(item.getType().translationKey());
        }
        Template template = new Template(item, displayName, name, now + RETRY_MILLIS);
        templates.put(ref, template);
        return template;
    }

    private static ItemStack build(ItemRef ref) {
        if (ref.kind() == ItemRef.Kind.VANILLA) return new ItemStack(ref.material());
        try {
            ItemStack item = null;
            if (ref.kind() == ItemRef.Kind.MMOITEMS) {
                Mmo api = mmo();
                if (api != null) {
                    Object type = api.getType().invoke(null, ref.type());
                    if (type != null) item = (ItemStack) api.getItem().invoke(api.plugin(), type, ref.id());
                }
            } else {
                Ia api = ia();
                if (api != null) {
                    Object custom = api.create().invoke(null, ref.id());
                    if (custom != null) item = (ItemStack) api.stack().invoke(custom);
                }
            }
            // Never silently replace an unknown custom item with a provider's fallback item.
            if (item != null && ref.matches(item)) {
                ItemStack copy = item.clone();
                copy.setAmount(1);
                return copy;
            }
            warn(ref.key(), "Item " + ref.key() + " is unavailable; check its provider and ID", null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            warn(ref.key(), "Could not create icon for " + ref.key(), ex);
        }
        return null;
    }

    private static Mmo mmo() throws ReflectiveOperationException {
        Plugin plugin = enabled("MMOItems");
        if (plugin == null) return null;
        if (mmo == null || mmo.plugin() != plugin) {
            Class<?> type = Class.forName("net.Indyuce.mmoitems.api.Type", true, plugin.getClass().getClassLoader());
            mmo = new Mmo(plugin.getClass().getMethod("getTypeName", ItemStack.class),
                    plugin.getClass().getMethod("getID", ItemStack.class), type.getMethod("get", String.class),
                    plugin.getClass().getMethod("getItem", type, String.class), plugin);
        }
        return mmo;
    }

    private static Ia ia() throws ReflectiveOperationException {
        Plugin plugin = enabled("ItemsAdder");
        if (plugin == null) return null;
        if (ia == null || ia.plugin() != plugin) {
            Class<?> api = Class.forName("dev.lone.itemsadder.api.CustomStack", true, plugin.getClass().getClassLoader());
            ia = new Ia(api.getMethod("byItemStack", ItemStack.class), api.getMethod("getNamespacedID"),
                    api.getMethod("getInstance", String.class), api.getMethod("getItemStack"), plugin);
        }
        return ia;
    }

    private static Plugin enabled(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled() ? plugin : null;
    }

    private static void warn(String key, String message, Throwable cause) {
        if (warned.add(key)) Bukkit.getLogger().warning("[CompanionPets] " + message
                + (cause == null ? "" : ": " + cause.getClass().getSimpleName() + " " + cause.getMessage()));
    }
}
