package net.tfminecraft.companionpets.behavior;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Mob;
import org.bukkit.entity.WaterMob;
import org.bukkit.entity.Axolotl;
import org.bukkit.entity.Turtle;
import org.bukkit.entity.Tadpole;

import net.tfminecraft.companionpets.fx.PetFx;

/** Land pets must be able to swim even when an order or a need keeps them still on land. */
public final class WaterEscape {
    private WaterEscape() { }

    public static boolean needed(Mob body) {
        return body.isInWater() && !(body instanceof WaterMob) && !(body instanceof Axolotl)
                && !(body instanceof Turtle) && !(body instanceof Tadpole);
    }

    /** Wake native FloatGoal and MoveControl without overriding their route or velocity. */
    public static void swim(Mob body) {
        body.setAware(true);
        PetFx.sit(body, false);
        PetFx.lie(body, false);
    }

    public static Location exit(Location from) {
        World world = from.getWorld();
        Location nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (int x = from.getBlockX() - 8; x <= from.getBlockX() + 8; x++) {
            for (int z = from.getBlockZ() - 8; z <= from.getBlockZ() + 8; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = Math.max(world.getMinHeight() + 1, from.getBlockY() - 2);
                        y <= Math.min(world.getMaxHeight() - 2, from.getBlockY() + 8); y++) {
                    Location candidate = new Location(world, x + 0.5, y, z + 0.5);
                    double candidateDistance = candidate.distanceSquared(from);
                    if (candidateDistance < distance && safe(candidate)) {
                        nearest = candidate;
                        distance = candidateDistance;
                    }
                }
            }
        }
        return nearest;
    }
    public static boolean safe(Location at) {
        World world = at.getWorld();
        if (world == null || at.getBlockY() <= world.getMinHeight() || at.getBlockY() + 1 >= world.getMaxHeight()
                || !world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)
                || !world.getWorldBorder().isInside(at)) return false;
        var feet = at.getBlock();
        var head = feet.getRelative(0, 1, 0);
        var floor = feet.getRelative(0, -1, 0);
        return feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid()
                && floor.getType().isSolid() && !floor.isPassable() && !floor.isLiquid()
                && !hazard(feet.getType()) && !hazard(head.getType()) && !hazard(floor.getType());
    }

    private static boolean hazard(Material material) {
        return switch (material) {
            case LAVA, FIRE, SOUL_FIRE, MAGMA_BLOCK, CAMPFIRE, SOUL_CAMPFIRE,
                    CACTUS, POWDER_SNOW, SWEET_BERRY_BUSH -> true;
            default -> false;
        };
    }
}
