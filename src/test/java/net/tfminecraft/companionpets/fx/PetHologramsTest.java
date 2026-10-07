package net.tfminecraft.companionpets.fx;

import static org.junit.jupiter.api.Assertions.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;

class PetHologramsTest {
    ServerMock server;
    @BeforeEach void start() { server=MockBukkit.mock(); server.addSimpleWorld("world"); }
    @AfterEach void stop() { MockBukkit.unmock(); }
    @Test void sleepLabelIsReusedAndRemovedWhenItsPetDisappears() {
        var plugin=MockBukkit.createMockPlugin(); var labels=new PetHolograms(plugin);
        var pet=server.addPlayer(); pet.teleport(new Location(server.getWorlds().getFirst(),0,64,0));
        labels.sleep(null,true); assertTrue(pet.getWorld().getEntitiesByClass(ArmorStand.class).isEmpty());
        labels.sleep(pet,true); var stands=pet.getWorld().getEntitiesByClass(ArmorStand.class);
        assertEquals(1,stands.size()); var label=stands.iterator().next();
        labels.sleep(pet,true); assertEquals(1,pet.getWorld().getEntitiesByClass(ArmorStand.class).size());
        assertEquals(Component.text("Sleeping",net.kyori.adventure.text.format.NamedTextColor.GRAY),label.customName());
        label.remove(); server.getScheduler().performTicks(2);
        assertDoesNotThrow(() -> server.getScheduler().performTicks(10));
        labels.sleep(pet,true); labels.sleep(pet,false);
        assertTrue(pet.getWorld().getEntitiesByClass(ArmorStand.class).isEmpty());
        labels.clear();
    }
}
