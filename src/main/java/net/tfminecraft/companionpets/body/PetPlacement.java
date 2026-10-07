package net.tfminecraft.companionpets.body;

import java.util.ArrayList;
import java.util.Comparator;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Conservative collision checks cover the whole scaled body, including solid furniture barriers. */
public final class PetPlacement {
    private PetPlacement() { }
    public static BoundingBox bounds(Entity body) {
        return body.getBoundingBox().clone().shift(body.getLocation().toVector().multiply(-1));
    }
    public static BoundingBox normal(double scale) {
        return new BoundingBox(-.3 * scale, 0, -.3 * scale, .3 * scale, .85 * scale, .3 * scale);
    }
    public static boolean clear(Location at, BoundingBox relative) {
        if (at == null || at.getWorld() == null) return false;
        BoundingBox box = relative.clone().shift(at.toVector());
        for (int x = (int) Math.floor(box.getMinX() + 1e-6); x <= Math.floor(box.getMaxX() - 1e-6); x++)
            for (int y = (int) Math.floor(box.getMinY() + 1e-6); y <= Math.floor(box.getMaxY() - 1e-6); y++)
                for (int z = (int) Math.floor(box.getMinZ() + 1e-6); z <= Math.floor(box.getMaxZ() - 1e-6); z++) {
                    var block = at.getWorld().getBlockAt(x, y, z);
                    if (!block.isPassable() || block.isLiquid()) return false;
                }
        return !at.getWorld().hasCollisionsIn(box);
    }
    public static boolean safe(Location at, BoundingBox bounds) {
        if (!clear(at, bounds)) return false;
        BoundingBox box = bounds.clone().shift(at.toVector());
        int y = (int) Math.floor(box.getMinY() - .01);
        for (int x = (int) Math.floor(box.getMinX() + 1e-6); x <= Math.floor(box.getMaxX() - 1e-6); x++)
            for (int z = (int) Math.floor(box.getMinZ() + 1e-6); z <= Math.floor(box.getMaxZ() - 1e-6); z++)
                if (!at.getWorld().getBlockAt(x, y, z).getType().isSolid()) return false;
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
            Location at = centered(feet.clone().add(offset));
            if (away(at, house) && safe(at, bounds)) return at;
        }
        return nearest(feet, bounds, house);
    }
    public static Location nearest(Location anchor, BoundingBox bounds, Location house) {
        if (anchor == null || anchor.getWorld() == null) return null;
        var candidates = new ArrayList<Location>();
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) for (int y = -3; y <= 3; y++)
            candidates.add(centered(anchor.clone().add(x, y, z)));
        candidates.sort(Comparator.comparingDouble(at -> at.distanceSquared(anchor)));
        for (Location at : candidates) if (away(at, house) && safe(at, bounds)) return at;
        return null;
    }
    private static boolean away(Location at, Location house) {
        return house == null || at.getWorld() != house.getWorld() || at.distanceSquared(house) >= 4;
    }
    private static Location centered(Location at) {
        at.setX(at.getBlockX() + .5); at.setZ(at.getBlockZ() + .5); at.setY(at.getBlockY()); at.setPitch(0);
        return at;
    }
}
