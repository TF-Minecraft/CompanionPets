package net.tfminecraft.companionpets.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.*;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.body.PetPlacement;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.listen.PetListener;
import net.tfminecraft.companionpets.session.ReleasePrompt;
import net.tfminecraft.companionpets.session.TrainingSession;
import org.bukkit.event.block.BlockPlaceEvent;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.bukkit.util.RayTraceResult;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.testutil.GoalServerMock;
import net.tfminecraft.companionpets.testutil.CollisionWorldMock;
import net.tfminecraft.companionpets.visual.PetVisual;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import net.tfminecraft.companionpets.gui.MenuHolder;
import net.tfminecraft.companionpets.gui.PetMenus;
import net.tfminecraft.companionpets.session.RenamePrompt;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

@SuppressWarnings("deprecation")
class PetActionsCoverageTest {
    private GoalServerMock server;
    private final Set<UUID> unloaded = new HashSet<>();
    private WorldMock world;
    private PlayerMock owner;
    private WolfMock body;
    private Pet pet;
    private PetRuntime runtime;
    private PetActions actions;
    private PetListener listener;
    private final List<String> animations = new ArrayList<>();
    private final List<UUID> cancelledAnimations = new ArrayList<>();
    private boolean lookingAtPet;
    private RayTraceResult rayBlock;
    private YamlConfiguration yaml;
    private boolean bellyActive, bellyAvailable, customClipPlayed;
    private final List<String> customClips = new ArrayList<>();
    private int bellyRubs;
    private boolean forbidDistantChunkLoads;
    private final Map<String, BlockMock> blocks = new HashMap<>();

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(new GoalServerMock() {
            @Override public Entity getEntity(UUID id) {
                return unloaded.contains(id) ? null : super.getEntity(id);
            }
        });
        world = new CollisionWorldMock() {
            @Override public org.mockbukkit.mockbukkit.world.ChunkMock getChunkAt(int x, int z) {
                if (forbidDistantChunkLoads && x == 20 && z == 0) throw new AssertionError("Distant chunk must not load");
                return super.getChunkAt(x, z);
            }
            @Override public java.util.concurrent.CompletableFuture<Chunk> getChunkAtAsync(int x, int z, boolean gen, boolean urgent) {
                throw new AssertionError("Care actions must not request background chunk loads");
            }
            @Override public BlockMock getBlockAt(int x, int y, int z) {
                return blocks.computeIfAbsent(x + ":" + y + ":" + z, unused -> new BlockMock(y == 63 ? Material.STONE : Material.AIR, new Location(this, x, y, z)) {
                    @Override public boolean isPassable() { return !getType().isSolid(); }
                    @Override public boolean isReplaceable() { return getType().isAir() || getType() == Material.SHORT_GRASS; }
                });
            }
            @Override public RayTraceResult rayTraceBlocks(Location at, org.bukkit.util.Vector direction, double distance,
                    FluidCollisionMode fluids, boolean ignorePassable) { return rayBlock; }
            @Override public RayTraceResult rayTraceEntities(Location at, org.bukkit.util.Vector direction, double distance,
                    java.util.function.Predicate<? super Entity> filter) {
                // The boundary still applies the production predicate and range cutoff.
                if (lookingAtPet && body != null && filter.test(body) && !filter.test(owner)
                        && at.distance(body.getEyeLocation()) <= distance) return new RayTraceResult(body.getLocation().toVector(), body);
                return null;
            }
            @Override public <T extends Entity> T spawn(Location at, Class<T> type) {
                if (type != Wolf.class) return super.spawn(at, type);
                return type.cast(wolf(at));
            }
        };
        server.addWorld(world);
        owner = server.addPlayer();
        owner.teleport(new Location(world, 0, 64, 0));
        var plugin = MockBukkit.createMockPlugin();
        yaml = new YamlConfiguration();
        yaml.loadFromString("""
                moments: {belly-up: {chance: 0}}
                limits: {max-out: 2, max-pets: 2}
                items: {kennel: BARREL, kennel-block: BARREL, toys: [STICK], treats: [COD], foods: [{item: BEEF, hunger: 35}]}
                custom-tricks:
                  wave: {display-name: Wave, animation: wave_clip, duration: 2, fallback-text: "{pet} waves to {owner}"}
                pets:
                  wolf: {entity: WOLF, behavior: dog, egg: WOLF_SPAWN_EGG, default-tricks: [], tricks: {add: [wave]}}
                """);
        var visual = new PetVisual() {
            @Override public void apply(Entity entity, PetTypeDef type) { }
            @Override public boolean play(Entity entity, PetTypeDef type, String action) {
                animations.add(entity.getUniqueId() + ":" + action); return false;
            }
            @Override public void cancelAction(Entity entity) { if (entity != null) cancelledAnimations.add(entity.getUniqueId()); bellyActive = false; }
            @Override public boolean belly(Entity entity) { return bellyActive; }
            @Override public boolean startBelly(Entity entity, PetTypeDef type, long duration) { bellyActive = bellyAvailable; return bellyActive; }
            @Override public boolean rubBelly(Entity entity) { bellyRubs++; return true; }
            @Override public boolean holdsMovement(Entity entity) { return bellyActive; }
            @Override public Set<String> clips(PetTypeDef type) { return Set.of("lie_back", "belly_up", "get_up", "wave_clip"); }
            @Override public boolean modelAvailable(PetTypeDef type) { return true; }
            @Override public boolean playClip(Entity entity, PetTypeDef type, String clip, double duration) {
                customClips.add(clip); return customClipPlayed;
            }
        };
        var key = new NamespacedKey(plugin, "pet");
        var store = new PetStore(plugin);
        assertTrue(store.load());
        runtime = new PetRuntime(plugin, CompanionConfig.load(plugin, yaml), store, new Sessions(),
                new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        actions = new PetActions(runtime);
        listener = new PetListener(runtime, actions);
        server.getPluginManager().registerEvents(listener, plugin);
        pet = new Pet(UUID.randomUUID(), owner.getUniqueId(), "wolf", "Toby", PetSex.MALE);
        body = wolf(owner.getLocation().add(1, 0, 0));
        body.getPersistentDataContainer().set(key, PersistentDataType.STRING, pet.id().toString());
        for (Need need : Need.values()) pet.need(need, 80);
        store.add(pet);
        runtime.remember(pet, body);
    }

    private WolfMock wolf(Location at) {
        var wolf = new WolfMock(server, UUID.randomUUID()) {
            @Override public boolean isValid() { return !unloaded.contains(getUniqueId()) && super.isValid(); }
            @Override public boolean hasLineOfSight(Entity other) { return true; }
            @Override public boolean isInWater() { return false; }
            @Override public boolean isOnGround() { return true; }
            @Override public float getBodyYaw() { return 0; }
            @Override public void setBodyYaw(float yaw) { }
            @Override public void lookAt(Entity target) { }
            @Override public void lookAt(Entity target, float speed, float pitch) { }
            @Override public void lookAt(Location target, float speed, float pitch) { }
            @Override public void lookAt(Location target) { }
            @Override public void setRemoveWhenFarAway(boolean remove) { }
            @Override public com.destroystokyo.paper.entity.Pathfinder getPathfinder() {
                return (com.destroystokyo.paper.entity.Pathfinder) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{com.destroystokyo.paper.entity.Pathfinder.class}, (proxy, method, args) -> switch (method.getName()) {
                            case "setCanFloat" -> { assertEquals(true, args[0]); yield null; }
                            case "stopPathfinding" -> null;
                            case "hasPath" -> false;
                            case "moveTo" -> true;
                            case "getEntity" -> this;
                            case "findPath" -> Proxy.newProxyInstance(getClass().getClassLoader(),
                                    new Class<?>[]{com.destroystokyo.paper.entity.Pathfinder.PathResult.class},
                                    (p, m, a) -> m.getName().equals("canReachFinalPoint") ? true : null);
                            default -> throw new AssertionError("Unexpected navigation: " + method.getName());
                        });
            }
        };
        server.registerEntity(wolf);
        wolf.teleport(at);
        return wolf;
    }

    @AfterEach void cleanup() {
        if (actions != null) actions.holograms().clear();
        if (runtime != null) runtime.store().close();
        if (owner != null) PetFx.clearPlayer(owner.getUniqueId());
        MockBukkit.unmock();
    }

    private ItemStack hold(Material type, int count) {
        owner.getInventory().setItemInMainHand(new ItemStack(type, count));
        return owner.getInventory().getItemInMainHand();
    }
    private <T extends Event> T fire(T event) { server.getPluginManager().callEvent(event); return event; }
    private <T extends Event> void atHigh(Class<T> type, java.util.function.Consumer<T> handler) {
        server.getPluginManager().registerEvent(type, new Listener() { }, EventPriority.HIGH,
                (unused, event) -> handler.accept(type.cast(event)), runtime.plugin());
    }
    private Block base() {
        Block base = world.getBlockAt(3, 63, 0);
        base.setType(Material.STONE);
        return base;
    }
    private PlayerInteractEvent use(Action action, ItemStack item, Block clicked, EquipmentSlot hand) {
        return new PlayerInteractEvent(owner, action, item, clicked, BlockFace.UP, hand);
    }
    private PlayerInteractEvent kennelEvent() {
        owner.setSneaking(true);
        return use(Action.RIGHT_CLICK_BLOCK, hold(Material.BARREL, 2), base(), EquipmentSlot.HAND);
    }
    private void assertNoPlacement() {
        assertTrue(base().getRelative(BlockFace.UP).getType().isAir());
        assertEquals(2, owner.getInventory().getItemInMainHand().getAmount());
        assertNull(runtime.store().kennelOwner(PetStore.kennelKey(world.getName(), 3, 64, 0)));
    }
    @Test void protectionSeesAVanillaShapedProvisionalPlacementBeforeAnyCommit() {
        AtomicInteger seen = new AtomicInteger();
        atHigh(BlockPlaceEvent.class, event -> {
            seen.incrementAndGet();
            assertEquals(base().getRelative(BlockFace.UP), event.getBlockPlaced());
            assertEquals(Material.BARREL, event.getBlockPlaced().getType());
            assertEquals(Material.AIR, event.getBlockReplacedState().getType());
            assertEquals(base(), event.getBlockAgainst());
            assertSame(owner, event.getPlayer());
            assertEquals(EquipmentSlot.HAND, event.getHand());
            assertEquals(new ItemStack(Material.BARREL, 2), event.getItemInHand());
            assertEquals(2, owner.getInventory().getItemInMainHand().getAmount());
            assertNull(runtime.store().kennelOwner(PetStore.kennelKey(world.getName(), 3, 64, 0)));
        });
        fire(kennelEvent());
        assertEquals(1, seen.get(), "Protection plugins must observe custom block placement");
        assertEquals(Material.BARREL, base().getRelative(BlockFace.UP).getType());
        assertEquals(1, owner.getInventory().getItemInMainHand().getAmount());
        assertEquals(owner.getUniqueId(), runtime.store().kennelOwner(PetStore.kennelKey(world.getName(), 3, 64, 0)));
    }

    @Test void cancelledPlacementRollsBackWorldAndRetainsInventoryAndOwnership() {
        atHigh(BlockPlaceEvent.class, event -> event.setCancelled(true));
        fire(kennelEvent());
        assertNoPlacement();
    }

    @Test void canBuildDenialRollsBackEvenWithoutCancellation() {
        atHigh(BlockPlaceEvent.class, event -> event.setBuild(false));
        fire(kennelEvent());
        assertNoPlacement();
    }

    @Test void cancelledPlacementRestoresTheReplacedBlockData() {
        base().getRelative(BlockFace.UP).setType(Material.SHORT_GRASS);
        var previous = base().getRelative(BlockFace.UP).getBlockData().clone();
        atHigh(BlockPlaceEvent.class, event -> event.setCancelled(true));
        fire(kennelEvent());
        assertEquals(previous, base().getRelative(BlockFace.UP).getBlockData());
        assertEquals(2, owner.getInventory().getItemInMainHand().getAmount());
        assertNull(runtime.store().kennelOwner(PetStore.kennelKey(world.getName(), 3, 64, 0)));
    }

    @Test void lostBodyClearsAllSessionsForTheDeadPetAndPreservesOtherPetsSessions() {
        var other = server.addPlayer();
        var unaffected = new RenamePrompt(UUID.randomUUID(), Long.MAX_VALUE);
        runtime.sessions().rename(other.getUniqueId(), unaffected);
        runtime.sessions().rename(owner.getUniqueId(), new RenamePrompt(pet.id(), Long.MAX_VALUE));
        runtime.sessions().release(owner.getUniqueId(), new ReleasePrompt(pet.id(), Long.MAX_VALUE));
        runtime.sessions().training(owner.getUniqueId(), new TrainingSession(pet.id()));
        actions.lostBody(pet);
        assertNull(runtime.store().get(pet.id()));
        assertTrue(runtime.store().isDeleted(pet.id()));
        assertNull(runtime.sessions().rename(owner.getUniqueId()));
        assertNull(runtime.sessions().release(owner.getUniqueId()));
        assertNull(runtime.sessions().training(owner.getUniqueId()));
        assertSame(unaffected, runtime.sessions().rename(other.getUniqueId()));
    }

    @Test void failedDeathJournalStillClearsSessionsAndFreezesTheRecord() throws Exception {
        Files.createDirectory(runtime.plugin().getDataFolder().toPath().resolve("pet-deletions.log"));
        runtime.sessions().rename(owner.getUniqueId(), new RenamePrompt(pet.id(), Long.MAX_VALUE));
        actions.lostBody(pet);
        assertSame(pet, runtime.store().get(pet.id()));
        assertTrue(pet.dead());
        assertFalse(runtime.store().canRestoreBodies());
        assertNull(runtime.sessions().privatePrompt(owner.getUniqueId()));
    }

    private void configure(String key, Object value) {
        yaml.set(key, value);
        runtime.config(CompanionConfig.load(runtime.plugin(), yaml));
    }
    private String messages() {
        StringBuilder text = new StringBuilder();
        String line;
        while ((line = owner.nextMessage()) != null) text.append(line).append('\n');
        return text.toString();
    }
    private String bars() {
        StringBuilder text = new StringBuilder();
        net.kyori.adventure.text.Component line;
        while ((line = owner.nextActionBar()) != null)
            text.append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line)).append('\n');
        return text.toString();
    }
    private MenuHolder holder() { return (MenuHolder) owner.getOpenInventory().getTopInventory().getHolder(); }
    private void care(int slot) {
        actions.menus().openCare(owner, pet);
        actions.clickMenu(owner, holder(), slot, null, false, false, false);
    }
    private void command(String word, Trick trick) {
        pet.bindWord(word, trick);
        pet.progress(trick, 100);
        actions.onChat(owner, pet.name() + " " + word);
    }
    private List<Item> items() { return world.getEntities().stream().filter(Item.class::isInstance).map(Item.class::cast).toList(); }
    private void pet() { assertTrue(actions.useOnPet(owner, body, hold(Material.AIR, 1))); }

    @Test void creativePlacementKeepsItsItemAndAnOccupiedTargetNeverEmitsAnEvent() {
        AtomicInteger events = new AtomicInteger();
        atHigh(BlockPlaceEvent.class, event -> events.incrementAndGet());
        owner.setGameMode(GameMode.CREATIVE);
        fire(kennelEvent());
        assertEquals(1, events.get());
        assertEquals(2, owner.getInventory().getItemInMainHand().getAmount());
        fire(kennelEvent());
        assertEquals(1, events.get());
        assertTrue(bars().contains("enough room"));
    }

    @Test void losingTheHeldStackDuringPlacementDoesNotCreateAFreeKennel() {
        atHigh(BlockPlaceEvent.class, event -> owner.getInventory().getItemInMainHand().setAmount(0));
        fire(kennelEvent());
        assertTrue(base().getRelative(BlockFace.UP).getType().isAir());
        assertNull(runtime.store().kennelOwner(PetStore.kennelKey(world.getName(), 3, 64, 0)));
    }

    @Test void onlyTheKennelOwnerCanOpenItsMenuAndOtherItemsRemainUnhandled() {
        fire(kennelEvent());
        Block kennel = base().getRelative(BlockFace.UP);
        owner.setSneaking(false);
        actions.useWorld(owner, null, kennel, BlockFace.UP, false, false);
        assertEquals(MenuHolder.Kind.KENNEL, holder().kind());
        owner.closeInventory();
        runtime.store().kennel(PetStore.kennelKey(world.getName(), 3, 64, 0), UUID.randomUUID());
        actions.useWorld(owner, null, kennel, BlockFace.UP, false, false);
        assertTrue(owner.getOpenInventory().getTopInventory() == null
                || !(owner.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder));
        assertTrue(bars().contains("belongs to someone else"));
        assertFalse(actions.handledWorld(owner, new ItemStack(Material.DIRT), null, null, false, true));
        assertFalse(actions.handledWorld(owner, null, null, null, false, true));
        var egg = hold(Material.WOLF_SPAWN_EGG, 1);
        assertTrue(actions.handledWorld(owner, egg, null, null, false, true));
        actions.useWorld(owner, egg, null, null, false, true);
        assertNotNull(runtime.sessions().hatch(owner.getUniqueId()));
        assertEquals(1, owner.getInventory().getItemInMainHand().getAmount());
    }

    @Test void storeAndBringOutPreserveThePetAndTransferItsCarriedToy() {
        pet.carriedToy("STICK");
        pet.order(PetOrder.STAY);
        pet.staying(true);
        care(PetMenus.careSlot(PetMenus.STORE_SLOT, false));
        assertTrue(pet.stored());
        assertNull(pet.entityId());
        assertFalse(body.isValid());
        assertNull(pet.carriedToy());
        assertEquals(Material.STICK, items().getFirst().getItemStack().getType());
        assertTrue(runtime.store().save());
        care(PetMenus.careSlot(PetMenus.BRING_OUT_SLOT, false));
        assertFalse(pet.stored());
        Entity restored = runtime.entity(pet);
        assertNotNull(restored);
        assertNotEquals(body.getUniqueId(), restored.getUniqueId());
        assertTrue(pet.lastOwnerNearbyMillis() > 0);
    }

    @Test void bringOutSlotForAnOutsidePetLeavesItsBodyOrderAndMenuUntouched() {
        body.teleport(new Location(world, 12, 64, 0));
        runtime.remember(pet, body);
        pet.order(PetOrder.STAY); pet.staying(true); pet.activity(Activity.SLEEPING);
        actions.menus().openCare(owner, pet);
        var before = holder();
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                before.getInventory().getItem(PetMenus.careSlot(PetMenus.BRING_OUT_SLOT, false)).getType());
        actions.clickMenu(owner, before, PetMenus.careSlot(PetMenus.BRING_OUT_SLOT, false), null, false, false, false);
        assertSame(before, holder());
        assertEquals(12, body.getLocation().getX());
        assertEquals(PetOrder.STAY, pet.order()); assertTrue(pet.staying());
        assertEquals(Activity.SLEEPING, pet.activity()); assertSame(body, runtime.entity(pet));
        assertEquals("", bars());
    }

    @Test void storingAnUnavailableDistantBodyAndBringingItOutNeverLoadsItsChunk() {
        body.teleport(new Location(world, 320, 64, 0));
        runtime.remember(pet, body);
        unloaded.add(body.getUniqueId()); // Bukkit cannot find this body until the chunk's entities load.
        assertNull(runtime.entity(pet));
        assertFalse(world.isChunkLoaded(20, 0));
        forbidDistantChunkLoads = true;
        care(PetMenus.careSlot(PetMenus.STORE_SLOT, false));
        assertTrue(pet.stored()); assertNull(pet.entityId());
        assertFalse(body.isValid()); assertFalse(body.isDead());
        care(PetMenus.careSlot(PetMenus.BRING_OUT_SLOT, false));
        Entity replacement = runtime.entity(pet);
        assertFalse(pet.stored()); assertNotNull(replacement);
        assertTrue(replacement.getLocation().distance(owner.getLocation()) < 3);
        assertFalse(world.isChunkLoaded(20, 0));
        unloaded.remove(body.getUniqueId());
        actions.reattach(body); // Natural chunk loading removes the old tagged body.
        assertFalse(body.isValid()); assertSame(replacement, runtime.entity(pet));
    }

    @Test void spacingOnlyCountsNearbyActivePetBodiesOnTheSameLevel() {
        var other = new Pet(UUID.randomUUID(), UUID.randomUUID(), "wolf", "Rex", PetSex.MALE);
        var otherBody = wolf(new Location(world, 10.5, 64, 0.5));
        otherBody.getPersistentDataContainer().set(runtime.petKey(), PersistentDataType.STRING, other.id().toString());
        runtime.store().add(other); runtime.remember(other, otherBody);
        wolf(new Location(world, 10.5, 64, 6.5)); // An untagged wolf is not a pet.
        assertFalse(PetSpacing.free(runtime, pet, new Location(world, 10.8, 64, 0.5)), "a pet body crowds the spot");
        assertTrue(PetSpacing.free(runtime, pet, new Location(world, 11.405, 64, 0.5)), "just beyond its clearance");
        assertTrue(PetSpacing.free(runtime, pet, new Location(world, 10.5, 64, 6.5)));
        assertTrue(PetSpacing.free(runtime, pet, new Location(world, 10.5, 66, 0.5)), "another level is not crowded");
        other.stored(true);
        assertTrue(PetSpacing.free(runtime, pet, new Location(world, 10.8, 64, 0.5)), "a stored pet's leftover body is ignored");
        assertTrue(PetSpacing.free(runtime, other, new Location(world, 10.8, 64, 0.5)), "a pet never crowds itself");
    }

    @Test void storingAtTheTotalLimitAlwaysSucceeds() {
        Pet stored = new Pet(UUID.randomUUID(), owner.getUniqueId(), "wolf", "Stored", PetSex.FEMALE);
        stored.stored(true); runtime.store().add(stored);
        assertEquals(2, runtime.store().countPets(owner.getUniqueId()));
        care(PetMenus.careSlot(PetMenus.STORE_SLOT, false));
        assertTrue(pet.stored()); assertNull(pet.entityId()); assertFalse(body.isValid());
        assertEquals(2, runtime.store().countPets(owner.getUniqueId()));
        assertEquals(0, runtime.store().countOut(owner.getUniqueId()));
        care(PetMenus.careSlot(PetMenus.BRING_OUT_SLOT, false));
        assertFalse(pet.stored()); assertNotNull(runtime.entity(pet));
        assertEquals(2, runtime.store().countPets(owner.getUniqueId()));
    }

    @Test void activeLimitKeepsAnExistingStoredPetInTheHouse() {
        configure("limits.max-out", 1);
        Pet stored = new Pet(UUID.randomUUID(), owner.getUniqueId(), "wolf", "Stored", PetSex.FEMALE);
        stored.stored(true); runtime.store().add(stored);
        actions.menus().openCare(owner, stored);
        actions.clickMenu(owner, holder(), PetMenus.careSlot(PetMenus.BRING_OUT_SLOT, false), null, false, false, false);
        assertTrue(stored.stored()); assertNull(stored.entityId());
        assertEquals(1, runtime.store().countOut(owner.getUniqueId()));
        assertTrue(bars().contains("as many pets out"));
    }

    @Test void testPetRejectsTheTotalWithRoomOutsideAndLeavesExistingPetsUntouched() {
        Pet stored = new Pet(UUID.randomUUID(), owner.getUniqueId(), "wolf", "Stored", PetSex.FEMALE);
        stored.stored(true); runtime.store().add(stored);
        int entities = world.getEntities().size();
        assertFalse(actions.spawnTestPet(owner, "wolf", "Extra"));
        assertEquals(2, runtime.store().countPets(owner.getUniqueId()));
        assertEquals(1, runtime.store().countOut(owner.getUniqueId()));
        assertEquals(entities, world.getEntities().size());
        assertTrue(owner.nextMessage().contains("as many pets as you can care for"));
        assertTrue(stored.stored()); assertSame(body, runtime.entity(pet));
    }

    @Test void bringingOutAPetWithoutSafeRoomKeepsItStoredWithItsIdentityAndNeeds() {
        care(PetMenus.careSlot(PetMenus.STORE_SLOT, false));
        assertTrue(pet.stored()); assertNull(pet.entityId());
        for (int x=-8; x<=8; x++) for (int y=60; y<=68; y++) for (int z=-8; z<=8; z++)
            world.getBlockAt(x,y,z).setType(Material.STONE);
        care(PetMenus.careSlot(PetMenus.BRING_OUT_SLOT, false));
        assertTrue(pet.stored()); assertNull(pet.entityId());
        assertSame(pet, runtime.store().get(pet.id())); assertEquals(80, pet.need(Need.HUNGER));
        assertTrue(bars().contains("no room"));
        assertTrue(world.getEntities().stream().noneMatch(Wolf.class::isInstance));
    }

    @Test void bringingOutAnUnavailableTypeRetainsSavedIdentityAndNeeds() {
        care(PetMenus.careSlot(PetMenus.STORE_SLOT, false));
        configure("pets.wolf", null);
        care(PetMenus.careSlot(PetMenus.BRING_OUT_SLOT, false));
        assertTrue(pet.stored());
        assertEquals(80, pet.need(Need.HUNGER));
        assertTrue(bars().contains("not configured"));
    }

    @Test void restoreBodyReusesTheCurrentOrLoadedTaggedBodyBeforeSpawning() {
        assertSame(body, actions.restoreBody(pet));
        UUID stale = UUID.randomUUID();
        pet.entityId(stale);
        world.loadChunk(body.getLocation().getBlockX() >> 4, body.getLocation().getBlockZ() >> 4);
        assertSame(body, actions.restoreBody(pet));
        assertEquals(body.getUniqueId(), pet.entityId());
        assertEquals(1, world.getEntities().stream().filter(Wolf.class::isInstance).count());
        body.remove();
        pet.place(world.getName(), -2, 65, -3, 30);
        world.loadChunk(-1, -1);
        Entity replacement = actions.restoreBody(pet);
        assertNotNull(replacement);
        Location restoredAt = replacement.getLocation();
        assertEquals(64, restoredAt.getY(), "Restore onto solid support near the saved position");
        assertEquals(restoredAt.getBlockX() + .5, restoredAt.getX());
        assertEquals(restoredAt.getBlockZ() + .5, restoredAt.getZ());
        assertEquals(30, restoredAt.getYaw()); assertEquals(0, restoredAt.getPitch());
        assertTrue(restoredAt.distance(new Location(world, -2, 65, -3)) < 2);
        assertTrue(PetPlacement.safe(restoredAt, PetPlacement.bounds(replacement)));
        assertEquals(pet.id(), runtime.bodies().readId(replacement));
        assertEquals(80, pet.need(Need.MOOD));
    }

    @Test void restoreGuardsNeverSpawnStoredDeadUnknownWorldOrUnloadedPets() {
        pet.stored(true); assertNull(actions.restoreBody(pet));
        pet.stored(false); pet.dead(true); assertNull(actions.restoreBody(pet));
        pet.dead(false); body.remove(); pet.entityId(null);
        pet.place("missing", 0, 64, 0, 0); assertNull(actions.restoreBody(pet));
        pet.place(world.getName(), 9000, 64, 9000, 0); assertNull(actions.restoreBody(pet));
        assertEquals(0, world.getEntities().stream().filter(Wolf.class::isInstance).count());
    }

    @Test void delayedDeletedOrStoredBodiesAreRemovedInsteadOfRevived() {
        pet.stored(true);
        actions.reattach(body);
        assertFalse(body.isValid());
        var stale = wolf(owner.getLocation());
        stale.getPersistentDataContainer().set(runtime.petKey(), PersistentDataType.STRING, pet.id().toString());
        assertTrue(runtime.store().remove(pet.id()));
        actions.reattach(stale);
        assertFalse(stale.isValid());
        var orphan = wolf(owner.getLocation());
        orphan.getPersistentDataContainer().set(runtime.petKey(), PersistentDataType.STRING, UUID.randomUUID().toString());
        actions.reattach(orphan);
        assertTrue(orphan.isValid(), "Unknown tags without a deletion record are not authority to destroy bodies");
    }

    @Test void releaseRequiresConfirmationAndSupportsExpiryRetryAndCancellation() {
        care(PetMenus.careSlot(PetMenus.RELEASE_SLOT, false));
        assertNotNull(runtime.sessions().release(owner.getUniqueId()));
        actions.onChat(owner, "maybe");
        assertSame(pet, runtime.store().get(pet.id()));
        assertTrue(messages().contains("Type \"yes\""));
        actions.onChat(owner, "no");
        assertNull(runtime.sessions().release(owner.getUniqueId()));
        assertTrue(body.isValid());
        runtime.sessions().release(owner.getUniqueId(), new ReleasePrompt(pet.id(), 0));
        actions.onChat(owner, "yes");
        assertSame(pet, runtime.store().get(pet.id()));
        assertTrue(messages().contains("expired"));
        pet.carriedToy("STICK");
        care(PetMenus.careSlot(PetMenus.RELEASE_SLOT, false));
        actions.onChat(owner, "yes");
        assertNull(runtime.store().get(pet.id()));
        assertFalse(body.isValid());
        assertNull(pet.carriedToy());
        assertEquals(Material.STICK, items().getFirst().getItemStack().getType());
    }

    @Test void releaseJournalFailureKeepsThePetAndBodyAvailable() throws Exception {
        Files.createDirectory(runtime.plugin().getDataFolder().toPath().resolve("pet-deletions.log"));
        care(PetMenus.careSlot(PetMenus.RELEASE_SLOT, false));
        actions.onChat(owner, "yes");
        assertSame(pet, runtime.store().get(pet.id()));
        assertTrue(body.isValid());
        assertFalse(pet.dead());
        assertTrue(messages().contains("Could not save the release"));
    }

    @Test void deathReturnsTheCarriedToyOnceAndDirectDropPreservesItsMaterial() {
        pet.carriedToy("STICK");
        actions.lostBody(pet);
        assertNull(pet.carriedToy());
        assertEquals(1, items().size());
        assertEquals(Material.STICK, items().getFirst().getItemStack().getType());
        actions.dropPlain(owner.getLocation(), "BEEF");
        assertTrue(items().stream().anyMatch(item -> item.getItemStack().getType() == Material.BEEF));
    }

    @Test void failedSpawnSaveRollsBackTheNewPetAndItsBody() throws Exception {
        Files.createDirectory(runtime.plugin().getDataFolder().toPath().resolve("pets.yml.tmp"));
        assertFalse(actions.spawnTestPet(owner, "wolf", "Unsaved"));
        assertEquals(1, runtime.store().all().size());
        assertEquals(1, world.getEntities().stream().filter(Wolf.class::isInstance).count());
        assertTrue(messages().contains("could not be saved"));
    }

    @Test void unavailablePersistenceRefusesCreationWithoutSpawning() throws Exception {
        Files.createDirectory(runtime.plugin().getDataFolder().toPath().resolve("pet-deletions.log"));
        assertFalse(runtime.store().remove(pet.id()));
        assertFalse(actions.spawnTestPet(owner, "wolf", "Blocked"));
        assertEquals(1, world.getEntities().stream().filter(Wolf.class::isInstance).count());
        assertTrue(messages().contains("persistence is unavailable"));
    }

    @Test void renameValidationCancellationAndStalePromptsPreserveTheOriginalName() {
        care(PetMenus.NAME_SLOT);
        actions.onChat(owner, "   ");
        assertNull(runtime.sessions().rename(owner.getUniqueId()).name());
        assertTrue(messages().contains("empty"));
        actions.onChat(owner, "Luna");
        assertTrue(runtime.sessions().rename(owner.getUniqueId()).confirming());
        actions.onChat(owner, "maybe");
        assertEquals("Toby", pet.name());
        assertTrue(messages().contains("confirm"));
        actions.onChat(owner, "cancel");
        assertNull(runtime.sessions().rename(owner.getUniqueId()));
        assertEquals("Toby", pet.name());
        runtime.sessions().rename(owner.getUniqueId(), new RenamePrompt(pet.id(), 0));
        actions.onChat(owner, "Luna");
        assertNull(runtime.sessions().rename(owner.getUniqueId()));
        runtime.sessions().rename(owner.getUniqueId(), new RenamePrompt(UUID.randomUUID(), Long.MAX_VALUE));
        actions.onChat(owner, "Luna");
        assertNull(runtime.sessions().rename(owner.getUniqueId()));
    }

    @Test void repeatedOwnerClicksCalmAVanillaAttackTargetWithoutFeeding() {
        var target = server.addPlayer();
        body.setTarget(target); body.setAngry(true);
        int clicks = runtime.config().social().calmClicks();
        for (int i = 1; i < clicks; i++) {
            pet();
            assertSame(target, body.getTarget());
            assertTrue(bars().contains(i + "/" + clicks));
        }
        pet();
        assertNull(body.getTarget());
        assertFalse(body.isAngry());
        assertTrue(bars().contains("calmed down"));
    }

    @Test void strangersCannotCalmAnAttackUnlessTheyAreItsTarget() {
        var stranger = server.addPlayer();
        stranger.teleport(owner.getLocation());
        body.setTarget(owner); body.setAngry(true);
        for (int i = 0; i < runtime.config().social().calmClicks(); i++)
            assertTrue(actions.useOnPet(stranger, body, new ItemStack(Material.AIR)));
        assertSame(owner, body.getTarget());
        body.setTarget(stranger);
        for (int i = 0; i < runtime.config().social().calmClicks(); i++)
            assertTrue(actions.useOnPet(stranger, body, new ItemStack(Material.AIR)));
        assertNull(body.getTarget());
    }

    @Test void checkInReportsNeedsAndIllnessWithoutPretendingTheyAreCured() {
        pet.need(Need.HUNGER, 10);
        pet();
        assertEquals(10, pet.need(Need.HUNGER));
        assertFalse(bars().isBlank());
        pet.need(Need.HUNGER, 80); pet.illness(Illness.SICK);
        pet();
        assertEquals(Illness.SICK, pet.illness());
        assertTrue(pet.need(Need.MOOD) > 80);
        assertTrue(bars().contains("attention"));
        double cheered = pet.need(Need.MOOD);
        pet();
        assertEquals(cheered, pet.need(Need.MOOD), "Rapid repeat attention is rate-limited");
    }

    @Test void quietRestingCheckInAndDevotedGreetingHaveDifferentPhysicalOutcomes() {
        runtime.sessions().rest(pet.id(), Long.MAX_VALUE);
        pet();
        assertTrue(bars().contains("rest"));
        assertEquals(0, body.getVelocity().getY());
        runtime.sessions().rest(pet.id(), 0);
        pet.bond(100);
        pet();
        assertTrue(animations.stream().anyMatch(action -> action.endsWith(":PET")));
        assertTrue(body.getVelocity().getY() > 0);
    }

    @Test void pettingWithoutAnOnlineOwnerReportsAbsenceWithoutBondGain() {
        var visitor = server.addPlayer(); visitor.teleport(owner.getLocation());
        owner.disconnect();
        assertTrue(actions.useOnPet(visitor, body, new ItemStack(Material.AIR)));
        assertEquals(0, pet.bond());
        assertEquals(80, pet.need(Need.MOOD));
    }

    @Test void legacyBehaviorListsDoNotDisableFetchingAndMissingTypesRemainUnhandled() {
        configure("pets.wolf.behaviors.remove", List.of("fetch"));
        assertTrue(actions.useOnPet(owner, body, new ItemStack(Material.STICK)), "Legacy allowlists are ignored by fixed profiles");
        assertTrue(bars().contains("Throw it into the air"));
        configure("pets.wolf", null);
        assertFalse(actions.useOnPet(owner, body, new ItemStack(Material.STICK)));
        assertFalse(actions.useOnPet(owner, body, new ItemStack(Material.BEEF)));
        assertEquals(80, pet.need(Need.HUNGER));
    }

    @Test void lowEnergyTreatClickExplainsWhyTrainingCannotStart() {
        pet.need(Need.ENERGY, 10);
        assertTrue(actions.useOnPet(owner, body, hold(Material.COD, 2)));
        assertNull(runtime.sessions().training(owner.getUniqueId()));
        assertEquals(2, owner.getInventory().getItemInMainHand().getAmount());
        assertTrue(bars().contains("can't train"));
    }

    @Test void learnedPhysicalTricksStandUpAndUseTheConfiguredActions() {
        pet.order(PetOrder.SIT); pet.staying(true); body.setSitting(true); pet.forcedSitUntilMillis(Long.MAX_VALUE);
        command("jump", Trick.JUMP);
        assertEquals(PetOrder.FOLLOW, pet.order()); assertFalse(pet.staying()); assertFalse(body.isSitting());
        assertEquals(0, pet.forcedSitUntilMillis()); assertEquals(0.48, body.getVelocity().getY(), 0.00001);
        command("paw", Trick.PAW);
        command("speak", Trick.SPEAK);
        assertTrue(animations.stream().anyMatch(action -> action.endsWith(":PAW")));
        assertTrue(animations.stream().anyMatch(action -> action.endsWith(":SPEAK")));
        pet.need(Need.HUNGER, 10);
        command("follow", Trick.FOLLOW);
        assertTrue(bars().contains("needs food, rest, or care"));
    }

    @Test void learnedCustomTrickUsesVisualClipOrItsConfiguredHologramFallback() {
        lookingAtPet = true;
        Trick wave = Trick.valueOf("wave");
        pet.bindWord("hello", wave); pet.progress(wave, 100);
        actions.onChat(owner, "hello");
        assertEquals(List.of("wave_clip"), customClips);
        assertTrue(world.getEntities().stream().filter(ArmorStand.class::isInstance)
                .anyMatch(stand -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(stand.customName()).contains("Toby waves")));
        customClipPlayed = true;
        command("wave", wave);
        assertEquals(2, customClips.size());
        pet.activity(Activity.SLEEPING);
        command("jump", Trick.JUMP);
        assertTrue(bars().contains("is resting"));
        assertEquals(0, body.getVelocity().getY());
    }

    @Test void rayTraceStopsAtBlocksAndExcludesThePlayer() {
        lookingAtPet = true;
        assertSame(body, PetActions.lookingAt(owner, 6));
        rayBlock = new RayTraceResult(owner.getEyeLocation().toVector().add(new org.bukkit.util.Vector(0, 0, 0.2)));
        assertNull(PetActions.lookingAt(owner, 6));
        rayBlock = new RayTraceResult(owner.getEyeLocation().toVector());
        assertNull(PetActions.lookingAt(owner, 6));
        assertTrue(PetActions.toyNames(null).isEmpty());
    }

    @Test void testingMomentsRejectsWrongTargetsAndBusyPetsWithUsefulFeedback() {
        assertFalse(actions.triggerTestMoment(owner, "affection"));
        assertTrue(messages().contains("Look at one of your pets"));
        lookingAtPet = true; pet.order(PetOrder.SIT);
        assertFalse(actions.triggerTestMoment(owner, "affection"));
        assertTrue(messages().contains("standing"));
        pet.order(PetOrder.FOLLOW);
        assertFalse(actions.triggerTestMoment(owner, "unknown"));
        assertTrue(messages().contains("Choose affection"));
        assertTrue(actions.triggerTestMoment(owner, "affection"));
        assertTrue(bars().contains("attention"));
        assertTrue(actions.triggerTestMoment(owner, "bark"));
        assertFalse(actions.triggerTestMoment(owner, "mischief"), "DOG supports mischief, but no plants are nearby");
        assertTrue(messages().contains("No small plant"));
        configure("pets.wolf.behavior", "basic");
        assertTrue(actions.triggerTestMoment(owner, "cry"), "Every supported profile retains affection");
        assertTrue(bars().contains("attention"));
        assertFalse(actions.triggerTestMoment(owner, "anger"));
        assertFalse(actions.triggerTestMoment(owner, "gift"));
        assertFalse(actions.triggerTestMoment(owner, "mischief"));
        assertTrue(messages().contains("No small plant"));
        assertFalse(actions.triggerTestMoment(owner, "belly"));
        assertTrue(messages().contains("Belly rub"));
    }

    @Test void bellyMomentStartsAndPettingScratchesWithoutStartingAnotherAction() {
        lookingAtPet = true; bellyAvailable = true;
        configure("pets.wolf.model", "canine");
        assertTrue(actions.triggerTestMoment(owner, "belly"));
        assertTrue(bellyActive);
        pet();
        assertEquals(1, bellyRubs);
        assertTrue(bellyActive);
        assertTrue(bars().contains("scratch"));
        pet.illness(Illness.SICK);
        pet();
        assertFalse(bellyActive, "Owner petting cancels a belly pose that is no longer suitable");
    }

    @Test void healthyAttentionCanStartTheAutomaticBellyMoment() {
        configure("pets.wolf.model", "canine");
        configure("moments.belly-up.chance", 100);
        bellyAvailable = true;
        pet();
        assertTrue(bellyActive);
        assertTrue(bars().contains("rolls onto"));
    }

    @Test void kennelPageNavigationAndSavedPetIconsOpenTheIntendedCareMenu() {
        for (int i = 0; i < 48; i++) {
            Pet saved = new Pet(UUID.randomUUID(), owner.getUniqueId(), "wolf", "Pet" + i, PetSex.FEMALE);
            saved.stored(true); runtime.store().add(saved);
        }
        actions.menus().openKennel(owner);
        assertEquals(0, holder().page());
        actions.clickMenu(owner, holder(), 53, null, false, false, false);
        assertEquals(1, holder().page());
        actions.clickMenu(owner, holder(), 53, null, true, false, false);
        assertEquals(0, holder().page());
        ItemStack icon = holder().getInventory().getItem(0);
        UUID expected = UUID.fromString(icon.getItemMeta().getPersistentDataContainer().get(runtime.petKey(), PersistentDataType.STRING));
        actions.clickMenu(owner, holder(), 0, icon, false, false, false);
        assertEquals(MenuHolder.Kind.CARE, holder().kind());
        assertEquals(expected, holder().petId());
        assertTrue(holder().petHouseBack());
    }

    @Test void kennelMenuRejectsStaleMalformedAndForeignPetIcons() {
        actions.menus().openKennel(owner);
        MenuHolder menu = holder();
        actions.clickMenu(owner, menu, 0, null, false, false, false);
        actions.clickMenu(owner, menu, 0, new ItemStack(Material.AIR), false, false, false);
        actions.clickMenu(owner, menu, 0, new ItemStack(Material.PAPER), false, false, false);
        for (String id : List.of("not-a-uuid", UUID.randomUUID().toString(), pet.id().toString())) {
            if (id.equals(pet.id().toString())) pet.ownerId(UUID.randomUUID());
            ItemStack icon = new ItemStack(Material.PAPER);
            var meta = icon.getItemMeta();
            meta.getPersistentDataContainer().set(runtime.petKey(), PersistentDataType.STRING, id); icon.setItemMeta(meta);
            actions.clickMenu(owner, menu, 0, icon, false, false, false);
            assertSame(menu, holder());
        }
    }

    @Test void chatTrainingOpensWordSelectionAndEmptyOrTransferredSelectionsAreIgnored() {
        lookingAtPet = true;
        assertTrue(actions.useOnPet(owner, body, hold(Material.COD, 2)));
        actions.onChat(owner, "new word");
        assertEquals("new word", runtime.sessions().training(owner.getUniqueId()).pendingWord());
        MenuHolder menu = holder();
        assertEquals(MenuHolder.Kind.TRICK, menu.kind());
        int blank = 0;
        while (menu.trick(blank) != null) blank++;
        actions.clickMenu(owner, menu, blank, menu.getInventory().getItem(blank), false, false, false);
        assertNull(pet.trickFor("new word"));
        pet.ownerId(UUID.randomUUID());
        actions.clickMenu(owner, menu, 0, menu.getInventory().getItem(0), false, false, false);
        assertNull(pet.trickFor("new word"));
    }

    @Test void unknownAddressedWordsDoNotChangeOrders() {
        actions.onChat(owner, "Toby unfamiliar");
        assertEquals(PetOrder.FOLLOW, pet.order());
        assertTrue(bars().contains("has not learned"));
        Pet twin = new Pet(UUID.randomUUID(), owner.getUniqueId(), "wolf", "Toby", PetSex.FEMALE);
        var twinBody = wolf(owner.getLocation().add(2, 0, 0));
        runtime.store().add(twin); runtime.remember(twin, twinBody);
        command("sit", Trick.SIT);
        assertEquals(PetOrder.FOLLOW, pet.order());
        assertEquals(PetOrder.FOLLOW, twin.order());
        assertTrue(messages().contains("More than one"));
    }

    @Test void anUnavailableOptionalProviderRefusesCreationWithoutAddingAPet() {
        MockBukkit.createMockPlugin("MythicMobs");
        configure("pets.wolf.mythic-mob", "UnavailableWolf");
        assertNotNull(runtime.config().type("wolf"));
        assertFalse(net.tfminecraft.companionpets.integration.MythicSpawn.available());
        assertFalse(actions.spawnTestPet(owner, "wolf", "NoBody"));
        assertEquals(1, runtime.store().all().size());
        assertEquals(1, world.getEntities().stream().filter(Wolf.class::isInstance).count());
        assertTrue(messages().contains("Could not spawn"));
    }

    @Test void failedSaveAndRollbackRetainTheOnlyLiveCopyAndDisableRestoration() throws Exception {
        Files.createDirectory(runtime.plugin().getDataFolder().toPath().resolve("pets.yml.tmp"));
        Files.createDirectory(runtime.plugin().getDataFolder().toPath().resolve("pet-deletions.log"));
        assertFalse(actions.spawnTestPet(owner, "wolf", "Unsaved"));
        Pet created = runtime.store().all().stream().filter(candidate -> candidate != pet).findFirst().orElseThrow();
        assertNotNull(runtime.entity(created), "A failed durable deletion must not destroy the only remaining body");
        assertFalse(runtime.store().canRestoreBodies());
        assertTrue(messages().contains("could not be saved"));
    }

    @Test void releasingAnUnloadedPetCommitsDeletionWithoutRecreatingItsBody() {
        UUID unloadedId = body.getUniqueId();
        body.remove();
        assertEquals(unloadedId, pet.entityId());
        assertNull(Bukkit.getEntity(unloadedId));
        care(PetMenus.careSlot(PetMenus.RELEASE_SLOT, false));
        actions.onChat(owner, "yes");
        assertNull(runtime.store().get(pet.id()));
        assertTrue(runtime.store().isDeleted(pet.id()));
        assertEquals(0, world.getEntities().stream().filter(Wolf.class::isInstance).count());
    }

    @Test void spawningBesideAVerticallyLookingOwnerFindsSafeGroundOrRefusesABlockedSearch() {
        owner.teleport(new Location(world, 0, 64, 0, 35, -90));
        var open = PetRuntime.beside(owner);
        assertEquals(new Location(world, .5, 64, 1.5, 35, 0), open);
        assertTrue(PetPlacement.safe(open, PetPlacement.normal(1)));
        for (int x=-2; x<=2; x++) for (int y=64; y<=67; y++) for (int z=-2; z<=2; z++)
            world.getBlockAt(x,y,z).setType(Material.STONE);
        var farther = PetRuntime.beside(owner);
        assertNotNull(farther);
        assertTrue(PetPlacement.safe(farther, PetPlacement.normal(1)));
        assertTrue(Math.abs(farther.getBlockX()) > 2 || Math.abs(farther.getBlockZ()) > 2);
        assertEquals(64, farther.getY()); assertEquals(35, farther.getYaw()); assertEquals(0, farther.getPitch());
        for (int x=-6; x<=6; x++) for (int y=61; y<=68; y++) for (int z=-6; z<=6; z++)
            world.getBlockAt(x,y,z).setType(Material.STONE);
        assertNull(PetRuntime.beside(owner), "A fully blocked search must not place the pet above the owner inside solid blocks");
    }

    @Test void feedingWithoutAnItemLeavesThePetsNeedsAndInventoryUnchanged() {
        var care = new PetCareActions(runtime);
        pet.need(Need.HUNGER, 40); pet.need(Need.HEALTH, 70);
        assertTrue(care.feed(owner,pet,body,null,35,false));
        assertEquals(40,pet.need(Need.HUNGER)); assertEquals(70,pet.need(Need.HEALTH));
    }
}
