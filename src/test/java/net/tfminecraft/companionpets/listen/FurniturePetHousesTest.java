package net.tfminecraft.companionpets.listen;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.gui.MenuHolder;
import net.tfminecraft.companionpets.runtime.PetActions;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.visual.IdleVisual;

class FurniturePetHousesTest {
    private PetRuntime runtime;
    private FurniturePetHouses petHouses;
    private PlayerMock owner;
    private Entity furniture;
    private String key;
    private YamlConfiguration yaml;

    @BeforeEach void setup() throws Exception {
        var server = MockBukkit.mock();
        var world = server.addSimpleWorld("world");
        var plugin = MockBukkit.createMockPlugin();
        owner = server.addPlayer();
        furniture = server.addPlayer();
        furniture.teleport(new Location(world, -2.5, 65, 3.5));
        key = PetStore.kennelKey("world", -3, 65, 3);
        yaml = new YamlConfiguration();
        yaml.loadFromString("""
                items:
                  kennel: "itemsadder:tfmc:pet_house"
                  kennel-furniture: "tfmc:pet_house"
                  kennel-block: BARREL
                pets:
                  wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG}
                """);
        var visual = new IdleVisual();
        var petKey = new NamespacedKey(plugin, "pet");
        runtime = new PetRuntime(plugin, CompanionConfig.load(plugin, yaml), new PetStore(plugin), new Sessions(),
                new Bodies(plugin, petKey, visual), visual, petKey, new NamespacedKey(plugin, "toy"));
        assertTrue(runtime.store().load());
        petHouses = new FurniturePetHouses(runtime);
    }

    @AfterEach void cleanup() { MockBukkit.unmock(); }

    @Test void successfulPlacementPersistsOwnerAndOnlyOwnerCanOpen() {
        petHouses.placed(event(owner));
        assertEquals(owner.getUniqueId(), runtime.store().kennelOwner(key));
        var restored = new PetStore(runtime.plugin());
        assertTrue(restored.load());
        assertEquals(owner.getUniqueId(), restored.kennelOwner(key));
        var stranger = MockBukkit.getMock().addPlayer();
        var denied = event(stranger);
        petHouses.interact(denied);
        assertTrue(denied.isCancelled());
        assertNull(stranger.getOpenInventory().getTopInventory());
        var click = event(owner);
        petHouses.interact(click);
        assertTrue(click.isCancelled());
        assertEquals(MenuHolder.Kind.KENNEL, ((MenuHolder) owner.getOpenInventory().getTopInventory().getHolder()).kind());
    }

    @Test void cancelledEventsAndOtherFurnitureDoNotChangeOwnership() {
        petHouses.placed(event(null));
        assertNull(runtime.store().kennelOwner(key), "Furniture spawned through the IA API has no player owner");
        var cancelled = event(owner); cancelled.setCancelled(true);
        petHouses.placed(cancelled);
        assertNull(runtime.store().kennelOwner(key));
        var other = new FurnitureEvent(owner, furniture, "tfmc:animal_station");
        petHouses.placed(other);
        assertNull(runtime.store().kennelOwner(key));
        petHouses.placed(event(owner));
        petHouses.broken(cancelled);
        petHouses.broken(other);
        assertEquals(owner.getUniqueId(), runtime.store().kennelOwner(key));
        petHouses.broken(event(owner));
        assertNull(runtime.store().kennelOwner(key));
    }

    @Test void leftClicksAndUnownedFurnitureDoNotOpenMenus() {
        var click = event(owner);
        petHouses.interact(click);
        assertFalse(click.isCancelled());
        petHouses.placed(event(owner));
        click.action = Action.LEFT_CLICK_BLOCK;
        petHouses.interact(click);
        assertFalse(click.isCancelled());
        click.action = Action.RIGHT_CLICK_AIR;
        petHouses.interact(click);
        assertTrue(click.isCancelled());
    }

    @Test void furniturePlacementIsLeftToItemsAdderAndLegacyBarrelsStillOpen() {
        var block = furniture.getLocation().getBlock();
        var actions = new PetActions(runtime);
        assertFalse(actions.handledWorld(owner, new ItemStack(Material.PAPER), block,
                org.bukkit.block.BlockFace.UP, true, false));
        block.setType(Material.BARREL);
        runtime.store().kennel(key, owner.getUniqueId());
        assertTrue(actions.handledWorld(owner, new ItemStack(Material.AIR), block,
                org.bukkit.block.BlockFace.UP, false, false));
        yaml.set("items.kennel-furniture", null);
        runtime.config(CompanionConfig.load(runtime.plugin(), yaml));
        var placement = event(owner);
        petHouses.placed(placement);
        assertNull(runtime.config().kennelFurniture());
    }

    @Test void configRejectsVanillaFurnitureAndMismatchedHeldItem() {
        yaml.set("items.kennel-furniture", "BARREL");
        assertThrows(IllegalArgumentException.class, () -> CompanionConfig.load(runtime.plugin(), yaml));
        yaml.set("items.kennel-furniture", "tfmc:animal_station");
        assertThrows(IllegalArgumentException.class, () -> CompanionConfig.load(runtime.plugin(), yaml));
    }

    private FurnitureEvent event(Player player) { return new FurnitureEvent(player, furniture, "tfmc:pet_house"); }

    public static final class FurnitureEvent extends PlayerEvent implements Cancellable {
        private final Entity entity;
        private final String id;
        private boolean cancelled;
        private Action action = Action.RIGHT_CLICK_BLOCK;
        public FurnitureEvent(Player player, Entity entity, String id) { super(player); this.entity = entity; this.id = id; }
        public String getNamespacedID() { return id; }
        public Entity getBukkitEntity() { return entity; }
        public Action getAction() { return action; }
        public boolean isCancelled() { return cancelled; }
        public void setCancelled(boolean value) { cancelled = value; }
        public HandlerList getHandlers() { return new HandlerList(); }
    }
}
