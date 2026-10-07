package net.tfminecraft.companionpets.testutil;

import org.bukkit.util.BoundingBox;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** MockBukkit has no collision API; fixtures model solid blocks, and bottom slabs as half blocks. */
public class CollisionWorldMock extends WorldMock {
    @Override public boolean hasCollisionsIn(BoundingBox box) {
        for (int x = (int) Math.floor(box.getMinX()); x <= Math.floor(box.getMaxX()); x++)
            for (int y = (int) Math.floor(box.getMinY()); y <= Math.floor(box.getMaxY()); y++)
                for (int z = (int) Math.floor(box.getMinZ()); z <= Math.floor(box.getMaxZ()); z++) {
                    var type = getBlockAt(x, y, z).getType();
                    if (!type.isSolid()) continue;
                    double top = y + (type.name().endsWith("_SLAB") ? .5 : 1);
                    if (box.overlaps(new BoundingBox(x, y, z, x + 1, top, z + 1))
                            && Math.min(box.getMaxX(), x + 1) - Math.max(box.getMinX(), x) > 1e-7
                            && Math.min(box.getMaxY(), top) - Math.max(box.getMinY(), y) > 1e-7
                            && Math.min(box.getMaxZ(), z + 1) - Math.max(box.getMinZ(), z) > 1e-7) return true;
                }
        return false;
    }
}
