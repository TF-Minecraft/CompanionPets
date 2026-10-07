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
    @Test void oneTaskFollowsEveryLabelAndStopsWhenNoneRemain() throws Exception {
        var plugin=MockBukkit.createMockPlugin(); var labels=new PetHolograms(plugin);
        var world=server.getWorlds().getFirst();
        var first=server.addPlayer(); first.teleport(new Location(world,0,64,0));
        var second=server.addPlayer(); second.teleport(new Location(world,8,64,0));
        labels.sleep(first,true); var follower=follower(labels);
        labels.show(second,Component.text("Sit"),100);
        assertSame(follower,follower(labels),"labels share a single follower");
        assertFalse(follower.isCancelled());
        first.teleport(new Location(world,3,64,0)); server.getScheduler().performTicks(2);
        var stand=world.getEntitiesByClass(ArmorStand.class).stream()
                .filter(label->label.getLocation().getX()<6).findFirst().orElseThrow();
        assertEquals(3,stand.getLocation().getX(),"a moving pet's label follows it");
        labels.sleep(first,false); assertFalse(follower.isCancelled());
        server.getScheduler().performTicks(100);
        assertTrue(world.getEntitiesByClass(ArmorStand.class).isEmpty());
        assertTrue(follower.isCancelled(),"no task remains without labels");
        assertNull(follower(labels));
    }
    private static org.bukkit.scheduler.BukkitTask follower(PetHolograms labels) throws Exception {
        var field=PetHolograms.class.getDeclaredField("follower"); field.setAccessible(true);
        return (org.bukkit.scheduler.BukkitTask) field.get(labels);
    }
}
