package net.tfminecraft.companionpets.body;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Finds a standing spot for the whole scaled body before it appears. Checks use the world's collision
 * shapes, so slabs, paths and carpets are floors and furniture barriers are obstacles.
 */
public final class PetPlacement {
    private static final double PIXEL = 1 / 16.0;
    private static final Set<Material> HAZARDS = EnumSet.of(Material.FIRE, Material.SOUL_FIRE, Material.COBWEB,
            Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.WITHER_ROSE);
    private static final Set<Material> HOT_FLOORS = EnumSet.of(Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE);

    private PetPlacement() { }
    public static BoundingBox bounds(Entity body) {
        return body.getBoundingBox().clone().shift(body.getLocation().toVector().multiply(-1));
    }
    public static BoundingBox normal(double scale) {
        return new BoundingBox(-.3 * scale, 0, -.3 * scale, .3 * scale, .85 * scale, .3 * scale);
    }
    /** Nothing collides with the body, and it is not in a liquid or a harmful block. */
    public static boolean clear(Location at, BoundingBox relative) {
        if (at == null || at.getWorld() == null) return false;
        BoundingBox box = relative.clone().shift(at.toVector());
        if (at.getWorld().hasCollisionsIn(box)) return false;
        for (int x = (int) Math.floor(box.getMinX() + 1e-6); x <= Math.floor(box.getMaxX() - 1e-6); x++)
            for (int y = (int) Math.floor(box.getMinY() + 1e-6); y <= Math.floor(box.getMaxY() - 1e-6); y++)
                for (int z = (int) Math.floor(box.getMinZ() + 1e-6); z <= Math.floor(box.getMaxZ() - 1e-6); z++) {
                    var block = at.getWorld().getBlockAt(x, y, z);
                    if (block.isLiquid() || HAZARDS.contains(block.getType())) return false;
                }
        return true;
    }
    /** Clear, and the feet rest on something solid that does not burn. */
    public static boolean safe(Location at, BoundingBox bounds) {
        if (!clear(at, bounds)) return false;
        BoundingBox box = bounds.clone().shift(at.toVector());
        BoundingBox floor = new BoundingBox(box.getMinX(), box.getMinY() - PIXEL, box.getMinZ(), box.getMaxX(), box.getMinY(), box.getMaxZ());
        if (!at.getWorld().hasCollisionsIn(floor)) return false;
        int y = (int) Math.floor(box.getMinY() - 1e-3);
        for (int x = (int) Math.floor(box.getMinX() + 1e-6); x <= Math.floor(box.getMaxX() - 1e-6); x++)
            for (int z = (int) Math.floor(box.getMinZ() + 1e-6); z <= Math.floor(box.getMaxZ() - 1e-6); z++) {
                var below = at.getWorld().getBlockAt(x, y, z);
                if (below.isLiquid() || HOT_FLOORS.contains(below.getType())) return false;
            }
        return true;
    }
    public static Location beside(Player player, BoundingBox bounds, Location house) {
        Location feet = player.getLocation();
        Vector forward = feet.getDirection().setY(0);
        if (forward.lengthSquared() < .0001) forward = new Vector(0, 0, 1);
        forward.normalize();
        Vector side = new Vector(-forward.getZ(), 0, forward.getX());
        for (Vector offset : house == null ? java.util.List.of(forward.clone().multiply(1.6), forward.clone().multiply(-1.6), side.clone().multiply(1.6), side.clone().multiply(-1.6))
                : java.util.List.of(forward.clone().multiply(-1.6), side.clone().multiply(1.6), side.clone().multiply(-1.6))) {
            Location at = standing(feet.clone().add(offset), feet.getBlockY(), bounds);
            if (at != null && away(at, house) && safe(at, bounds)) return at;
        }
        return nearest(feet, bounds, house);
    }
    public static Location nearest(Location anchor, BoundingBox bounds, Location house) {
        if (anchor == null || anchor.getWorld() == null) return null;
        var cells = new ArrayList<int[]>();
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) for (int y = -3; y <= 3; y++) cells.add(new int[]{x, y, z});
        cells.sort(Comparator.comparingInt(c -> c[0] * c[0] + c[1] * c[1] + c[2] * c[2]));
        for (int[] cell : cells) {
            Location at = standing(anchor.clone().add(cell[0], 0, cell[2]), anchor.getBlockY() + cell[1], bounds);
            if (at != null && away(at, house) && safe(at, bounds)) return at;
        }
        return null;
    }
    private static boolean away(Location at, Location house) {
        return house == null || at.getWorld() != house.getWorld() || at.distanceSquared(house) >= 4;
    }
    /** The block-centered spot whose feet rest on the top of whatever collides inside block y, if it is not full. */
    private static Location standing(Location column, int y, BoundingBox bounds) {
        World world = column.getWorld();
        Location at = column.clone();
        at.setX(column.getBlockX() + .5); at.setZ(column.getBlockZ() + .5); at.setY(y); at.setPitch(0);
        BoundingBox cell = new BoundingBox(at.getX() + bounds.getMinX(), y, at.getZ() + bounds.getMinZ(),
                at.getX() + bounds.getMaxX(), y + 1, at.getZ() + bounds.getMaxZ());
        if (!world.hasCollisionsIn(cell)) return at;
        double low = y, high = y + 1;
        for (int i = 0; i < 10; i++) {
            double middle = (low + high) / 2;
            if (world.hasCollisionsIn(above(cell, middle))) low = middle; else high = middle;
        }
        if (high > y + 1 - PIXEL / 2) return null;
        double pixel = Math.round(high / PIXEL) * PIXEL;
        at.setY(world.hasCollisionsIn(above(cell, pixel)) ? high : pixel);
        return at;
    }
    private static BoundingBox above(BoundingBox cell, double minY) {
        return new BoundingBox(cell.getMinX(), minY, cell.getMinZ(), cell.getMaxX(), cell.getMaxY(), cell.getMaxZ());
    }
}
