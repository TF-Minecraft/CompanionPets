package net.tfminecraft.companionpets.behavior;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Mob;
import org.bukkit.entity.WaterMob;
import org.bukkit.entity.Axolotl;
import org.bukkit.entity.Turtle;
import org.bukkit.entity.Tadpole;
import org.bukkit.util.Vector;

import net.tfminecraft.companionpets.fx.PetFx;

/** Land pets must be able to swim even when an order or a need keeps them still on land. */
public final class WaterEscape {
    private WaterEscape() { }

    public static boolean needed(Mob body) {
        return body.isInWater() && !(body instanceof WaterMob) && !(body instanceof Axolotl)
                && !(body instanceof Turtle) && !(body instanceof Tadpole);
    }

    public static void swim(Mob body, Location exit) {
        swim(body, exit, 1.1);
    }

    public static void swim(Mob body, Location exit, double navigationSpeed) {
        body.setAware(true);
        PetFx.sit(body, false);
        PetFx.lie(body, false);
        Vector velocity = body.getVelocity();
        velocity.setY(Math.max(velocity.getY(), 0.16));
        if (exit != null && exit.getWorld().equals(body.getWorld())) {
            Vector direction = exit.toVector().subtract(body.getLocation().toVector()).setY(0);
            if (direction.lengthSquared() > 0.04) {
                direction.normalize().multiply(0.10 * navigationSpeed / 1.1);
                velocity.setX(direction.getX());
                velocity.setZ(direction.getZ());
            }
        }
        body.setVelocity(velocity);
    }

    public static Location exit(Location from, Location preferred) {
        return exit(from, preferred, candidate -> true);
    }

    public static Location exit(Location from, Location preferred, java.util.function.Predicate<Location> reachable) {
        if (preferred != null && preferred.getWorld().equals(from.getWorld())
                && preferred.distanceSquared(from) <= 144 && safe(preferred) && reachable.test(preferred)) return preferred.clone();
        World world = from.getWorld();
        var candidates = new java.util.ArrayList<Location>();
        for (int x = from.getBlockX() - 8; x <= from.getBlockX() + 8; x++) {
            for (int z = from.getBlockZ() - 8; z <= from.getBlockZ() + 8; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = Math.max(world.getMinHeight() + 1, from.getBlockY() - 2);
                        y <= Math.min(world.getMaxHeight() - 2, from.getBlockY() + 8); y++) {
                    Location candidate = new Location(world, x + 0.5, y, z + 0.5);
                    if (safe(candidate)) candidates.add(candidate);
                }
            }
        }
        return candidates.stream().sorted(java.util.Comparator.comparingDouble(candidate ->
                candidate.distanceSquared(from) + (preferred != null && world.equals(preferred.getWorld())
                        ? candidate.distanceSquared(preferred) : 0)))
                .filter(reachable).findFirst().orElse(null);
    }

    /** Native land routes or an unobstructed swim to water/a low, dry bank. */
    public static boolean reachable(Mob body, Location target) {
        return reachable(body, target, at -> {
            var path = body.getPathfinder().findPath(at);
            return path != null && path.canReachFinalPoint();
        });
    }

    /** The caller budgets native routes; swimming remains a cheap fallback for every candidate. */
    public static boolean reachable(Mob body, Location target, java.util.function.Predicate<Location> landRoute) {
        if (target == null || !body.getWorld().equals(target.getWorld())
                || !body.getWorld().isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4)
                || !body.getWorld().getWorldBorder().isInside(target)) return false;
        if (landRoute.test(target)) return true;
        if (!needed(body) || Math.abs(target.getY() - body.getLocation().getY()) > 1.5
                || !(target.getBlock().getType() == Material.WATER || safe(target))) return false;
        var from = body.getEyeLocation();
        var direction = target.clone().add(0, body.getEyeHeight(), 0).toVector().subtract(from.toVector());
        double distance = direction.length();
        return distance > 0.01 && body.getWorld().rayTraceBlocks(from, direction.normalize(), distance,
                org.bukkit.FluidCollisionMode.NEVER, true) == null;
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
