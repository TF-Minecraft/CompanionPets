package net.tfminecraft.companionpets.gui;

import java.util.UUID;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class MenuHolder implements InventoryHolder {
    public enum Kind {
        CARE,
        KENNEL,
        TRICK
    }

    private final Kind kind;
    private final UUID petId;
    private final String word;
    private Inventory inventory;

    public MenuHolder(Kind kind, UUID petId, String word) {
        this.kind = kind;
        this.petId = petId;
        this.word = word;
    }

    public Kind kind() {
        return kind;
    }

    public UUID petId() {
        return petId;
    }

    public String word() {
        return word;
    }

    public void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
