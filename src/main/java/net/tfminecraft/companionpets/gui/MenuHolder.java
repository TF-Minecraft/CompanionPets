package net.tfminecraft.companionpets.gui;

import java.util.UUID;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class MenuHolder implements InventoryHolder {
    public enum Kind {
        CARE,
        KENNEL,
        TRICK,
        LEARNED
    }

    private final Kind kind;
    private final UUID petId;
    private final String word;
    private Inventory inventory;
    private int page;
    private int pages = 1;
    private boolean shelterBack;
    public boolean shelterBack() { return shelterBack; }
    public void shelterBack(boolean value) { shelterBack = value; }
    public int pages() { return pages; }
    public void pages(int pages) { this.pages = Math.max(1, pages); }
    private boolean navigating;
    public boolean navigating() { return navigating; }
    public void navigating(boolean navigating) { this.navigating = navigating; }
    private final java.util.Map<Integer, net.tfminecraft.companionpets.pet.Trick> tricks = new java.util.HashMap<>();
    public int page() { return page; }
    public void page(int page) { this.page = page; }
    public void trick(int slot, net.tfminecraft.companionpets.pet.Trick trick) { tricks.put(slot, trick); }
    public net.tfminecraft.companionpets.pet.Trick trick(int slot) { return tricks.get(slot); }

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
