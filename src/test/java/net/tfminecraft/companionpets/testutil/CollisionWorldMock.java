package net.tfminecraft.companionpets.testutil;

import org.bukkit.util.BoundingBox;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** MockBukkit has no collision API; fixtures model solid voxel collisions explicitly. */
public class CollisionWorldMock extends WorldMock {
    @Override public boolean hasCollisionsIn(BoundingBox box) {
        for (int x = (int) Math.floor(box.getMinX() + 1e-6); x <= Math.floor(box.getMaxX() - 1e-6); x++)
            for (int y = (int) Math.floor(box.getMinY() + 1e-6); y <= Math.floor(box.getMaxY() - 1e-6); y++)
                for (int z = (int) Math.floor(box.getMinZ() + 1e-6); z <= Math.floor(box.getMaxZ() - 1e-6); z++)
                    if (getBlockAt(x, y, z).getType().isSolid()) return true;
        return false;
    }
}
