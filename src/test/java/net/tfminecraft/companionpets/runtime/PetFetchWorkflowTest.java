package net.tfminecraft.companionpets.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.SnowballMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.item.ToyItems;
import net.tfminecraft.companionpets.listen.PetListener;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.play.FetchJob;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.visual.IdleVisual;

class PetFetchWorkflowTest {
    private ServerMock server;
    private PetRuntime runtime;
    private PetActions actions;
    private PetListener listener;
    private PlayerMock owner;
    private Pet pet;
    private WorldMock world;
    private ItemStack toy;

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(); var plugin = MockBukkit.createMockPlugin();
        world = new WorldMock() {
            @Override public BlockMock getBlockAt(int x, int y, int z) {
                return new BlockMock(new Location(this, x, y, z)) { @Override public boolean isPassable() { return true; } };
            }
        };
        server.addWorld(world); owner = server.addPlayer(); owner.teleport(new Location(world, 0, 64, 0));
        var yaml = new YamlConfiguration(); yaml.loadFromString("items: {toys: [STICK]}\npets: {wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG}}\n");
        var visual = new IdleVisual(); var key = new NamespacedKey(plugin, "pet");
        var store = new PetStore(plugin); assertTrue(store.load());
        runtime = new PetRuntime(plugin, CompanionConfig.load(plugin, yaml), store, new Sessions(), new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        actions = new PetActions(runtime); listener = new PetListener(runtime, actions);
        pet = new Pet(UUID.randomUUID(), owner.getUniqueId(), "wolf", "Toby", PetSex.MALE); runtime.store().add(pet);
        toy = new ItemStack(Material.STICK);
        var meta = toy.getItemMeta(); meta.setCustomModelData(4321);
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "test-toy-data"), PersistentDataType.STRING, "original"); toy.setItemMeta(meta);
    }
    @AfterEach void teardown() { MockBukkit.unmock(); }
    private SnowballMock projectile() {
        var ball = new SnowballMock(server, UUID.randomUUID()); server.registerEntity(ball); ball.teleport(owner.getLocation());
        ball.getPersistentDataContainer().set(runtime.toyKey(), PersistentDataType.STRING, pet.id() + "|" + ToyItems.encode(toy));
        return ball;
    }
    private java.util.List<Item> items() { return world.getEntities().stream().filter(Item.class::isInstance).map(Item.class::cast).toList(); }

    @Test void currentProjectileProducesOneTaggedToyWithOriginalData() {
        var ball = projectile(); var job = new FetchJob(ToyItems.encode(toy), false); job.projectileId(ball.getUniqueId()); pet.fetch(job);
        listener.onToyHit(new ProjectileHitEvent(ball));
        assertTrue(ball.isDead()); assertEquals(1, items().size()); assertTrue(toy.isSimilar(items().getFirst().getItemStack()));
        assertEquals(items().getFirst().getUniqueId(), pet.fetch().itemId()); assertNull(pet.fetch().projectileId());
    }

    @Test void supersededProjectileCannotDropOrReplaceCurrentToy() {
        var old = projectile(); var current = projectile();
        var job = new FetchJob(ToyItems.encode(toy), false); job.projectileId(current.getUniqueId()); pet.fetch(job);
        listener.onToyHit(new ProjectileHitEvent(old));
        assertTrue(old.isDead()); assertTrue(items().isEmpty()); assertEquals(current.getUniqueId(), pet.fetch().projectileId());
    }

    @Test void staleProjectileAfterToyWasReturnedCannotDuplicateIt() {
        var ball = projectile();
        pet.carriedToy(ToyItems.encode(toy)); pet.fetch(null);
        listener.onToyHit(new ProjectileHitEvent(ball));
        assertTrue(ball.isDead()); assertTrue(items().isEmpty()); assertTrue(toy.isSimilar(ToyItems.decode(pet.carriedToy())));
    }

    @Test void offlineReturnStoresToyAndRemovesGroundCopy() {
        var job = new FetchJob(ToyItems.encode(toy), false); pet.fetch(job);
        actions.dropToy(owner.getLocation(), pet, job.toy()); assertEquals(1, items().size());
        actions.releaseFetch(pet, null, false);
        assertNull(pet.fetch()); assertTrue(items().isEmpty()); assertTrue(toy.isSimilar(ToyItems.decode(pet.carriedToy())));
        assertTrue(runtime.store().save()); var loaded = new PetStore(runtime.plugin()); assertTrue(loaded.load());
        assertTrue(toy.isSimilar(ToyItems.decode(loaded.get(pet.id()).carriedToy())));
    }

    @Test void onlineReturnDropsOnePlainToyAndSecondReturnDoesNothing() {
        var job = new FetchJob(ToyItems.encode(toy), false); pet.fetch(job);
        actions.releaseFetch(pet, owner, true); actions.releaseFetch(pet, owner, true);
        assertEquals(1, items().size()); assertTrue(toy.isSimilar(items().getFirst().getItemStack()));
        assertFalse(items().getFirst().getPersistentDataContainer().has(runtime.toyKey())); assertNull(pet.fetch());
    }
}
