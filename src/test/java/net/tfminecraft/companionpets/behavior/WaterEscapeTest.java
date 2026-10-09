package net.tfminecraft.companionpets.behavior;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class WaterEscapeTest {
    private final Map<String, Material> terrain = new HashMap<>();
    private WorldMock world;

    @BeforeEach void setup() {
        var server = MockBukkit.mock();
        world = new WorldMock() {
            @Override public boolean isChunkLoaded(int x, int z) { return x == 0 && z == 0; }
            @Override public BlockMock getBlockAt(int x, int y, int z) {
                return new BlockMock(new Location(this, x, y, z)) {
                    @Override public Material getType() { return terrain.getOrDefault(x + ":" + y + ":" + z, Material.AIR); }
                    @Override public boolean isPassable() { return getType().isAir() || isLiquid() || getType() == Material.FIRE; }
                    @Override public boolean isLiquid() { return getType() == Material.WATER || getType() == Material.LAVA; }
                    @Override public Block getRelative(int dx, int dy, int dz) { return world.getBlockAt(x + dx, y + dy, z + dz); }
                };
            }
        };
        server.addWorld(world);
    }
    @AfterEach void cleanup() { MockBukkit.unmock(); }
    private void block(int x, int y, int z, Material type) { terrain.put(x + ":" + y + ":" + z, type); }

    @Test void choosesTheNearestSafeDryShore() {
        Location from = new Location(world, 2.5, 62, 2.5);
        block(4, 63, 2, Material.STONE);
        block(7, 63, 2, Material.STONE);
        Location shore = new Location(world, 4.5, 64, 2.5);
        Location owner = new Location(world, 7.5, 64, 2.5);
        assertEquals(shore, WaterEscape.exit(from, Set.of()));
        assertEquals(owner, WaterEscape.exit(from, Set.of(shore)));
        assertNull(WaterEscape.exit(from, Set.of(shore, owner)));
        block(7, 64, 2, Material.WATER);
        assertEquals(shore, WaterEscape.exit(from, Set.of()));
    }

    @Test void rejectsHazardsLowCeilingsAndUnloadedOrUnsupportedPositions() {
        Location at = new Location(world, 4.5, 64, 2.5);
        assertFalse(WaterEscape.safe(at));
        block(4, 63, 2, Material.STONE); assertTrue(WaterEscape.safe(at));
        for (Material hazard : new Material[]{Material.WATER, Material.LAVA, Material.FIRE}) {
            block(4, 64, 2, hazard); assertFalse(WaterEscape.safe(at));
        }
        block(4, 64, 2, Material.AIR); block(4, 65, 2, Material.STONE);
        assertFalse(WaterEscape.safe(at)); block(4, 65, 2, Material.AIR);
        block(4, 63, 2, Material.MAGMA_BLOCK); assertFalse(WaterEscape.safe(at));
        block(20, 63, 2, Material.STONE); assertFalse(WaterEscape.safe(new Location(world, 20.5, 64, 2.5)));
        assertNull(WaterEscape.exit(new Location(world, 2.5, 62, 2.5), Set.of()));
    }

}
