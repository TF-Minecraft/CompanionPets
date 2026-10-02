package net.tfminecraft.companionpets.integration;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;

class WolfShakeTest {
    @AfterEach void teardown() { MockBukkit.unmock(); }
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
        public float getShakeAnim(float partialTick) { return progress; }
    }
}
