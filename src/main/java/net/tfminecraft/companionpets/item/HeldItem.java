package net.tfminecraft.companionpets.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** One main-thread interaction's item, identified only when a comparison needs it. */
public final class HeldItem implements AutoCloseable {
    private static final List<HeldItem> scopes = new ArrayList<>();
    private final ItemStack stack;
    private final Material material;
    private final boolean empty;
    private ItemBridge.Identity identity;
    private List<ItemRef> keys;

    private HeldItem(ItemStack stack) {
        this.stack = stack;
        material = stack == null ? Material.AIR : stack.getType();
        empty = stack == null || material.isAir() || stack.getAmount() <= 0;
    }

    public static HeldItem of(ItemStack stack) {
        for (int i = scopes.size() - 1; i >= 0; i--) {
            HeldItem held = scopes.get(i);
            if (held.stack == stack) return held;
        }
        return new HeldItem(stack);
    }

    /** Lets existing ItemStack APIs share this identity during a synchronous call. */
    public HeldItem scope() {
        scopes.add(this);
        return this;
    }

    @Override public void close() { scopes.removeLast(); }

    public Material material() { return material; }
    public boolean empty() { return empty; }

    public Integer model() {
        var meta = stack == null ? null : stack.getItemMeta();
        return meta != null && meta.hasCustomModelData() ? meta.getCustomModelData() : null;
    }

    public boolean matches(ItemRef ref) {
        if (empty() || ref.kind() == ItemRef.Kind.VANILLA && material() != ref.material()) return false;
        return ref.matches(identity());
    }

    private ItemBridge.Identity identity() {
        if (identity == null) identity = ItemBridge.identity(stack);
        return identity;
    }

    /** All matching selectors, including both providers if an item carries both IDs. */
    public List<ItemRef> keys() {
        if (keys != null) return keys;
        if (empty()) return List.of();
        var item = identity();
        if (!item.known()) return List.of();
        List<ItemRef> keys = new ArrayList<>();
        if (item.mmoType() != null && item.mmoId() != null)
            keys.add(new ItemRef(ItemRef.Kind.MMOITEMS, item.mmoId().toUpperCase(Locale.ROOT), item.mmoType().toUpperCase(Locale.ROOT)));
        if (item.itemsAdderId() != null)
            keys.add(new ItemRef(ItemRef.Kind.ITEMSADDER, item.itemsAdderId().toLowerCase(Locale.ROOT), null));
        if (item.mmoType() == null && item.itemsAdderId() == null) keys.add(ItemRef.vanilla(material()));
        this.keys = List.copyOf(keys);
        return this.keys;
    }
}
