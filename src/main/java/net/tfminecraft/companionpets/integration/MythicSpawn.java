package net.tfminecraft.companionpets.integration;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

public final class MythicSpawn {
    private MythicSpawn() {
    }

    public static boolean available() {
        try {
            Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    public static Entity spawn(String mobId, Location location, Logger logger) {
        try {
            Class<?> mythicClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object mythic = mythicClass.getMethod("inst").invoke(null);
            Object manager = mythic.getClass().getMethod("getMobManager").invoke(mythic);
            Object optional = manager.getClass().getMethod("getMythicMob", String.class).invoke(manager, mobId);
            if (!(optional instanceof Optional<?> found) || found.isEmpty()) {
                logger.warning("MythicMob " + mobId + " is not loaded");
                return null;
            }
            Object mob = found.get();
            Class<?> adapter = Class.forName("io.lumine.mythic.bukkit.BukkitAdapter");
            Object abstractLocation = adapter.getMethod("adapt", Location.class).invoke(null, location);
            Object active = null;
            for (Method method : mob.getClass().getMethods()) {
                if (!method.getName().equals("spawn") || method.getParameterCount() != 2) {
                    continue;
                }
                Class<?> second = method.getParameterTypes()[1];
                if (second != double.class && second != float.class) {
                    continue;
                }
                if (!method.getParameterTypes()[0].isInstance(abstractLocation)) {
                    continue;
                }
                Object level = second == float.class ? 1.0f : 1.0d;
                active = method.invoke(mob, abstractLocation, level);
                break;
            }
            if (active == null) {
                logger.warning("MythicMob " + mobId + " has no spawn(location, level) method");
                return null;
            }
            Object mythicEntity = active.getClass().getMethod("getEntity").invoke(active);
            Object bukkit = mythicEntity.getClass().getMethod("getBukkitEntity").invoke(mythicEntity);
            if (bukkit instanceof Entity entity) {
                return entity;
            }
        } catch (ReflectiveOperationException ex) {
            logger.log(Level.WARNING, "Could not spawn MythicMob " + mobId, ex);
        }
        return null;
    }
}
