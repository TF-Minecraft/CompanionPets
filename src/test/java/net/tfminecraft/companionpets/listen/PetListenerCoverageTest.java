package net.tfminecraft.companionpets.listen;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.*;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.runtime.*;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.testutil.GoalServerMock;
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
class PetListenerCoverageTest {
    private GoalServerMock server;
    private WorldMock world;
    private PlayerMock owner;
    private WolfMock body;
    private Pet pet;
    private PetRuntime runtime;
    private PetActions actions;
    private PetListener listener;
    private final List<String> animations = new ArrayList<>();
    private final List<UUID> cancelledAnimations = new ArrayList<>();
    private boolean attached;

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(new GoalServerMock());
        world = server.addSimpleWorld("listener");
        owner = server.addPlayer();
        owner.teleport(new Location(world, 0, 64, 0));
        var plugin = MockBukkit.createMockPlugin();
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
                items: {kennel: BARREL, kennel-block: BARREL, toys: [STICK], treats: [COD]}
                pets:
                  wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG, default-tricks: []}
                """);
        var visual = new PetVisual() {
            @Override public void apply(Entity entity, PetTypeDef type) { }
            @Override public boolean attached(Entity entity) { return attached; }
            @Override public boolean play(Entity entity, PetTypeDef type, String action) {
                animations.add(entity.getUniqueId() + ":" + action); return false;
            }
            @Override public void cancelAction(Entity entity) { cancelledAnimations.add(entity.getUniqueId()); }
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
            @Override public boolean isInWater() { return false; }
            @Override public boolean isOnGround() { return true; }
            @Override public float getBodyYaw() { return 0; }
            @Override public void setBodyYaw(float yaw) { }
            @Override public void lookAt(Entity target) { }
            @Override public void lookAt(Location target) { }
            @Override public void setRemoveWhenFarAway(boolean remove) { }
            @Override public com.destroystokyo.paper.entity.Pathfinder getPathfinder() {
                return (com.destroystokyo.paper.entity.Pathfinder) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{com.destroystokyo.paper.entity.Pathfinder.class}, (proxy, method, args) -> switch (method.getName()) {
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
    private EntityDamageByEntityEvent attack(Entity attacker, Entity victim, double damage) {
        return new EntityDamageByEntityEvent(attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, damage);
    }

    @Test void previouslyCancelledBlockClickCannotPlaceOrConsumeKennel() {
        var event = kennelEvent();
        event.setCancelled(true);
        fire(event);
        assertNoPlacement();
    }

    @Test void highPriorityProtectionCancellationPreventsKennelPlacement() {
        atHigh(PlayerInteractEvent.class, event -> event.setCancelled(true));
        fire(kennelEvent());
        assertNoPlacement();
    }

    @Test void denyingOnlyItemUsePreventsKennelPlacement() {
        var event = kennelEvent();
        event.setUseItemInHand(Event.Result.DENY);
        assertFalse(event.isCancelled(), "Paper's legacy cancellation flag only reflects block use");
        fire(event);
        assertNoPlacement();
    }

    @Test void allowedBlockClickPlacesOneOwnedKennel() {
        var event = fire(kennelEvent());
        assertTrue(event.isCancelled(), "Consume the interaction after custom placement");
        assertEquals(Material.BARREL, base().getRelative(BlockFace.UP).getType());
        assertEquals(1, owner.getInventory().getItemInMainHand().getAmount());
        assertEquals(owner.getUniqueId(), runtime.store().kennelOwner(PetStore.kennelKey(world.getName(), 3, 64, 0)));
    }

    @Test void preCancelledAirClickStillThrowsConfiguredToy() {
        var event = use(Action.RIGHT_CLICK_AIR, hold(Material.STICK, 2), null, EquipmentSlot.HAND);
        event.setCancelled(true);
        fire(event);
        var balls = world.getEntities().stream().filter(Snowball.class::isInstance).toList();
        assertEquals(1, balls.size());
        assertTrue(balls.getFirst().getPersistentDataContainer().has(runtime.toyKey(), PersistentDataType.STRING));
        assertEquals(1, owner.getInventory().getItemInMainHand().getAmount());
    }

    @Test void highPriorityDamageCancellationPreservesMoodAndAnimations() {
        atHigh(EntityDamageByEntityEvent.class, event -> event.setCancelled(true));
        var event = fire(attack(owner, body, 2));
        assertTrue(event.isCancelled());
        assertEquals(80, pet.need(Need.MOOD));
        assertTrue(animations.isEmpty());
        assertTrue(cancelledAnimations.isEmpty());
    }

    @Test void zeroFinalDamageDoesNotCountAsBeingStruck() {
        fire(attack(owner, body, 0));
        assertEquals(80, pet.need(Need.MOOD));
        assertTrue(animations.isEmpty());
    }

    @Test void unhandledWorldClicksLeaveInventoryAndEventAlone() {
        for (var action : List.of(Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK, Action.PHYSICAL)) {
            var event = fire(use(action, hold(Material.BARREL, 2), base(), EquipmentSlot.HAND));
            assertNotEquals(Event.Result.DENY, event.useItemInHand());
            assertEquals(2, owner.getInventory().getItemInMainHand().getAmount());
        }
        var offHand = kennelEvent();
        fire(use(offHand.getAction(), offHand.getItem(), offHand.getClickedBlock(), EquipmentSlot.OFF_HAND));
        assertNoPlacement();
        var ordinary = fire(use(Action.RIGHT_CLICK_BLOCK, hold(Material.DIRT, 2), base(), EquipmentSlot.HAND));
        assertFalse(ordinary.isCancelled());
        assertEquals(2, owner.getInventory().getItemInMainHand().getAmount());
    }

    @Test void mainHandFeedingAndModelCallbackDeduplicateTheSameTick() {
        pet.need(Need.HUNGER, 10);
        hold(Material.COD, 8);
        var offhand = fire(new PlayerInteractEntityEvent(owner, body, EquipmentSlot.OFF_HAND));
        assertFalse(offhand.isCancelled());
        assertEquals(10, pet.need(Need.HUNGER));
        assertTrue(fire(new PlayerInteractEntityEvent(owner, body, EquipmentSlot.HAND)).isCancelled());
        double fed = pet.need(Need.HUNGER);
        assertTrue(fed > 10);
        assertEquals(7, owner.getInventory().getItemInMainHand().getAmount());
        attached = true;
        listener.onModelInteract(owner, body);
        fire(new PlayerInteractEntityEvent(owner, body, EquipmentSlot.HAND));
        assertEquals(fed, pet.need(Need.HUNGER));
        assertEquals(7, owner.getInventory().getItemInMainHand().getAmount());
        var stranger = server.addPlayer();
        assertFalse(fire(new PlayerInteractEntityEvent(owner, stranger, EquipmentSlot.HAND)).isCancelled());
    }

    @Test void modelInteractionRequiresAttachmentBeforeStartingTraining() {
        hold(Material.COD, 2);
        listener.onModelInteract(owner, body);
        assertNull(runtime.sessions().training(owner.getUniqueId()));
        attached = true;
        listener.onModelInteract(owner, body);
        assertEquals(pet.id(), runtime.sessions().training(owner.getUniqueId()).petId());
        assertEquals(2, owner.getInventory().getItemInMainHand().getAmount());
    }

    @Test void committedDamagePenalizesVictimOnceAndAnimatesBothPets() {
        var attackerBody = wolf(owner.getLocation().add(2, 0, 0));
        Pet attacker = new Pet(UUID.randomUUID(), owner.getUniqueId(), "wolf", "Luna", PetSex.FEMALE);
        runtime.store().add(attacker);
        runtime.remember(attacker, attackerBody);
        body.setSilent(true);
        var event = fire(attack(attackerBody, body, 2));
        assertFalse(event.isCancelled());
        assertEquals(80 - runtime.config().care().struckMoodPenalty(), pet.need(Need.MOOD));
        assertEquals(List.of(body.getUniqueId() + ":HURT", attackerBody.getUniqueId() + ":ATTACK"), animations);
        assertEquals(List.of(body.getUniqueId(), attackerBody.getUniqueId()), cancelledAnimations);
        assertEquals(100, attacker.need(Need.MOOD));
    }

    @Test void environmentalDamageHurtsWithoutAMoodPenaltyAndUnrelatedMobsStayUntouched() {
        fire(new EntityDamageEvent(body, EntityDamageEvent.DamageCause.FALL, 2));
        assertEquals(List.of(body.getUniqueId() + ":HURT"), animations);
        assertEquals(80, pet.need(Need.MOOD));
        animations.clear();
        fire(attack(owner, server.addPlayer(), 2));
        assertTrue(animations.isEmpty());
    }

    @Test void toyProjectileDamageIsCancelledBeforeMoodOrVisualEffects() {
        Snowball toy = world.spawn(owner.getEyeLocation(), Snowball.class);
        toy.getPersistentDataContainer().set(runtime.toyKey(), PersistentDataType.STRING, UUID.randomUUID().toString());
        assertTrue(fire(attack(toy, body, 1)).isCancelled());
        assertEquals(80, pet.need(Need.MOOD));
        assertTrue(animations.isEmpty());
        var ordinary = world.spawn(owner.getEyeLocation(), Snowball.class);
        assertFalse(fire(attack(ordinary, body, 1)).isCancelled());
        assertEquals(80 - runtime.config().care().struckMoodPenalty(), pet.need(Need.MOOD));
    }

    @Test void landingThrownToyPreservesItsItemAndProtectsItFromPickups() {
        fire(use(Action.RIGHT_CLICK_AIR, hold(Material.STICK, 2), null, EquipmentSlot.HAND));
        Snowball ball = world.getEntities().stream().filter(Snowball.class::isInstance).map(Snowball.class::cast).findFirst().orElseThrow();
        assertNotNull(pet.fetch());
        ball.teleport(new Location(world, 4, 64, 0));
        fire(new ProjectileHitEvent(ball));
        assertFalse(ball.isValid());
        assertEquals(net.tfminecraft.companionpets.play.FetchPhase.GROUND, pet.fetch().phase());
        Item item = (Item) Bukkit.getEntity(pet.fetch().itemId());
        assertNotNull(item);
        assertEquals(Material.STICK, item.getItemStack().getType());
        assertTrue(fire(new PlayerAttemptPickupItemEvent(owner, item)).isCancelled());
        assertTrue(fire(new EntityPickupItemEvent(body, item, 0)).isCancelled());
        assertTrue(fire(new EntityPickupItemEvent(server.addPlayer(), item, 0)).isCancelled());
    }

    @Test void unrelatedProjectilesAndItemsKeepTheirNormalBehavior() {
        Snowball snowball = world.spawn(owner.getEyeLocation(), Snowball.class);
        fire(new ProjectileHitEvent(snowball));
        assertTrue(snowball.isValid());
        var arrow = world.spawn(owner.getEyeLocation(), Arrow.class);
        fire(new ProjectileHitEvent(arrow));
        assertTrue(arrow.isValid());
        Item item = world.dropItem(owner.getLocation(), new ItemStack(Material.COBBLESTONE));
        assertFalse(fire(new PlayerAttemptPickupItemEvent(owner, item)).isCancelled());
        assertFalse(fire(new EntityPickupItemEvent(server.addPlayer(), item, 0)).isCancelled());
        assertTrue(fire(new EntityPickupItemEvent(body, item, 0)).isCancelled(), "Pets must not equip random world items");
    }

    @Test void cancelledKennelBreakRetainsOwnershipUntilTheBreakCommits() {
        Block block = base();
        String key = PetStore.kennelKey(world.getName(), block.getX(), block.getY(), block.getZ());
        runtime.store().kennel(key, owner.getUniqueId());
        var cancelled = new BlockBreakEvent(block, owner);
        cancelled.setCancelled(true);
        fire(cancelled);
        assertEquals(owner.getUniqueId(), runtime.store().kennelOwner(key));
        fire(new BlockBreakEvent(block, owner));
        assertNull(runtime.store().kennelOwner(key));
        fire(new BlockBreakEvent(block, owner));
        assertNull(runtime.store().kennelOwner(key));
    }

    @Test void loadRestoresTheTaggedBodyAndUnloadRecordsOnlyItsAuthoritativeLocation() {
        pet.entityId(null);
        fire(new EntitiesLoadEvent(body.getChunk(), List.of(owner, body)));
        assertEquals(body.getUniqueId(), pet.entityId());
        assertTrue(body.isTamed());
        assertEquals(owner.getUniqueId(), body.getOwner().getUniqueId());
        body.teleport(new Location(world, 7, 65, 9, 35, 0));
        var duplicate = wolf(new Location(world, 20, 70, 22));
        duplicate.getPersistentDataContainer().set(runtime.petKey(), PersistentDataType.STRING, pet.id().toString());
        fire(new EntitiesUnloadEvent(body.getChunk(), List.of(owner, body, duplicate)));
        assertEquals(7, pet.x());
        assertEquals(65, pet.y());
        assertEquals(9, pet.z());
        assertEquals(35, pet.yaw());
        assertEquals(body.getUniqueId(), pet.entityId());
        fire(new EntitiesLoadEvent(duplicate.getChunk(), List.of(duplicate)));
        assertFalse(duplicate.isValid());
        assertTrue(body.isValid());
    }

    private EntityDeathEvent death(LivingEntity entity) {
        return new EntityDeathEvent(entity, DamageSource.builder(DamageType.GENERIC).build(),
                new ArrayList<>(List.of(new ItemStack(Material.BONE))), 7);
    }

    @Test void observedDeathDurablyRemovesThePetAndNotifiesItsOwner() {
        body.setSilent(true);
        fire(death(body));
        assertNull(runtime.store().get(pet.id()));
        assertTrue(runtime.store().isDeleted(pet.id()));
        assertTrue(owner.nextMessage().contains("has died"));
    }

    @Test void neglectDeathSuppressesDropsBeforeAndAfterDurableDeletion() {
        pet.dead(true);
        var pending = fire(death(body));
        assertTrue(pending.getDrops().isEmpty());
        assertEquals(0, pending.getDroppedExp());
        assertTrue(runtime.store().remove(pet.id()));
        var deleted = fire(death(body));
        assertTrue(deleted.getDrops().isEmpty());
        assertEquals(0, deleted.getDroppedExp());
        var ordinary = fire(death(wolf(owner.getLocation())));
        assertEquals(1, ordinary.getDrops().size());
        assertEquals(7, ordinary.getDroppedExp());
    }

    @Test void cancelledDeathMustNotDeleteTheLivingPet() {
        atHigh(EntityDeathEvent.class, event -> event.setCancelled(true));
        var event = fire(death(body));
        assertTrue(event.isCancelled());
        assertSame(pet, runtime.store().get(pet.id()));
        assertFalse(runtime.store().isDeleted(pet.id()));
        assertTrue(body.isValid());
    }

    @Test void joinAndQuitSuspendDistantFollowingAndClearInteractionDeduplication() {
        hold(Material.COD, 8);
        pet.need(Need.HUNGER, 10);
        fire(new PlayerInteractEntityEvent(owner, body, EquipmentSlot.HAND));
        assertEquals(7, owner.getInventory().getItemInMainHand().getAmount());
        body.teleport(owner.getLocation().add(30, 0, 0));
        fire(new PlayerJoinEvent(owner, net.kyori.adventure.text.Component.empty()));
        assertFalse(runtime.followingAllowed(pet, owner));
        runtime.sessions().rename(owner.getUniqueId(), new RenamePrompt(pet.id(), System.currentTimeMillis() + 30_000));
        fire(new PlayerQuitEvent(owner, net.kyori.adventure.text.Component.empty()));
        assertNull(runtime.sessions().privatePrompt(owner.getUniqueId()));
        assertFalse(runtime.followingAllowed(pet, owner));
        // A reconnect can deliver a new interaction within the same server tick.
        fire(new PlayerInteractEntityEvent(owner, body, EquipmentSlot.HAND));
        assertEquals(6, owner.getInventory().getItemInMainHand().getAmount());
    }

    @Test void careMenuClickStartsPrivateRenameAndChatCommitsItOnTheMainThread() {
        owner.setSneaking(true);
        hold(Material.AIR, 1);
        fire(new PlayerInteractEntityEvent(owner, body, EquipmentSlot.HAND));
        assertEquals(MenuHolder.Kind.CARE, ((MenuHolder) owner.getOpenInventory().getTopInventory().getHolder()).kind());
        var click = fire(new InventoryClickEvent(owner.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                PetMenus.NAME_SLOT, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        assertTrue(click.isCancelled());
        RenamePrompt prompt = runtime.sessions().rename(owner.getUniqueId());
        assertNotNull(prompt);
        var name = fire(chat("Luna"));
        assertTrue(name.isCancelled());
        assertNull(prompt.name(), "Async event only schedules domain work");
        server.getScheduler().performOneTick();
        assertEquals("Luna", prompt.name());
        assertEquals("Toby", pet.name());
        assertTrue(fire(chat("yes")).isCancelled());
        server.getScheduler().performOneTick();
        assertEquals("Luna", pet.name());
        assertNull(runtime.sessions().rename(owner.getUniqueId()));
    }

    private AsyncPlayerChatEvent chat(String text) {
        return new AsyncPlayerChatEvent(false, owner, text, new HashSet<>(server.getOnlinePlayers()));
    }

    @Test void queuedChatCannotAnswerANewPromptOrRunAfterDisconnect() {
        fire(chat("public text"));
        var first = new RenamePrompt(pet.id(), System.currentTimeMillis() + 30_000);
        runtime.sessions().rename(owner.getUniqueId(), first);
        server.getScheduler().performOneTick();
        assertNull(first.name());
        assertTrue(fire(chat("stale answer")).isCancelled());
        var replacement = new RenamePrompt(pet.id(), System.currentTimeMillis() + 30_000);
        runtime.sessions().rename(owner.getUniqueId(), replacement);
        server.getScheduler().performOneTick();
        assertNull(replacement.name());
        fire(chat("last answer"));
        owner.disconnect();
        server.getScheduler().performOneTick();
        assertEquals("Toby", pet.name());
        assertNull(runtime.sessions().privatePrompt(owner.getUniqueId()));
    }

    @Test void normalInventoryClicksDoNotAcquirePetMenuRestrictions() {
        var inventory = Bukkit.createInventory(null, 9);
        owner.openInventory(inventory);
        assertFalse(fire(new InventoryClickEvent(owner.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                0, ClickType.LEFT, InventoryAction.PICKUP_ALL)).isCancelled());
        var item = new ItemStack(Material.DIRT);
        assertFalse(fire(new InventoryDragEvent(owner.getOpenInventory(), item, item, false, Map.of(0, item))).isCancelled());
        owner.closeInventory();
    }
}
