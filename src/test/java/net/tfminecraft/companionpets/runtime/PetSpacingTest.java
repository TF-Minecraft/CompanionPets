package net.tfminecraft.companionpets.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.visual.PetVisual;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class PetSpacingTest {
    private final Map<UUID, Integer> lookups = new HashMap<>();
    private ServerMock server;
    private WorldMock world, elsewhere;
    private PlayerMock owner;
    private PetRuntime runtime;
    private boolean floor = true;

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(new ServerMock() {
            @Override public Entity getEntity(UUID id) {
                lookups.merge(id, 1, Integer::sum);
                return super.getEntity(id);
            }
        });
        world = new WorldMock() {
            @Override public boolean isChunkLoaded(int x, int z) { return true; }
            @Override public BlockMock getBlockAt(int x, int y, int z) {
                return new BlockMock(floor && y == 63 ? Material.STONE : Material.AIR, new Location(this, x, y, z)) {
                    @Override public boolean isPassable() { return !getType().isSolid(); }
                };
            }
        };
        server.addWorld(world); elsewhere = server.addSimpleWorld("elsewhere");
        owner = server.addPlayer(); owner.teleport(new Location(world, 0, 64, 0));
        var plugin = MockBukkit.createMockPlugin();
        var yaml = new YamlConfiguration();
        yaml.loadFromString("pets: {wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG}}\n");
        var visual = new PetVisual() {
            @Override public void apply(Entity entity, PetTypeDef type) { }
        };
        var key = new NamespacedKey(plugin, "pet");
        var store = new PetStore(plugin); assertTrue(store.load());
        runtime = new PetRuntime(plugin, CompanionConfig.load(plugin, yaml), store, new Sessions(),
                new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
    }

    @AfterEach void cleanup() { runtime.store().close(); MockBukkit.unmock(); }

    private Pet record(UUID id, UUID ownerId, Location at) {
        Pet pet = new Pet(id, ownerId, "wolf", "Friend", PetSex.MALE);
        pet.place(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw());
        pet.entityId(UUID.randomUUID()); runtime.store().add(pet);
        return pet;
    }

    private Pet pet(long id, Location at, double width) {
        Pet pet = record(new UUID(0, id), owner.getUniqueId(), at);
        var body = new WolfMock(server, UUID.randomUUID()) {
            @Override public double getWidth() { return width; }
        };
        server.registerEntity(body); body.teleport(at); runtime.remember(pet, body);
        return pet;
    }

    @Test void distantAndOtherWorldRecordsNeverLookUpTheirEntities() {
        Location at = new Location(world, 10, 64, -20);
        Pet self = pet(1, at, 0.6);
        var skipped = new ArrayList<Pet>();
        for (int i = 0; i < 250; i++) {
            skipped.add(record(UUID.randomUUID(), UUID.randomUUID(), at.clone().add(100 + i, 0, 0)));
            skipped.add(record(UUID.randomUUID(), UUID.randomUUID(), at.clone().add(0, 0, -100 - i)));
            skipped.add(record(UUID.randomUUID(), UUID.randomUUID(), new Location(elsewhere, at.getX(), 64, at.getZ())));
        }
        // Each axis is within the margin, but the horizontal distance is outside it.
        skipped.add(pet(2, at.clone().add(4, 0, 4), 0.6));
        Pet nearby = pet(3, at.clone().add(3, 0, 0), 0.6);
        lookups.clear();
        assertTrue(PetSpacing.free(runtime, self, at));
        assertEquals(Map.of(self.entityId(), 1, nearby.entityId(), 1), lookups);
        for (Pet other : skipped) assertFalse(lookups.containsKey(other.entityId()));
    }

    @Test void nearbyOccupantUsesItsActualPositionDespiteStaleHorizontalAndVerticalCoordinates() {
        Location at = owner.getLocation();
        Pet self = pet(1, at.clone().add(-10, 0, 0), 0.6);
        Pet other = pet(2, at.clone().add(4, 20, 0), 0.6);
        runtime.entity(other).teleport(at);
        lookups.clear();
        assertFalse(PetSpacing.free(runtime, self, at));
        assertEquals(Map.of(self.entityId(), 1), lookups, "The previously resolved occupant is reused from the body cache");
        runtime.entity(other).teleport(at.clone().add(5, 0, 0));
        assertTrue(PetSpacing.free(runtime, self, at), "A stale nearby record must not block a point the body has vacated");
    }

    @Test void realWidthHeightAndWorldStillDetermineClearance() {
        Location at = owner.getLocation();
        Pet self = pet(1, at.clone().add(-10, 0, 0), 2);
        Pet other = pet(2, at.clone().add(2.75, 0, 0), 3);
        Entity body = runtime.entity(other);
        assertTrue(PetSpacing.free(runtime, self, at), "The exact clearance boundary is free");
        body.teleport(at.clone().add(2.74, 0, 0));
        assertFalse(PetSpacing.free(runtime, self, at));
        body.teleport(at.clone().add(0, 1.5, 0));
        assertFalse(PetSpacing.free(runtime, self, at));
        body.teleport(at.clone().add(0, 1.51, 0));
        assertTrue(PetSpacing.free(runtime, self, at));
        body.teleport(new Location(elsewhere, 0, 64, 0));
        assertTrue(PetSpacing.free(runtime, self, at));
    }

    @Test void missingBodiesUseDefaultWidthAndInactiveOrNonMobPetsDoNotBlock() {
        Location at = owner.getLocation();
        Pet self = record(new UUID(0, 1), owner.getUniqueId(), at);
        Pet other = pet(2, at.clone().add(0.9, 0, 0), 0.6);
        assertTrue(PetSpacing.free(runtime, self, at));
        runtime.entity(other).teleport(at.clone().add(0.89, 0, 0));
        assertFalse(PetSpacing.free(runtime, self, at));
        other.stored(true);
        Pet dead = pet(3, at, 0.6); dead.dead(true);
        Pet missing = record(new UUID(0, 4), owner.getUniqueId(), at);
        Pet nonMob = record(new UUID(0, 5), owner.getUniqueId(), at); nonMob.entityId(owner.getUniqueId());
        lookups.clear();
        assertTrue(PetSpacing.free(runtime, self, at));
        assertFalse(lookups.containsKey(other.entityId())); assertFalse(lookups.containsKey(dead.entityId()));
        assertEquals(1, lookups.get(missing.entityId())); assertEquals(1, lookups.get(nonMob.entityId()));
        assertTrue(PetSpacing.free(runtime, self, new Location(null, 0, 64, 0)));
    }

    @Test void formationKeepsTheOriginalCountAndUuidRanksWithoutLookingUpOtherOwners() throws Exception {
        Location at = owner.getLocation().add(20, 0, 0);
        var focused = new ArrayList<Pet>();
        for (long id : new long[]{40, 10, 50, 20, 30}) {
            Pet pet = pet(id, at, 0.6); pet.activity(Activity.TOY_FOCUS); focused.add(pet);
        }
        Pet stored = pet(2, at, 0.6); stored.activity(Activity.TOY_FOCUS); stored.stored(true);
        Pet dead = pet(3, at, 0.6); dead.activity(Activity.TOY_FOCUS); dead.dead(true);
        Pet idle = pet(4, at, 0.6);
        Pet wrongWorld = pet(5, new Location(elsewhere, 20, 64, 0), 0.6); wrongWorld.activity(Activity.TOY_FOCUS);
        Pet missing = record(new UUID(0, 6), owner.getUniqueId(), at); missing.activity(Activity.TOY_FOCUS);
        Pet nonMob = record(new UUID(0, 7), owner.getUniqueId(), at);
        nonMob.activity(Activity.TOY_FOCUS); nonMob.entityId(owner.getUniqueId());
        var strangers = new ArrayList<Pet>();
        for (int i = 0; i < 250; i++) {
            Pet other = record(UUID.randomUUID(), UUID.randomUUID(), at); other.activity(Activity.TOY_FOCUS); strangers.add(other);
        }
        var expected = new HashMap<Pet, List<Integer>>();
        for (Pet pet : runtime.store().of(owner.getUniqueId())) expected.put(pet, originalFormation(pet));
        lookups.clear();
        for (Pet pet : focused) {
            assertEquals(expected.get(pet), formation(pet));
            assertEquals(List.of(5, (int) (pet.id().getLeastSignificantBits() / 10 - 1)), formation(pet));
        }
        for (Pet pet : List.of(stored, dead, idle, wrongWorld, missing, nonMob)) {
            assertEquals(List.of(5, -1), formation(pet)); assertEquals(expected.get(pet), formation(pet));
        }
        for (Pet other : strangers) assertFalse(lookups.containsKey(other.entityId()));
    }

    private List<Integer> formation(Pet pet) throws Exception {
        var method = PetSpacing.class.getDeclaredMethod("formation", PetRuntime.class, Pet.class, org.bukkit.entity.Player.class);
        method.setAccessible(true); Object result = method.invoke(null, runtime, pet, owner);
        var count = result.getClass().getDeclaredMethod("count"); count.setAccessible(true);
        var slot = result.getClass().getDeclaredMethod("slot"); slot.setAccessible(true);
        return List.of((int) count.invoke(result), (int) slot.invoke(result));
    }

    private List<Integer> originalFormation(Pet pet) {
        int count = 0, slot = 0;
        boolean included = false;
        for (Pet other : runtime.store().active()) {
            if (!other.ownerId().equals(pet.ownerId()) || other.stored() || other.dead()
                    || other.activity() != Activity.TOY_FOCUS
                    || !(runtime.entity(other) instanceof Mob body) || !body.getWorld().equals(owner.getWorld())) continue;
            count++;
            if (other.id().compareTo(pet.id()) < 0) slot++;
            if (other == pet) included = true;
        }
        return List.of(count, included ? slot : -1);
    }

    @Test void toyFrontKeepsStableRowsAndLateralSlotsWhenInsertionOrderDiffers() {
        Location at = owner.getLocation().add(20, 0, 0);
        var pets = new HashMap<Long, Pet>();
        for (long id : new long[]{4, 2, 1, 3}) {
            Pet pet = pet(id, at, 0.6); pet.activity(Activity.TOY_FOCUS); pets.put(id, pet);
        }
        assertEquals(new Location(world, 0, 64, 2.4), PetSpacing.toyFront(runtime, pets.get(1L), owner));
        assertEquals(new Location(world, -1.35, 64, 2.4), PetSpacing.toyFront(runtime, pets.get(2L), owner));
        assertEquals(new Location(world, 1.35, 64, 2.4), PetSpacing.toyFront(runtime, pets.get(3L), owner));
        assertEquals(new Location(world, 0, 64, 3.8), PetSpacing.toyFront(runtime, pets.get(4L), owner));
        Pet wide = pet(5, at, 2); wide.activity(Activity.TOY_FOCUS);
        assertEquals(new Location(world, -2.5, 64, 3.8), PetSpacing.toyFront(runtime, wide, owner));
        owner.teleport(new Location(world, 0, 64, 0, 90, 0));
        Location turned = PetSpacing.toyFront(runtime, pets.get(1L), owner);
        assertNotNull(turned); assertEquals(-2.4, turned.getX(), 1e-10); assertEquals(0, turned.getZ(), 1e-10);
    }

    @Test void toyFrontKeepsOffsetsBlockedSlotsAndMissingGroundBehavior() {
        Pet self = pet(1, owner.getLocation().add(-10, 0, 0), 0.6);
        self.activity(Activity.TOY_FOCUS);
        Location front = PetSpacing.toyFront(runtime, self, owner); assertNotNull(front);
        Pet other = pet(2, front.clone().add(0.8, 0, 0), 0.6);
        assertEquals(front.clone().add(-0.35, 0, 0), PetSpacing.toyFront(runtime, self, owner));
        runtime.entity(other).teleport(front.clone().add(-0.8, 0, 0));
        assertEquals(front.clone().add(0.35, 0, 0), PetSpacing.toyFront(runtime, self, owner));
        runtime.entity(other).teleport(front);
        assertNull(PetSpacing.toyFront(runtime, self, owner));
        runtime.entity(other).teleport(front.clone().add(5, 0, 0));
        assertEquals(front, PetSpacing.toyFront(runtime, self, owner));
        self.entityId(null); self.activity(Activity.NONE);
        assertEquals(front, PetSpacing.toyFront(runtime, self, owner));
        floor = false; assertNull(PetSpacing.toyFront(runtime, self, owner));
    }
}
