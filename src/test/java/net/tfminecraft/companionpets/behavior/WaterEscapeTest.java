package net.tfminecraft.companionpets.behavior;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
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
    private boolean blockedSwim;

    @BeforeEach void setup() {
        var server = MockBukkit.mock();
        world = new WorldMock() {
            @Override public org.bukkit.util.RayTraceResult rayTraceBlocks(Location from, org.bukkit.util.Vector direction,
                    double distance, org.bukkit.FluidCollisionMode fluid, boolean ignorePassable) {
                return blockedSwim ? new org.bukkit.util.RayTraceResult(from.toVector()) : null;
            }
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

    @Test void choosesDryShoreAndMovesTowardsOwnerOnlyWhenTheirSpotIsSafe() {
        Location from = new Location(world, 2.5, 62, 2.5);
        block(4, 63, 2, Material.STONE);
        block(7, 63, 2, Material.STONE);
        Location shore = new Location(world, 4.5, 64, 2.5);
        Location owner = new Location(world, 7.5, 64, 2.5);
        assertEquals(shore, WaterEscape.exit(from, null));
        assertEquals(owner, WaterEscape.exit(from, owner));
        block(7, 64, 2, Material.WATER);
        assertEquals(shore, WaterEscape.exit(from, owner));
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
        assertNull(WaterEscape.exit(new Location(world, 2.5, 62, 2.5), null));
    }

    @Test void skipsAnUnreachablePreferredAndNearestExitForAnotherBank() {
        var from = new Location(world, 2.5, 62, 2.5);
        block(4, 63, 2, Material.STONE);
        block(7, 63, 2, Material.STONE);
        var enclosed = new Location(world, 4.5, 64, 2.5);
        var reachable = new Location(world, 7.5, 64, 2.5);
        assertEquals(reachable, WaterEscape.exit(from, enclosed, reachable::equals));
        assertNull(WaterEscape.exit(from, enclosed, candidate -> false));
    }

    @Test void partialNativePathFallsBackToClearSwimmingButCannotReachRaisedPlatformOrWall() {
        var server = (org.mockbukkit.mockbukkit.ServerMock) org.bukkit.Bukkit.getServer();
        var body = new org.mockbukkit.mockbukkit.entity.WolfMock(server, java.util.UUID.randomUUID()) {
            @Override public boolean isInWater() { return true; }
            @Override public com.destroystokyo.paper.entity.Pathfinder getPathfinder() {
                return (com.destroystokyo.paper.entity.Pathfinder) java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[]{com.destroystokyo.paper.entity.Pathfinder.class},
                        (p, m, a) -> (com.destroystokyo.paper.entity.Pathfinder.PathResult) java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(), new Class<?>[]{com.destroystokyo.paper.entity.Pathfinder.PathResult.class},
                                (path, method, args) -> method.getName().equals("canReachFinalPoint") ? false : null));
            }
        };
        body.teleport(new Location(world, 2.5, 64, 2.5));
        block(4, 63, 2, Material.STONE); block(2, 67, 2, Material.STONE);
        var shore = new Location(world, 4.5, 64, 2.5);
        var platform = new Location(world, 2.5, 68, 2.5);
        assertTrue(WaterEscape.reachable(body, shore));
        assertFalse(WaterEscape.reachable(body, platform));
        assertEquals(shore, WaterEscape.exit(body.getLocation(), platform, at -> WaterEscape.reachable(body, at)));
        blockedSwim = true;
        assertFalse(WaterEscape.reachable(body, shore));
    }

    @Test void exhaustedNativeRouteBudgetStillChecksTheCheapSwimWithoutFindingAPath() {
        var body = org.mockito.Mockito.mock(org.bukkit.entity.Mob.class);
        var from = new Location(world, 2.5, 64, 2.5);
        org.mockito.Mockito.when(body.getWorld()).thenReturn(world);
        org.mockito.Mockito.when(body.getLocation()).thenReturn(from);
        org.mockito.Mockito.when(body.getEyeLocation()).thenReturn(from.clone().add(0, 1, 0));
        org.mockito.Mockito.when(body.getEyeHeight()).thenReturn(1.0);
        org.mockito.Mockito.when(body.isInWater()).thenReturn(true);
        block(4, 63, 2, Material.STONE);
        var shore = new Location(world, 4.5, 64, 2.5);
        assertTrue(WaterEscape.reachable(body, shore, at -> false));
        blockedSwim = true;
        assertFalse(WaterEscape.reachable(body, shore, at -> false));
        assertTrue(WaterEscape.reachable(body, shore, at -> true));
        org.mockito.Mockito.verify(body, org.mockito.Mockito.never()).getPathfinder();
    }
}
