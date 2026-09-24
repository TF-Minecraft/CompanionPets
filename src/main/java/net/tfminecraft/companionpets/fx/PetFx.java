package net.tfminecraft.companionpets.fx;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fox;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Wolf;

import io.papermc.paper.entity.LookAnchor;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class PetFx {
    private static final long REPEAT_AFTER_MILLIS = 1_600L;
    private static final long HOLD_MILLIS = 2_200L;
    private static final Map<UUID, Hold> HOLDS = new HashMap<>();
    private static final Map<UUID, Long> QUIET = new HashMap<>();

    private PetFx() {
    }

    public static void bar(Player player, String text) {
        bar(player, Component.text(text));
    }

    public static void bar(Player player, Component text) {
        show(player, text);
        QUIET.put(player.getUniqueId(), System.currentTimeMillis() + HOLD_MILLIS);
    }

    public static void tell(Player player, String text) {
        player.sendMessage(Component.text("✦ ", NamedTextColor.GOLD).append(Component.text(text, NamedTextColor.YELLOW)));
    }

    public static void tip(Player player, String text) {
        player.sendMessage(Component.text("   Tip: ", NamedTextColor.AQUA).append(Component.text(text, NamedTextColor.GRAY)));
    }

    public static void cue(Player player, Sound sound, float pitch) {
        player.playSound(player.getLocation(), sound, 0.7f, pitch);
    }

    public static void status(Player player, String text) {
        status(player, Component.text(text));
    }

    public static void status(Player player, Component text) {
        Long until = QUIET.get(player.getUniqueId());
        if (until != null && System.currentTimeMillis() < until) {
            return;
        }
        show(player, text);
    }

    private static void show(Player player, Component text) {
        long now = System.currentTimeMillis();
        String key = PlainTextComponentSerializer.plainText().serialize(text);
        Hold hold = HOLDS.get(player.getUniqueId());
        if (hold != null && key.equals(hold.text) && now - hold.at < REPEAT_AFTER_MILLIS) {
            return;
        }
        HOLDS.put(player.getUniqueId(), new Hold(key, now));
        player.sendActionBar(text);
    }

    public static void ambient(Entity entity) {
        entity.getWorld().playSound(entity.getLocation(), ambientSound(entity.getType()), 0.8f, 1.0f);
    }

    public static void eat(Entity entity) {
        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.8f, 1.0f);
    }

    public static void hearts(Entity entity, int count) {
        entity.getWorld().spawnParticle(Particle.HEART, entity.getLocation().add(0, 1.0, 0), count, 0.3, 0.3, 0.3, 0);
    }

    public static void particle(Entity entity, Particle particle, int count) {
        entity.getWorld().spawnParticle(particle, entity.getLocation().add(0, 0.8, 0), count, 0.25, 0.3, 0.25, 0);
    }

    public static void sit(Entity entity, boolean sitting) {
        if (entity instanceof Sittable sittable) {
            sittable.setSitting(sitting);
        }
        if (!sitting && entity instanceof Fox fox) {
            fox.setSleeping(false);
        }
    }

    public static void lie(Entity entity, boolean lying) {
        if (entity instanceof Fox fox) {
            fox.setSleeping(lying);
            fox.setSitting(false);
            return;
        }
        sit(entity, lying);
    }

    public static void beg(Entity entity) {
        if (entity instanceof Wolf wolf) {
            wolf.setInterested(true);
        }
        if (entity instanceof LivingEntity living) {
            Location above = living.getLocation().add(0, 2, 0);
            living.lookAt(above.getX(), above.getY(), above.getZ(), LookAnchor.EYES);
        }
    }

    public static void stopBeg(Entity entity) {
        if (entity instanceof Wolf wolf && wolf.isValid()) {
            wolf.setInterested(false);
        }
    }

    public static void jump(Entity entity, boolean partial) {
        Vector velocity = entity.getVelocity();
        velocity.setY(partial ? 0.28 : 0.48);
        entity.setVelocity(velocity);
    }

    public static void look(Entity entity, Location target) {
        if (entity instanceof LivingEntity living && target != null) {
            living.lookAt(target.getX(), target.getY(), target.getZ(), LookAnchor.EYES);
        }
    }

    private record Hold(String text, long at) {
    }

    public static void happy(Entity entity, boolean loud) {
        Sound sound = switch (entity.getType()) {
            case WOLF -> loud ? Sound.ENTITY_WOLF_AMBIENT : Sound.ENTITY_WOLF_PANT;
            case CAT -> loud ? Sound.ENTITY_CAT_PURREOW : Sound.ENTITY_CAT_PURR;
            case FOX -> loud ? Sound.ENTITY_FOX_AMBIENT : Sound.ENTITY_FOX_SNIFF;
            default -> ambientSound(entity.getType());
        };
        entity.getWorld().playSound(entity.getLocation(), sound, 0.9f, 1.1f);
    }

    public static void sad(Entity entity) {
        Sound sound = switch (entity.getType()) {
            case WOLF -> Sound.ENTITY_WOLF_WHINE;
            case CAT -> Sound.ENTITY_CAT_BEG_FOR_FOOD;
            case FOX -> Sound.ENTITY_FOX_SNIFF;
            default -> ambientSound(entity.getType());
        };
        entity.getWorld().playSound(entity.getLocation(), sound, 0.8f, 0.9f);
    }

    public static Sound ambientSound(EntityType type) {
        return switch (type) {
            case WOLF -> Sound.ENTITY_WOLF_AMBIENT;
            case CAT -> Sound.ENTITY_CAT_AMBIENT;
            case FOX -> Sound.ENTITY_FOX_AMBIENT;
            case PARROT -> Sound.ENTITY_PARROT_AMBIENT;
            default -> Sound.ENTITY_FOX_AMBIENT;
        };
    }
}
