package net.tfminecraft.companionpets.fx;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class PetHolograms {
    private static final long FOLLOW_TICKS = 2L;
    private static final double ABOVE_NAME = 0.25;
    private static final double CARD_ABOVE = 0.75;
    private static final float CARD_SCALE = 0.55f;
    private static final long CARD_REFRESH_TICKS = 10L;

    private final Plugin plugin;
    private final Map<UUID, Hologram> shown = new HashMap<>();
    private final Map<UUID, Card> cards = new HashMap<>();

    public PetHolograms(Plugin plugin) {
        this.plugin = plugin;
    }

    public void sleep(Entity pet, boolean asleep) {
        if (pet == null || !pet.isValid()) {
            return;
        }
        UUID id = pet.getUniqueId();
        Hologram current = shown.get(id);
        if (!asleep) {
            if (current != null && current.held) {
                remove(id, current);
            }
            return;
        }
        if (current != null && current.held) {
            return;
        }
        show(pet, Component.text("Sleeping", NamedTextColor.GRAY), 0L, true);
    }

    public void show(Entity pet, Component text, long ticks) {
        show(pet, text, ticks, false);
    }

    private void show(Entity pet, Component text, long ticks, boolean held) {
        if (pet == null || !pet.isValid()) {
            return;
        }
        UUID id = pet.getUniqueId();
        remove(id);
        ArmorStand stand = pet.getWorld().spawn(above(pet), ArmorStand.class, as -> {
            as.customName(text);
            as.setCustomNameVisible(true);
            as.setVisible(false);
            as.setMarker(true);
            as.setGravity(false);
            as.setSmall(true);
            as.setInvulnerable(true);
            as.setSilent(true);
            as.setPersistent(false);
        });
        Hologram hologram = new Hologram(stand, held);
        shown.put(id, hologram);
        hologram.follow = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!pet.isValid() || !stand.isValid()) {
                remove(id, hologram);
                return;
            }
            stand.teleport(above(pet));
        }, FOLLOW_TICKS, FOLLOW_TICKS);
        if (!held) {
            hologram.expire = Bukkit.getScheduler().runTaskLater(plugin, () -> remove(id, hologram), ticks);
        }
    }

    /**
     * A card above the pet that only the viewer sees, so stats can be read after an
     * interaction without competing with other plugins' action bars.
     */
    public void card(Player viewer, Entity pet, java.util.function.Supplier<Component> text, long ticks) {
        if (viewer == null || pet == null || !pet.isValid()) return;
        UUID id = pet.getUniqueId();
        Card current = cards.get(id);
        if (current != null && current.viewer.equals(viewer.getUniqueId()) && current.display.isValid()) {
            current.display.text(text.get());
            current.expire.cancel();
            current.expire = Bukkit.getScheduler().runTaskLater(plugin, () -> removeCard(id, current), ticks);
            return;
        }
        if (current != null) removeCard(id, current);
        TextDisplay display = pet.getWorld().spawn(cardAt(pet), TextDisplay.class, d -> {
            d.text(text.get());
            d.setVisibleByDefault(false);
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.LEFT);
            d.setTeleportDuration((int) FOLLOW_TICKS);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(CARD_SCALE, CARD_SCALE, CARD_SCALE), new AxisAngle4f()));
        });
        viewer.showEntity(plugin, display);
        Card card = new Card(viewer.getUniqueId(), display);
        cards.put(id, card);
        card.follow = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!pet.isValid() || !display.isValid() || !viewer.isOnline()) {
                removeCard(id, card);
                return;
            }
            display.teleport(cardAt(pet));
            card.age += FOLLOW_TICKS;
            if (card.age % CARD_REFRESH_TICKS == 0) display.text(text.get());
        }, FOLLOW_TICKS, FOLLOW_TICKS);
        card.expire = Bukkit.getScheduler().runTaskLater(plugin, () -> removeCard(id, card), ticks);
    }

    private void removeCard(UUID id, Card card) {
        cards.remove(id, card);
        if (card.follow != null) card.follow.cancel();
        if (card.expire != null) card.expire.cancel();
        if (card.display.isValid()) card.display.remove();
    }

    public void clear() {
        for (var entry : List.copyOf(cards.entrySet())) removeCard(entry.getKey(), entry.getValue());
        for (UUID id : List.copyOf(shown.keySet())) {
            remove(id);
        }
    }

    private void remove(UUID id) {
        Hologram hologram = shown.get(id);
        if (hologram != null) {
            remove(id, hologram);
        }
    }

    private void remove(UUID id, Hologram hologram) {
        if (shown.get(id) == hologram) {
            shown.remove(id);
        }
        if (hologram.follow != null) {
            hologram.follow.cancel();
        }
        if (hologram.expire != null) {
            hologram.expire.cancel();
        }
        if (hologram.stand.isValid()) {
            hologram.stand.remove();
        }
    }

    private static Location above(Entity pet) {
        return pet.getLocation().add(0, pet.getHeight() + ABOVE_NAME, 0);
    }

    private static Location cardAt(Entity pet) {
        return pet.getLocation().add(0, pet.getHeight() + CARD_ABOVE, 0);
    }

    private static final class Card {
        private final UUID viewer;
        private final TextDisplay display;
        private BukkitTask follow;
        private BukkitTask expire;
        private long age;

        private Card(UUID viewer, TextDisplay display) {
            this.viewer = viewer;
            this.display = display;
        }
    }

    private static final class Hologram {
        private final ArmorStand stand;
        private final boolean held;
        private BukkitTask follow;
        private BukkitTask expire;

        private Hologram(ArmorStand stand, boolean held) {
            this.stand = stand;
            this.held = held;
        }
    }
}
