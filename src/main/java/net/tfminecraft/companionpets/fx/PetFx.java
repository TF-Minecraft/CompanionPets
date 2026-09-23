package net.tfminecraft.companionpets.fx;

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

public final class PetFx {
    private PetFx() {
    }

    public static void bar(Player player, String text) {
        player.sendActionBar(Component.text(text));
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
