package net.tfminecraft.companionpets.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.entity.CatMock;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.Trick;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.visual.PetVisual;

class TestPetSpawnTest {
    private PetRuntime runtime;
    private PetActions actions;
    private PlayerMock player;
    private boolean retargetSpawn;
    private Entity lastSpawned;

    @BeforeEach void setup() throws Exception {
        var server = MockBukkit.mock(new net.tfminecraft.companionpets.testutil.GoalServerMock());
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        var world = new net.tfminecraft.companionpets.testutil.CollisionWorldMock() {
            @Override public <T extends Entity> T spawn(Location at, Class<T> type) {
                Entity body;
                if (type == org.bukkit.entity.Wolf.class) {
                    body = new WolfMock(server, java.util.UUID.randomUUID()) {
                        @Override public void setRemoveWhenFarAway(boolean remove) { }
                        @Override public boolean teleport(Location to, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause) {
                            return super.teleport(retargetSpawn ? to.clone().subtract(0, 1, 0) : to, cause);
                        }
                    };
                } else if (type == org.bukkit.entity.Cat.class) {
                    body = new CatMock(server, java.util.UUID.randomUUID()) {
                        @Override public void setRemoveWhenFarAway(boolean remove) { }
                    };
                } else return super.spawn(at, type);
                server.registerEntity((org.mockbukkit.mockbukkit.entity.EntityMock) body);
                lastSpawned = body;
                body.teleport(at);
                return type.cast(body);
            }
            @Override public BlockMock getBlockAt(int x, int y, int z) {
                return new BlockMock(y == 63 ? org.bukkit.Material.STONE : org.bukkit.Material.AIR, new Location(this, x, y, z)) {
                    @Override public boolean isPassable() { return y != 63; }
                };
            }
        };
        server.addWorld(world);
        player = server.addPlayer();
        player.teleport(new Location(world, 0, 64, 0));
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                limits: {max-out: 2}
                custom-tricks:
                  tongue: {animation: tongue}
                  croak: {animation: croak}
                  missing: {animation: absent}
                  salute: {fallback-text: Hello}
                  follow: {fallback-text: Custom follow trick}
                pets:
                  wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG}
                  performer:
                    entity: CAT
                    egg: FROG_SPAWN_EGG
                    tricks: [come, stay, lay, jump, tongue, croak, missing, salute]
                """);
        var config = CompanionConfig.load(plugin, yaml);
        PetVisual visual = new PetVisual() {
            @Override public void apply(Entity entity, PetTypeDef type) { }
            @Override public boolean hasClip(Entity entity, PetTypeDef type, String clip) {
                return type.id().equals("performer") && Set.of("tongue", "croak").contains(clip);
            }
        };
        var petKey = new NamespacedKey(plugin, "pet");
        runtime = new PetRuntime(plugin, config, loadedStore(plugin), new Sessions(),
                new Bodies(plugin, petKey, visual), visual, petKey, new NamespacedKey(plugin, "toy"));
        actions = new PetActions(runtime);
    }

    private static PetStore loadedStore(org.bukkit.plugin.java.JavaPlugin plugin) {
        var store = new PetStore(plugin);
        assertTrue(store.load());
        return store;
    }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    private Pet onlyPet() { return runtime.store().all().iterator().next(); }

    @Test void choosesConfiguredTypeAndLearnsCompatibleCustomTricks() {
        assertTrue(actions.spawnTestPet(player, "PERFORMER", "  Michi   de prueba "));
        Pet pet = onlyPet();
        assertEquals("performer", pet.typeId());
        assertEquals("Michi de prueba", pet.name());
        assertEquals(EntityType.CAT, runtime.entity(pet).getType());
        for (String word : Set.of("follow", "stay", "lay", "jump", "tongue", "croak", "salute")) {
            assertEquals(100, pet.progress(Trick.valueOf(word)));
            assertEquals(Trick.valueOf(word), pet.trickFor(word));
        }
        assertEquals(Trick.FOLLOW, pet.trickFor("follow"));
        assertNull(pet.trickFor("missing"));
        assertNull(pet.trickFor("paw"));
        assertFalse(pet.stored());
        assertEquals(player.getUniqueId(), pet.ownerId());
    }

    @Test void unknownTypeCreatesNoPetOrBody() {
        long before = player.getWorld().getEntities().size();
        assertFalse(actions.spawnTestPet(player, "unknown", "Test"));
        assertTrue(runtime.store().all().isEmpty());
        assertEquals(before, player.getWorld().getEntities().size());
    }

    @Test void lateRetargetIntoSolidBlocksRejectsTheSpawnWithoutSavingOrProtectingTheBody() {
        retargetSpawn = true;
        assertFalse(actions.spawnTestPet(player, "wolf", "Blocked"));
        assertTrue(runtime.store().all().isEmpty());
        assertNotNull(lastSpawned);
        assertFalse(lastSpawned.isValid(), "The unsafe new body is removed");
        assertFalse(runtime.bodies().protectedFromSuffocation(lastSpawned));
    }

    @Test void changingConfiguredBaseReplacesBodyAndPreservesPetIdentityAndCare() {
        assertTrue(actions.spawnTestPet(player, "wolf", "Toby"));
        Pet pet = onlyPet(); Entity original = runtime.entity(pet);
        pet.need(net.tfminecraft.companionpets.pet.Need.HUNGER, 42);
        pet.progress(Trick.SPEAK, 71); pet.bindWord("hello", Trick.SPEAK);
        var yaml = new YamlConfiguration(); yaml.set("pets.wolf.entity", "CAT"); yaml.set("pets.wolf.egg", "WOLF_SPAWN_EGG");
        runtime.config(CompanionConfig.load(runtime.plugin(), yaml)); actions.reattach(original);
        assertSame(pet, onlyPet()); assertFalse(original.isValid());
        assertEquals(EntityType.CAT, runtime.entity(pet).getType());
        assertEquals(42, pet.need(net.tfminecraft.companionpets.pet.Need.HUNGER));
        assertEquals(71, pet.progress(Trick.SPEAK)); assertEquals(Trick.SPEAK, pet.trickFor("hello"));
        assertEquals(player.getUniqueId(), ((org.bukkit.entity.Tameable) runtime.entity(pet)).getOwner().getUniqueId());
        actions.reattach(original); assertEquals(EntityType.CAT, runtime.entity(pet).getType());
        assertEquals(1, runtime.store().all().size(), "Late old body never duplicates its replacement");
    }

    @Test void followAliasDoesNotOverwriteConfiguredCustomTrick() {
        assertTrue(actions.spawnTestPet(player, "wolf", "Test"));
        Pet pet = onlyPet();
        assertEquals(Trick.valueOf("follow"), pet.trickFor("follow"));
        assertEquals(Trick.FOLLOW, pet.trickFor("follow"));
        assertEquals(100, pet.progress(Trick.valueOf("follow")));
    }

    @Test void defaultsNameAndHonorsActivePetLimit() {
        assertTrue(actions.spawnTestPet(player, "performer", ""));
        assertEquals("performer", onlyPet().name());
        assertTrue(actions.spawnTestPet(player, "wolf", "Second"));
        assertFalse(actions.spawnTestPet(player, "performer", "Third"));
        assertEquals(2, runtime.store().all().size());
    }

    @Test void spawnUsesConfiguredWolfTypeAndNames() {
        assertTrue(actions.spawnTestPet(player, "wolf", ""));
        assertEquals("wolf", onlyPet().typeId());
        assertEquals("wolf", onlyPet().name());
        assertTrue(actions.spawnTestPet(player, "wolf", "Beagle called Toby"));
        assertTrue(runtime.store().all().stream().anyMatch(pet -> pet.name().equals("Beagle called Toby")
                && pet.typeId().equals("wolf")));
    }
}
