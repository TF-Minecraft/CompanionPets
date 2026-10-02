package net.tfminecraft.companionpets.staff;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import net.tfminecraft.companionpets.item.ItemRef;

class EggDeliveryTest {
    @AfterEach void teardown() { MockBukkit.unmock(); }
    @Test void mmoItemsEggUsesItsGiveCommandFromConsoleWithConfiguredIdentity() {
        var server = MockBukkit.mock(); server.addSimpleWorld("world"); var player = server.addPlayer("Owner");
        var calls = new java.util.ArrayList<String>();
        server.getCommandMap().register("mmoitems", new Command("mi") {
            @Override public boolean execute(CommandSender sender, String label, String[] args) {
                assertSame(server.getConsoleSender(), sender); calls.add(String.join(" ", args)); return true;
            }
        });
        assertTrue(EggDelivery.deliver(ItemRef.parse("mmoitems:PETS:BEAGLE_EGG"), new ItemStack(Material.WOLF_SPAWN_EGG), player, 3));
        assertEquals(java.util.List.of("give PETS BEAGLE_EGG Owner 3"), calls);
        assertNull(player.getInventory().getItem(0), "MMOItems handles delivery; there is no second API-created egg");
    }
}
