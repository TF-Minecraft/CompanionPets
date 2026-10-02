package net.tfminecraft.companionpets.staff;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class StaffMenuHolder implements InventoryHolder {
    public enum Kind { LIST, INSPECT, TRICKS }
    private final UUID viewer;
    private final Kind kind;
    private final UUID subject;
    private final int page;
    private final Map<Integer, Runnable> buttons = new HashMap<>();
    private final Map<Integer, Runnable> rightButtons = new HashMap<>();
    private Inventory inventory;
    public StaffMenuHolder(UUID viewer, Kind kind, UUID subject, int page) {
        this.viewer = viewer; this.kind = kind; this.subject = subject; this.page = page;
    }
    public UUID viewer() { return viewer; }
    public Kind kind() { return kind; }
    public UUID subject() { return subject; }
    public int page() { return page; }
    public void inventory(Inventory value) { inventory = value; }
    public void button(int slot, Runnable action) { buttons.put(slot, action); }
    public void rightButton(int slot, Runnable action) { rightButtons.put(slot, action); }
    public void click(int slot) { click(slot, false); }
    public void click(int slot, boolean right) { Runnable action = (right ? rightButtons : buttons).get(slot); if (action != null) action.run(); }
    @Override public Inventory getInventory() { return inventory; }
}
