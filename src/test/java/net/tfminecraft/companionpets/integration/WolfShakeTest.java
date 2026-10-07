package net.tfminecraft.companionpets.integration;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;

class WolfShakeTest {
    @AfterEach void teardown() { MockBukkit.unmock(); }
    @Test void fetchCancelsNativeShakeAndFinishesDryWithoutASecondShake() {
        var server = MockBukkit.mock(); var wolf = new NativeWolfFixture(server);
        wolf.handle.isWet = true; wolf.handle.progress = 0.7F;
        assertTrue(WolfShake.defer(wolf));
        assertFalse(wolf.handle.isWet); assertFalse(WolfShake.shaking(wolf));
        assertEquals(java.util.List.of((byte) 56), wolf.handle.level.events);
        assertTrue(WolfShake.defer(wolf));
        WolfShake.restore(wolf); assertFalse(wolf.handle.isWet);
        wolf.handle.isWet = false;
        WolfShake.restore(wolf); assertFalse(wolf.handle.isWet, "Finishing a race must not restart shaking repeatedly");
    }
    @Test void detectsNativeShakeStartAndEndInsteadOfWaitingForDryness() {
        var server = MockBukkit.mock();
        var wolf = new NativeWolfFixture(server);
        assertFalse(WolfShake.shaking(wolf));
        wolf.handle.progress = 0.05F; assertTrue(WolfShake.shaking(wolf));
        wolf.handle.progress = 1.9F; assertTrue(WolfShake.shaking(wolf));
        wolf.handle.progress = 0; assertFalse(WolfShake.shaking(wolf));
        assertFalse(WolfShake.shaking(new WolfMock(server, UUID.randomUUID())), "An unsupported server must not substitute an animation at the wrong time");
    }
    public static class NativeWolfFixture extends WolfMock {
        final NativeClock handle = new NativeClock();
        NativeWolfFixture(ServerMock server) { super(server, UUID.randomUUID()); }
        public NativeClock getHandle() { return handle; }
    }
    public static class NativeClock {
        float progress;
        private boolean isWet;
        final NativeLevel level = new NativeLevel();
        public float getShakeAnim(float partialTick) { return progress; }
        public void handleEntityEvent(byte event) { if (event == 56) progress = 0; }
        public NativeLevel level() { return level; }
    }
    public static class NativeLevel {
        final java.util.List<Byte> events = new java.util.ArrayList<>();
        public void broadcastEntityEvent(NativeClock wolf, byte event) { events.add(event); }
    }
}
