package net.tfminecraft.companionpets.fx;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import net.tfminecraft.companionpets.item.ItemRef;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fox;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Wolf;
import org.bukkit.inventory.ItemStack;

import io.papermc.paper.entity.LookAnchor;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.companionpets.pet.Need;

public final class PetFx {
    private static final long REPEAT_AFTER_MILLIS = 1_600L;
    private static final long HOLD_MILLIS = 2_200L;
    private static final Map<UUID, Hold> HOLDS = new HashMap<>();
    private static final Map<UUID, Alert> ALERTS = new HashMap<>();

    private PetFx() {
    }

    public static void bar(Player player, String text) {
        bar(player, Component.text(text));
    }

    public static void bar(Player player, Component text) {
        show(player, text);
        ALERTS.put(player.getUniqueId(), new Alert(text, System.currentTimeMillis() + HOLD_MILLIS));
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
        if (!refreshHeld(player)) player.sendActionBar(text);
    }

    public static boolean refreshHeld(Player player) {
        Alert alert = ALERTS.get(player.getUniqueId());
        if (alert == null) return false;
        if (System.currentTimeMillis() >= alert.until) {
            ALERTS.remove(player.getUniqueId());
            return false;
        }
        player.sendActionBar(alert.text);
        return true;
    }

    public static void clearPlayer(UUID playerId) {
        ALERTS.remove(playerId);
        HOLDS.remove(playerId);
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

    public static void need(Entity entity, Need need, ItemRef favoriteFood) {
        if (need == null) {
            return;
        }
        if (need == Need.HUNGER) {
            ItemStack food = favoriteFood == null ? new ItemStack(Material.COOKED_BEEF) : favoriteFood.icon(Material.COOKED_BEEF);
            entity.getWorld().spawnParticle(Particle.ITEM, entity.getLocation().add(0, 0.9, 0),
                    4, 0.2, 0.2, 0.2, 0.02, food);
            return;
        }
        Particle signal = switch (need) {
            case MOOD -> Particle.SPLASH;
            case ENERGY -> Particle.CLOUD;
            case CLEANLINESS -> Particle.DUST_PLUME;
            case HEALTH -> Particle.DAMAGE_INDICATOR;
            default -> Particle.CLOUD;
        };
        particle(entity, signal, need == Need.CLEANLINESS ? 4 : 3);
    }

    public static void sit(Entity entity, boolean sitting) {
        if (entity instanceof Mob mob && net.tfminecraft.companionpets.behavior.WaterEscape.needed(mob)) sitting = false;
        if (entity instanceof Sittable sittable) {
            sittable.setSitting(sitting);
        }
        if (!sitting && entity instanceof Fox fox) {
            fox.setSleeping(false);
        }
    }

    public static void lie(Entity entity, boolean lying) {
        if (entity instanceof Mob mob && net.tfminecraft.companionpets.behavior.WaterEscape.needed(mob)) lying = false;
        if (entity instanceof Fox fox) {
            fox.setSleeping(lying);
            fox.setSitting(false);
            return;
        }
        if (entity instanceof LivingEntity living && living.getPose() == Pose.SLEEPING) {
            living.setPose(Pose.STANDING);
        }
        sit(entity, lying);
    }

    public static void hurt(Entity entity) {
        entity.getWorld().playSound(entity.getLocation(), hurtSound(entity.getType()), 1.0f, 1.0f);
    }

    public static void beg(Entity entity) {
        if (entity instanceof Wolf wolf) {
            wolf.setInterested(true);
        }
        if (entity instanceof LivingEntity living) {
            Location above = living.getLocation().add(0, 2, 0);
            look(living, above);
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
        if (entity instanceof Mob mob && target != null && mob.getWorld().equals(target.getWorld())) {
            PetLookGoal.look(mob, target);
        } else if (entity instanceof LivingEntity living && target != null && living.getWorld().equals(target.getWorld())) {
            living.lookAt(target.getX(), target.getY(), target.getZ(), LookAnchor.EYES);
        }
    }

    public static void look(Entity entity, Entity target) {
        if (target == null) return;
        if (entity instanceof Mob mob && mob.getWorld().equals(target.getWorld())) {
            PetLookGoal.look(mob, target);
        } else {
            look(entity, target instanceof LivingEntity living ? living.getEyeLocation() : target.getLocation());
        }
    }

    public static void stopLooking(Entity entity) {
        if (entity instanceof Mob mob) PetLookGoal.remove(mob);
    }

    private record Hold(String text, long at) {
    }

    private record Alert(Component text, long until) {
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

    public static Sound hurtSound(EntityType type) {
        return switch (type) {
            case WOLF -> Sound.ENTITY_WOLF_HURT;
            case CAT -> Sound.ENTITY_CAT_HURT;
            case FOX -> Sound.ENTITY_FOX_HURT;
            case PARROT -> Sound.ENTITY_PARROT_HURT;
            default -> Sound.ENTITY_PLAYER_HURT;
        };
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
