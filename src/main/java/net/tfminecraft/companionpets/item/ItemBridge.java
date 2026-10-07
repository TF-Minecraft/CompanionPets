package net.tfminecraft.companionpets.item;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Optional integrations loaded from their owning plugins, without bundling their APIs. */
final class ItemBridge {
    private ItemBridge() { }
    private static final Set<String> warned = new HashSet<>();
    private static Mmo mmo;
    private static Ia ia;
    static final long DISPLAY_MILLIS = 30_000L;
    private record Shown(ItemStack item, Plugin provider, long at) {}
    private static final java.util.Map<ItemRef, Shown> shown = new java.util.HashMap<>();

    private record Mmo(Method type, Method id, Method getType, Method getItem, Plugin plugin) {}
    private record Ia(Method identify, Method id, Method create, Method stack, Plugin plugin) {}

    static ItemIdentity identity(ItemStack item) {
        String mmoType = null, mmoId = null, iaId = null;
        try {
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
            return new ItemIdentity(item.getType(), mmoType, mmoId, iaId, true);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            warn("identify", "Could not identify custom item; refusing to match it", ex);
            return new ItemIdentity(item.getType(), null, null, null, false);
        }
    }

    static ItemStack create(ItemRef ref) {
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

    /**
     * Menus refresh their icons twice a second and providers build complete items, so display
     * copies are reused for a while. A replaced or reloaded provider rebuilds them at once.
     */
    static ItemStack display(ItemRef ref) {
        if (ref.kind() == ItemRef.Kind.VANILLA) return create(ref);
        long now = System.currentTimeMillis();
        Plugin provider = enabled(ref.kind() == ItemRef.Kind.MMOITEMS ? "MMOItems" : "ItemsAdder");
        Shown cached = shown.get(ref);
        if (cached == null || cached.provider() != provider || now - cached.at() >= DISPLAY_MILLIS) {
            ItemStack item = create(ref);
            if (item == null) {
                shown.remove(ref);
                return null;
            }
            cached = new Shown(item, provider, now);
            shown.put(ref, cached);
        }
        return cached.item().clone();
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
