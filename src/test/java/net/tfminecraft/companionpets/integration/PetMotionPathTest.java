package net.tfminecraft.companionpets.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import com.destroystokyo.paper.entity.Pathfinder;
import net.minecraft.world.level.pathfinder.PathType;
import org.bukkit.Location;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.WolfMock;

class PetMotionPathTest {
    private ServerMock server;
    private Pathfinder path;
    private Location target;

    @BeforeEach void setup() {
        server = MockBukkit.mock();
        path = mock(Pathfinder.class);
        target = new Location(server.addSimpleWorld("water"), 10, 64, 0);
    }
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    @Test void allRouteVariantsUseZeroWaterCostAndRestoreTheOriginal() {
        var body = new NativeWolf(server, path);
        var route = mock(Pathfinder.PathResult.class);
        when(path.moveTo(target, 1.2)).thenAnswer(invocation -> {
            assertEquals(0F, body.handle.malus); return true;
        });
        when(path.findPath(target)).thenAnswer(invocation -> {
            assertEquals(0F, body.handle.malus); return route;
        });
        when(path.moveTo(route, 1.2)).thenAnswer(invocation -> {
            assertEquals(0F, body.handle.malus); return true;
        });
        assertTrue(PetMotion.moveTo(body, target, 1.2)); assertEquals(8F, body.handle.malus);
        body.handle.malus = 3F;
        assertSame(route, PetMotion.findPath(body, target)); assertEquals(3F, body.handle.malus);
        assertTrue(PetMotion.moveTo(body, route, 1.2)); assertEquals(3F, body.handle.malus);
    }

    @Test void navigationExceptionsPropagateAfterRestoringWaterCost() {
        var body = new NativeWolf(server, path);
        var failure = new IllegalStateException("pathfinder failed");
        when(path.moveTo(target, 1.1)).thenAnswer(invocation -> {
            assertEquals(0F, body.handle.malus); throw failure;
        });
        assertSame(failure, assertThrows(IllegalStateException.class, () -> PetMotion.moveTo(body, target, 1.1)));
        assertEquals(8F, body.handle.malus);
        when(path.findPath(target)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> PetMotion.findPath(body, target)));
        assertEquals(8F, body.handle.malus);
        var route = mock(Pathfinder.PathResult.class);
        when(path.moveTo(route, 1.1)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> PetMotion.moveTo(body, route, 1.1)));
        assertEquals(8F, body.handle.malus);
    }

    @Test void absentOrIncompleteNativeAccessFallsBackToPaper() {
        when(path.moveTo(target, 1.1)).thenReturn(true);
        var plain = new WolfMock(server, UUID.randomUUID()) {
            @Override public Pathfinder getPathfinder() { return path; }
        };
        assertTrue(PetMotion.moveTo(plain, target, 1.1));
        assertTrue(PetMotion.moveTo(new IncompleteWolf(server, path), target, 1.1));
        var body = new NativeWolf(server, path); body.fail = true;
        assertTrue(PetMotion.moveTo(body, target, 1.1));
        body.fail = false; body.handle.failRead = true;
        assertTrue(PetMotion.moveTo(body, target, 1.1));
        body.handle.failRead = false; body.handle.failWrite = true;
        assertTrue(PetMotion.moveTo(body, target, 1.1));
        assertEquals(8F, body.handle.malus);
        verify(path, times(5)).moveTo(target, 1.1);
        var route = mock(Pathfinder.PathResult.class);
        when(path.findPath(target)).thenReturn(route);
        when(path.moveTo(route, 1.1)).thenReturn(true);
        assertSame(route, PetMotion.findPath(plain, target));
        assertTrue(PetMotion.moveTo(plain, route, 1.1));
    }

    @Test void restorationFailureDoesNotHideTheNavigationResult() {
        var body = new NativeWolf(server, path);
        when(path.moveTo(target, 1.1)).thenAnswer(invocation -> {
            body.handle.failWrite = true; return false;
        });
        assertFalse(PetMotion.moveTo(body, target, 1.1));
    }

    public static class NativeWolf extends WolfMock {
        final Handle handle = new Handle();
        final Pathfinder path;
        boolean fail;
        NativeWolf(ServerMock server, Pathfinder path) { super(server, UUID.randomUUID()); this.path = path; }
        public Handle getHandle() { if (fail) throw new IllegalStateException("unloaded"); return handle; }
        @Override public Pathfinder getPathfinder() { return path; }
    }
    public static class Handle {
        float malus = 8F;
        boolean failRead, failWrite;
        public float getPathfindingMalus(PathType type) {
            if (failRead) throw new IllegalStateException("read failed"); return malus;
        }
        public void setPathfindingMalus(PathType type, float value) {
            if (failWrite) throw new IllegalStateException("write failed"); malus = value;
        }
    }
    public static class IncompleteWolf extends WolfMock {
        final Pathfinder path;
        IncompleteWolf(ServerMock server, Pathfinder path) { super(server, UUID.randomUUID()); this.path = path; }
        public Object getHandle() { return new Object(); }
        @Override public Pathfinder getPathfinder() { return path; }
    }
}
