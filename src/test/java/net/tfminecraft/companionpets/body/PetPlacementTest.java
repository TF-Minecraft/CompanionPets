package net.tfminecraft.companionpets.body;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class PetPlacementTest {
    private org.mockbukkit.mockbukkit.world.WorldMock world() {
        var world = new net.tfminecraft.companionpets.testutil.CollisionWorldMock() {
            private final java.util.Map<String, org.mockbukkit.mockbukkit.block.BlockMock> blocks = new java.util.HashMap<>();
            @Override public org.mockbukkit.mockbukkit.block.BlockMock getBlockAt(int x, int y, int z) {
                return blocks.computeIfAbsent(x + ":" + y + ":" + z, key ->
                        new org.mockbukkit.mockbukkit.block.BlockMock(Material.AIR, new Location(this, x, y, z)) {
                            @Override public boolean isPassable() { return !getType().isSolid(); }
                        });
            }
        };
        ((org.mockbukkit.mockbukkit.ServerMock) org.bukkit.Bukkit.getServer()).addWorld(world);
        return world;
    }
    @AfterEach void close() { MockBukkit.unmock(); }
    @Test void furnitureExitPrefersBehindAndChecksTheWholeScaledBox() {
        var server = MockBukkit.mock(); var world = world();
        var player = server.addPlayer(); player.teleport(new Location(world, .5, 4, .5));
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) world.getBlockAt(x, 3, z).setType(Material.STONE);
        Location house = new Location(world, .5, 4, 2.5);
        world.getBlockAt(0, 4, 2).setType(Material.BARRIER);
        var bounds = PetPlacement.normal(2);
        Location selected = PetPlacement.beside(player, bounds, house);
        assertNotNull(selected); assertTrue(selected.getZ() < player.getZ());
        assertEquals(.5, selected.getX() - Math.floor(selected.getX()));
        assertTrue(PetPlacement.safe(selected, bounds));
        world.getBlockAt(1, 4, -2).setType(Material.STONE);
        assertFalse(PetPlacement.safe(new Location(world, .5, 4, -1.5), bounds), "A clear center cannot hide a colliding edge");
        var next = PetPlacement.nearest(selected, bounds, house);
        assertNotNull(next); assertTrue(PetPlacement.safe(next, bounds));
    }
    @Test void noSolidFloorOrNoRoomRefusesToSpawn() {
        var server = MockBukkit.mock(); var world = world();
        Location at = new Location(world, .5, 100, .5);
        assertNull(PetPlacement.nearest(at, PetPlacement.normal(1), null));
        world.getBlockAt(0, 99, 0).setType(Material.STONE);
        assertTrue(PetPlacement.safe(at, PetPlacement.normal(1)));
        world.getBlockAt(0, 100, 0).setType(Material.STONE);
        assertFalse(PetPlacement.safe(at, PetPlacement.normal(1)));
    }
}
