package net.tfminecraft.companionpets.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.visual.PetVisual;

class PetInteractionTest {
    private PetRuntime runtime;
    private PetActions actions;
    private PlayerMock player;
    private WolfMock body;
    private Pet pet;
    private int bodyRemovals;
    private int navigationStops;
    private Location navigationTarget;

    @BeforeEach void setup() throws Exception {
        var goals = new java.util.HashMap<String, com.destroystokyo.paper.entity.ai.Goal<?>>();
        var server = MockBukkit.mock(new org.mockbukkit.mockbukkit.ServerMock() {
            @Override public com.destroystokyo.paper.entity.ai.MobGoals getMobGoals() {
                return (com.destroystokyo.paper.entity.ai.MobGoals) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{com.destroystokyo.paper.entity.ai.MobGoals.class}, (proxy, method, args) -> {
                            var id = ((org.bukkit.entity.Mob) args[0]).getUniqueId();
                            return switch (method.getName()) {
                                case "getGoal" -> goals.get(id + ":" + args[1]);
                                case "addGoal" -> { goals.put(id + ":" + ((com.destroystokyo.paper.entity.ai.Goal<?>) args[2]).getKey(), (com.destroystokyo.paper.entity.ai.Goal<?>) args[2]); yield null; }
                                case "removeGoal" -> { goals.remove(id + ":" + args[1]); yield null; }
                                case "removeAllGoals" -> { goals.keySet().removeIf(k -> k.startsWith(id + ":")); yield null; }
                                default -> throw new AssertionError("Unexpected goals call: " + method.getName());
                            };
                        });
            }
        });
        var plugin = MockBukkit.createMockPlugin();
        var world = new WorldMock() {
            @Override public org.bukkit.util.RayTraceResult rayTraceBlocks(Location at, org.bukkit.util.Vector direction, double distance, org.bukkit.FluidCollisionMode fluids, boolean ignorePassable) { return null; }
            @Override public org.bukkit.util.RayTraceResult rayTraceEntities(Location at, org.bukkit.util.Vector direction, double distance, java.util.function.Predicate<? super Entity> filter) { return null; }
            @Override public <T extends Entity> T spawn(Location at, Class<T> type) {
                if (type != org.bukkit.entity.Wolf.class) return super.spawn(at, type);
                var wolf = new WolfMock(server, UUID.randomUUID()) {
                    @Override public void setRemoveWhenFarAway(boolean remove) { }
                    @Override public com.destroystokyo.paper.entity.Pathfinder getPathfinder() {
                        return (com.destroystokyo.paper.entity.Pathfinder) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[]{com.destroystokyo.paper.entity.Pathfinder.class}, (proxy, method, args) -> switch (method.getName()) {
                                    case "stopPathfinding" -> { navigationStops++; yield null; }
                                    case "moveTo" -> { navigationTarget = ((Location) args[0]).clone(); yield true; }
                                    case "hasPath" -> false;
                                    case "getEntity" -> this;
                                    default -> throw new AssertionError("Unexpected navigation call: " + method.getName());
                                });
                    }
                };
                server.registerEntity(wolf);
                wolf.teleport(at);
                return type.cast(wolf);
            }
            @Override public BlockMock getBlockAt(int x, int y, int z) {
                return new BlockMock(new Location(this, x, y, z)) {
                    @Override public boolean isPassable() { return true; }
                };
            }
        };
        server.addWorld(world);
        player = server.addPlayer();
        player.teleport(new Location(world, 0, 64, 0));
        player.openInventory(server.createInventory(null, 9));
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
                limits: {max-out: 2}
                items:
                  treats: [COD, SALMON]
                  foods: [{item: BEEF, hunger: 35}]
                  medicines: [MILK_BUCKET, HONEY_BOTTLE]
                  brushes: [FEATHER]
                  toys: [STICK]
                pets:
                  wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG, sex: choose}
                  selective:
                    entity: WOLF
                    egg: CAT_SPAWN_EGG
                    items: {foods: [], treats: [], medicines: [], brushes: [], toys: []}
                """);
        var visual = new PetVisual() {
            @Override public void apply(Entity entity, net.tfminecraft.companionpets.config.PetTypeDef type) { }
            @Override public void removeBody(Entity entity) {
                bodyRemovals++;
                PetVisual.super.removeBody(entity);
            }
        };
        var key = new NamespacedKey(plugin, "pet");
        runtime = new PetRuntime(plugin, CompanionConfig.load(plugin, yaml), loadedStore(plugin),
                new Sessions(), new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        actions = new PetActions(runtime);
        pet = new Pet(UUID.randomUUID(), player.getUniqueId(), "wolf", "Toby", PetSex.MALE);
        body = (WolfMock) world.spawn(player.getLocation().add(1, 0, 0), org.bukkit.entity.Wolf.class);
        body.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.STRING, pet.id().toString());
        net.tfminecraft.companionpets.training.DefaultTricks.apply(runtime.config(), pet); runtime.store().add(pet);
        runtime.remember(pet, body);
        pet.stored(false);
    }

    private static PetStore loadedStore(org.bukkit.plugin.java.JavaPlugin plugin) {
        var store = new PetStore(plugin);
        assertTrue(store.load());
        return store;
    }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    private ItemStack hold(Material material, int amount) {
        player.getInventory().setItemInMainHand(new ItemStack(material, amount));
        return player.getInventory().getItemInMainHand();
    }

    @Test void feedingConsumesOneItemAndUsesConfiguredGain() {
        pet.need(Need.HUNGER, 20);
        var hand = hold(Material.BEEF, 2);
        assertTrue(actions.useOnPet(player, body, hand));
        assertEquals(55, pet.need(Need.HUNGER));
        assertEquals(1, hand.getAmount());
    }

    @Test void creativeFeedingPreservesStack() {
        player.setGameMode(GameMode.CREATIVE);
        pet.need(Need.HUNGER, 20);
        var hand = hold(Material.BEEF, 2);
        assertTrue(actions.useOnPet(player, body, hand));
        assertEquals(55, pet.need(Need.HUNGER));
        assertEquals(2, hand.getAmount());
    }

    @Test void overfeedingConsumesFoodAndAppliesConfiguredPenalties() {
        pet.need(Need.HUNGER, 100);
        pet.need(Need.HEALTH, 100);
        pet.need(Need.MOOD, 100);
        var hand = hold(Material.BEEF, 2);
        assertTrue(actions.useOnPet(player, body, hand));
        assertEquals(100, pet.need(Need.HUNGER));
        assertEquals(100 - runtime.config().care().overfeedHealthPenalty(), pet.need(Need.HEALTH));
        assertEquals(100 - runtime.config().care().overfeedMoodPenalty(), pet.need(Need.MOOD));
        assertEquals(1, hand.getAmount());
    }

    @Test void eitherTreatFeedsAHungryPetWithoutStartingTraining() {
        for (var material : new Material[] {Material.COD, Material.SALMON}) {
            pet.need(Need.HUNGER, 20);
            var hand = hold(material, 2);
            assertTrue(actions.useOnPet(player, body, hand));
            assertEquals(50, pet.need(Need.HUNGER));
            assertEquals(1, hand.getAmount());
            assertNull(runtime.sessions().training(player.getUniqueId()));
        }
    }

    @Test void eitherMedicineTreatsSickPetsAndConsumesOneItem() {
        for (var material : new Material[] {Material.MILK_BUCKET, Material.HONEY_BOTTLE}) {
            pet.illness(Illness.SICK);
            pet.treated(false);
            pet.need(Need.HEALTH, 20);
            var hand = hold(material, 2);
            assertTrue(actions.useOnPet(player, body, hand));
            assertTrue(pet.treated());
            assertEquals(20 + runtime.config().care().medicineHealthBump(), pet.need(Need.HEALTH));
            assertEquals(1, hand.getAmount());
        }
    }

    @Test void healthyPetsDoNotConsumeMedicine() {
        var hand = hold(Material.MILK_BUCKET, 2);
        assertFalse(actions.useOnPet(player, body, hand));
        assertEquals(2, hand.getAmount());
        assertFalse(pet.treated());
    }

    @Test void brushingRestoresCleanlinessWithoutConsumingTheBrush() {
        pet.need(Need.CLEANLINESS, 1);
        var hand = hold(Material.FEATHER, 1);
        assertTrue(actions.useOnPet(player, body, hand));
        assertEquals(100, pet.need(Need.CLEANLINESS));
        assertEquals(1, hand.getAmount());
    }

    @Test void toyHintDoesNotConsumeTheToy() {
        var hand = hold(Material.STICK, 2);
        assertTrue(actions.useOnPet(player, body, hand));
        assertEquals(2, hand.getAmount());
        assertNull(pet.fetch());
    }

    @Test void storedPetDoesNotConsumeFoodOrChangeNeeds() {
        pet.stored(true);
        pet.need(Need.HUNGER, 20);
        var hand = hold(Material.BEEF, 2);
        assertFalse(actions.useOnPet(player, body, hand));
        assertEquals(20, pet.need(Need.HUNGER));
        assertEquals(2, hand.getAmount());
    }

    @Test void explicitEmptyPetListsDisableAllGlobalCareItems() {
        var selective = new Pet(UUID.randomUUID(), player.getUniqueId(), "selective", "Other", PetSex.FEMALE);
        runtime.store().add(selective);
        selective.stored(false);
        runtime.remember(selective, body);
        body.getPersistentDataContainer().set(runtime.petKey(), org.bukkit.persistence.PersistentDataType.STRING, selective.id().toString());
        selective.need(Need.HUNGER, 20);
        selective.need(Need.CLEANLINESS, 20);
        selective.need(Need.HEALTH, 20);
        selective.illness(Illness.SICK);
        for (var material : new Material[] {Material.BEEF, Material.COD, Material.MILK_BUCKET, Material.FEATHER, Material.STICK}) {
            var hand = hold(material, 2);
            assertFalse(actions.useOnPet(player, body, hand));
            assertEquals(2, hand.getAmount());
        }
        assertEquals(20, selective.need(Need.HUNGER));
        assertEquals(20, selective.need(Need.CLEANLINESS));
        assertEquals(20, selective.need(Need.HEALTH));
    }

    private void startHatch() {
        var hand = hold(Material.WOLF_SPAWN_EGG, 2);
        actions.useWorld(player, hand, null, null, false, true);
        assertNotNull(runtime.sessions().hatch(player.getUniqueId()));
        assertEquals(2, hand.getAmount());
    }

    @Test void confirmedReleaseDeletesVisualAndBodyAndRecordsDeletion() {
        assertTrue(runtime.store().load());
        net.tfminecraft.companionpets.training.DefaultTricks.apply(runtime.config(), pet); runtime.store().add(pet);
        runtime.sessions().release(player.getUniqueId(), new net.tfminecraft.companionpets.session.ReleasePrompt(pet.id(), System.currentTimeMillis() + 30_000));
        actions.onChat(player, "yes");
        assertEquals(1, bodyRemovals, "Release must use body deletion, which does not reveal the vanilla mob");
        assertFalse(body.isValid());
        assertNull(runtime.store().get(pet.id()));
        assertTrue(runtime.store().isDeleted(pet.id()));
        assertNull(runtime.sessions().release(player.getUniqueId()));
    }

    @Test void cancelledReleaseRetainsBodyAndRecord() {
        runtime.sessions().release(player.getUniqueId(), new net.tfminecraft.companionpets.session.ReleasePrompt(pet.id(), System.currentTimeMillis() + 30_000));
        actions.onChat(player, "no");
        assertEquals(0, bodyRemovals);
        assertTrue(body.isValid());
        assertSame(pet, runtime.store().get(pet.id()));
        assertFalse(runtime.store().isDeleted(pet.id()));
    }

    @Test void shelterDeletesVisualAndBodyButPreservesLearningAndNeeds() {
        pet.progress(Trick.FOLLOW, 73); pet.bindWord("here", Trick.FOLLOW); pet.need(Need.HUNGER, 55);
        var holder = new net.tfminecraft.companionpets.gui.MenuHolder(net.tfminecraft.companionpets.gui.MenuHolder.Kind.CARE, pet.id(), null);
        actions.clickMenu(player, holder, net.tfminecraft.companionpets.gui.PetMenus.STORE_SLOT, null, false, false, false);
        assertEquals(1, bodyRemovals, "Shelter must not detach by revealing the vanilla mob");
        assertFalse(body.isValid());
        assertTrue(pet.stored()); assertNull(pet.entityId());
        assertEquals(55, pet.need(Need.HUNGER)); assertEquals(73, pet.progress(Trick.FOLLOW)); assertEquals(Trick.FOLLOW, pet.trickFor("here"));
        assertSame(pet, runtime.store().get(pet.id())); assertFalse(runtime.store().isDeleted(pet.id()));
    }

    @Test void defaultFollowResumesEveryPosture() {
        assertEquals(Trick.FOLLOW, pet.trickFor("follow")); assertEquals(100, pet.progress(Trick.FOLLOW));
        for (PetOrder order : new PetOrder[]{PetOrder.SIT, PetOrder.STAY, PetOrder.LAY}) {
            pet.order(order); pet.staying(true); pet.activity(Activity.SLEEPING); body.setSitting(true); body.setAware(false);
            actions.onChat(player, "Toby follow");
            assertEquals(PetOrder.FOLLOW, pet.order()); assertFalse(pet.staying()); assertEquals(Activity.NONE, pet.activity());
            assertFalse(body.isSitting()); assertTrue(body.isAware());
            assertEquals(100, pet.progress(Trick.FOLLOW), "Follow is a learned foundation");
        }
    }

    @Test void namedOrdersWorkInEitherOrderWithoutAiming() {
        pet.bindWord("sit down", Trick.SIT); pet.progress(Trick.SIT, 100);
        actions.onChat(player, "Toby, sit down!"); assertEquals(PetOrder.SIT, pet.order()); assertTrue(body.isSitting()); assertFalse(body.isAware());
        actions.onChat(player, "follow Toby"); assertEquals(PetOrder.FOLLOW, pet.order());
        actions.onChat(player, "sit down Toby"); assertEquals(PetOrder.SIT, pet.order());
        pet.bindWord("stay", Trick.STAY); pet.progress(Trick.STAY, 100);
        actions.onChat(player, "Toby stay"); assertEquals(PetOrder.STAY, pet.order()); assertFalse(body.isSitting()); assertFalse(body.isAware());
    }

    @Test void remoteOtherOwnerAndStoredPetsCannotHearNamedOrders() {
        pet.order(PetOrder.SIT);
        body.teleport(player.getLocation().add(13, 0, 0)); actions.onChat(player, "Toby follow"); assertEquals(PetOrder.SIT, pet.order());
        body.teleport(player.getLocation().add(1, 0, 0));
        pet.stored(true); actions.onChat(player, "Toby follow"); assertEquals(PetOrder.SIT, pet.order());
        pet.stored(false); pet.ownerId(UUID.randomUUID()); actions.onChat(player, "Toby follow"); assertEquals(PetOrder.SIT, pet.order());
    }

    @Test void stayRemainsStandingAndRejectsNativeTeleportsWhileFollowingAndComeCanMove() {
        var listener = new net.tfminecraft.companionpets.listen.PetListener(runtime, actions);
        pet.bindWord("stay", Trick.STAY); pet.progress(Trick.STAY, 100);
        actions.onChat(player, "Toby stay");
        assertEquals(PetOrder.STAY, pet.order()); assertFalse(body.isSitting());
        var at = body.getLocation().clone();
        player.teleport(player.getLocation().add(100, 0, 0));
        new PetTicker(runtime, actions).run();
        assertEquals(at, body.getLocation()); assertFalse(body.isSitting()); assertFalse(body.isAware());
        for (PetOrder order : new PetOrder[]{PetOrder.STAY, PetOrder.SIT, PetOrder.LAY}) {
            pet.order(order); pet.staying(order == PetOrder.STAY);
            var event = new org.bukkit.event.entity.EntityTeleportEvent(body, at, player.getLocation());
            listener.onTeleport(event); assertTrue(event.isCancelled(), order + " blocks native teleports");
        }
        pet.order(PetOrder.FOLLOW); pet.staying(false); pet.activity(Activity.ATTENDING);
        var event = new org.bukkit.event.entity.EntityTeleportEvent(body, at, player.getLocation());
        listener.onTeleport(event); assertFalse(event.isCancelled(), "Come temporarily uses Follow and may reach its owner");
        var ordinary = new org.bukkit.event.entity.EntityTeleportEvent(player, player.getLocation(), at);
        listener.onTeleport(ordinary); assertFalse(ordinary.isCancelled());
    }

    @Test void ambiguousPetNamesDoNotExecuteCommandsOnEitherPet() {
        pet.order(PetOrder.SIT);
        var other = new Pet(UUID.randomUUID(), player.getUniqueId(), "wolf", "Toby", PetSex.FEMALE);
        other.order(PetOrder.SIT); runtime.store().add(other);
        var otherBody = body.getWorld().spawn(player.getLocation().add(2, 0, 0), org.bukkit.entity.Wolf.class); runtime.remember(other, otherBody);
        actions.onChat(player, "Toby follow");
        assertEquals(PetOrder.SIT, pet.order()); assertEquals(PetOrder.SIT, other.order());
        assertTrue(player.nextMessage().contains("More than one"));
    }

    @Test void hearingANameHoldsAttentionButDoesNotCancelAnExplicitPosture() {
        actions.onChat(player, "Toby"); assertEquals(Activity.ATTENDING, pet.activity());
        pet.order(PetOrder.STAY); pet.staying(true); pet.activity(Activity.NONE);
        actions.onChat(player, "Toby"); assertEquals(PetOrder.STAY, pet.order()); assertTrue(pet.staying()); assertEquals(Activity.NONE, pet.activity());
    }

    @Test void attentionWaitStartsAfterArrivalAndPreventsWanderingForTenSeconds() {
        long now = System.currentTimeMillis();
        actions.roaming().attend(pet, player, now);
        assertTrue(actions.roaming().tickAttention(pet, body, now + 29_000));
        assertFalse(body.isAware()); assertTrue(navigationStops > 0);
        assertTrue(actions.roaming().tickAttention(pet, body, now + 38_999));
        assertFalse(actions.roaming().tickAttention(pet, body, now + 39_000));
        assertEquals(Activity.NONE, pet.activity());
    }

    @Test void nameCallTracksCurrentOwnerAndEveryPostureCancelsItsRouteAndHorizontalVelocity() {
        for (Trick posture : new Trick[]{Trick.SIT, Trick.STAY, Trick.LAY}) {
            pet.bindWord(posture.name().toLowerCase(), posture); pet.progress(posture, 100);
            actions.onChat(player, "Toby follow");
            body.teleport(player.getLocation().add(8, 0, 0));
            actions.onChat(player, "Toby");
            long now = System.currentTimeMillis();
            assertTrue(actions.roaming().tickAttention(pet, body, now));
            assertEquals(player.getLocation(), navigationTarget);
            player.teleport(player.getLocation().add(1, 0, 1));
            assertTrue(actions.roaming().tickAttention(pet, body, now + 1));
            assertEquals(player.getLocation(), navigationTarget, "A name call uses the owner's current position");
            body.setVelocity(new org.bukkit.util.Vector(0.4, -0.2, 0.3));
            actions.onChat(player, "Toby " + posture.name().toLowerCase());
            assertEquals(posture.name(), pet.order().name()); assertFalse(body.isAware());
            assertEquals(0, body.getVelocity().getX()); assertEquals(0, body.getVelocity().getZ());
            assertEquals(-0.2, body.getVelocity().getY(), "Holding keeps gravity");
            assertFalse(actions.roaming().tickAttention(pet, body, now + 2), "The previous call must be cancelled");
            var goal = org.bukkit.Bukkit.getMobGoals().getGoal(body,
                    com.destroystokyo.paper.entity.ai.GoalKey.of(org.bukkit.entity.Mob.class,
                            new NamespacedKey(runtime.plugin(), "call_navigation")));
            assertNotNull(goal); assertFalse(goal.shouldActivate(), "The call cannot take over a posture");
        }
    }

    @Test void comeGetsUpWalksToCurrentOwnerAndRestoresEachPostureOnlyAfterArrival() {
        pet.bindWord("come", Trick.COME); pet.progress(Trick.COME, 100);
        for (PetOrder previous : PetOrder.values()) {
            actions.onChat(player, "Toby follow");
            if (previous != PetOrder.FOLLOW) {
                Trick posture = Trick.valueOf(previous.name()); pet.bindWord(previous.name().toLowerCase(), posture); pet.progress(posture, 100);
                actions.onChat(player, "Toby " + previous.name().toLowerCase());
            }
            body.teleport(player.getLocation().add(8, 0, 0));
            actions.onChat(player, "come Toby");
            assertEquals(PetOrder.FOLLOW, pet.order()); assertEquals(Activity.ATTENDING, pet.activity());
            assertTrue(body.isAware()); assertFalse(body.isSitting());
            long now = System.currentTimeMillis();
            assertTrue(actions.roaming().tickAttention(pet, body, now)); assertEquals(player.getLocation(), navigationTarget);
            player.teleport(player.getLocation().add(1, 0, 1));
            assertTrue(actions.roaming().tickAttention(pet, body, now + 1)); assertEquals(player.getLocation(), navigationTarget);
            assertEquals(PetOrder.FOLLOW, pet.order(), "The old posture must not return while walking");
            body.teleport(player.getLocation().add(1, 0, 0)); body.setVelocity(new org.bukkit.util.Vector(0.3, 0, 0.2));
            assertTrue(actions.roaming().tickAttention(pet, body, now + 2));
            assertEquals(previous, pet.order()); assertEquals(previous == PetOrder.STAY, pet.staying());
            assertEquals(previous == PetOrder.LAY ? Activity.SLEEPING : Activity.NONE, pet.activity());
            assertEquals(previous == PetOrder.FOLLOW, body.isAware());
            assertEquals(previous == PetOrder.SIT || previous == PetOrder.LAY, body.isSitting());
            assertEquals(0, body.getVelocity().getX()); assertEquals(0, body.getVelocity().getZ());
            assertFalse(actions.roaming().tickAttention(pet, body, now + 3));
        }
    }

    @Test void repeatedComeKeepsItsOriginalPostureAndNewOrdersCancelItsReturn() {
        pet.bindWord("come", Trick.COME); pet.progress(Trick.COME, 100); pet.bindWord("lay", Trick.LAY); pet.progress(Trick.LAY, 100);
        actions.onChat(player, "Toby lay"); body.teleport(player.getLocation().add(8, 0, 0));
        actions.onChat(player, "Toby come"); actions.onChat(player, "Toby come"); actions.onChat(player, "Toby");
        body.teleport(player.getLocation().add(1, 0, 0));
        assertTrue(actions.roaming().tickAttention(pet, body, System.currentTimeMillis())); assertEquals(PetOrder.LAY, pet.order());
        actions.onChat(player, "Toby come"); actions.onChat(player, "Toby follow");
        assertFalse(actions.roaming().tickAttention(pet, body, System.currentTimeMillis()));
        assertEquals(PetOrder.FOLLOW, pet.order()); assertTrue(body.isAware());
    }

    @Test void expiredComeAndReloadRestoreTheHeldPostureInsteadOfLeavingThePetFollowing() {
        pet.bindWord("come", Trick.COME); pet.progress(Trick.COME, 100); pet.bindWord("stay", Trick.STAY); pet.progress(Trick.STAY, 100);
        actions.onChat(player, "Toby stay"); body.teleport(player.getLocation().add(8, 0, 0));
        long now = System.currentTimeMillis(); actions.onChat(player, "Toby come");
        assertTrue(actions.roaming().tickAttention(pet, body, now + 31_000));
        assertEquals(PetOrder.STAY, pet.order()); assertFalse(body.isAware());
        actions.onChat(player, "Toby come"); actions.clearInteractions();
        assertEquals(PetOrder.STAY, pet.order()); assertFalse(body.isAware());
    }

    @Test void profileHasNoDedicatedFollowButton() {
        actions.menus().openCare(player, pet);
        var holder = (net.tfminecraft.companionpets.gui.MenuHolder) player.getOpenInventory().getTopInventory().getHolder();
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, holder.getInventory().getItem(39).getType());
        pet.order(PetOrder.SIT); actions.clickMenu(player, holder, 39, null, false, false, false);
        assertEquals(PetOrder.SIT, pet.order());
    }

    @SuppressWarnings("deprecation")
    private org.bukkit.event.player.AsyncPlayerChatEvent chatEvent(String text) {
        return new org.bukkit.event.player.AsyncPlayerChatEvent(false, player, text, new java.util.HashSet<>(org.bukkit.Bukkit.getOnlinePlayers()));
    }

    @SuppressWarnings("deprecation")
    public static class PublicChatProbe implements org.bukkit.event.Listener {
        private final PlayerMock observer;
        PublicChatProbe(PlayerMock observer) { this.observer = observer; }
        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
        public void onChat(org.bukkit.event.player.AsyncPlayerChatEvent event) { observer.sendMessage(event.getMessage()); }
    }

    private PlayerMock registerChatListeners() {
        var server = (org.mockbukkit.mockbukkit.ServerMock) org.bukkit.Bukkit.getServer();
        PlayerMock observer = server.addPlayer();
        var plugin = runtime.plugin();
        server.getPluginManager().registerEvents(new net.tfminecraft.companionpets.listen.PetListener(runtime, actions), plugin);
        server.getPluginManager().registerEvents(new PublicChatProbe(observer), plugin);
        return observer;
    }

    @Test void privateReleaseConfirmationIsConsumedBeforeRoleplayBroadcast() {
        assertTrue(runtime.store().load()); net.tfminecraft.companionpets.training.DefaultTricks.apply(runtime.config(), pet); runtime.store().add(pet);
        PlayerMock observer = registerChatListeners();
        runtime.sessions().release(player.getUniqueId(), new net.tfminecraft.companionpets.session.ReleasePrompt(pet.id(), System.currentTimeMillis() + 30_000));
        var event = chatEvent("yes"); org.bukkit.Bukkit.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled()); assertNull(observer.nextMessage());
        ((org.mockbukkit.mockbukkit.ServerMock) org.bukkit.Bukkit.getServer()).getScheduler().performTicks(1);
        assertNull(runtime.store().get(pet.id())); assertTrue(player.nextMessage().contains("is gone")); assertNull(observer.nextMessage());
    }

    @Test void eggAndRenameInputsStayPrivateAndOrdinaryChatStillBroadcasts() {
        PlayerMock observer = registerChatListeners();
        runtime.sessions().hatch(player.getUniqueId(), new net.tfminecraft.companionpets.session.HatchPrompt("wolf", System.currentTimeMillis() + 30_000));
        var hatch = chatEvent("female"); org.bukkit.Bukkit.getPluginManager().callEvent(hatch); assertTrue(hatch.isCancelled());
        runtime.sessions().clearHatch(player.getUniqueId());
        runtime.sessions().rename(player.getUniqueId(), new net.tfminecraft.companionpets.session.RenamePrompt(pet.id(), System.currentTimeMillis() + 30_000));
        var rename = chatEvent("Secret name"); org.bukkit.Bukkit.getPluginManager().callEvent(rename); assertTrue(rename.isCancelled());
        assertNull(observer.nextMessage());
        ((org.mockbukkit.mockbukkit.ServerMock) org.bukkit.Bukkit.getServer()).getScheduler().performTicks(1);
        assertEquals("Secret name", runtime.sessions().rename(player.getUniqueId()).name()); assertNull(observer.nextMessage());
        runtime.sessions().clearRename(player.getUniqueId());
        var normal = chatEvent("Good morning"); org.bukkit.Bukkit.getPluginManager().callEvent(normal); assertFalse(normal.isCancelled()); assertEquals("Good morning", observer.nextMessage());
    }

    @Test void queuedConfirmationCannotConfirmAReplacementPrompt() {
        registerChatListeners();
        runtime.sessions().release(player.getUniqueId(), new net.tfminecraft.companionpets.session.ReleasePrompt(pet.id(), System.currentTimeMillis() + 30_000));
        var event = chatEvent("yes"); org.bukkit.Bukkit.getPluginManager().callEvent(event); assertTrue(event.isCancelled());
        var replacement = new net.tfminecraft.companionpets.session.ReleasePrompt(pet.id(), System.currentTimeMillis() + 30_000);
        runtime.sessions().release(player.getUniqueId(), replacement);
        ((org.mockbukkit.mockbukkit.ServerMock) org.bukkit.Bukkit.getServer()).getScheduler().performTicks(1);
        assertSame(replacement, runtime.sessions().release(player.getUniqueId())); assertSame(pet, runtime.store().get(pet.id())); assertTrue(body.isValid());
    }

    private void confirmHatch() {
        actions.onChat(player, "female");
        actions.onChat(player, " Luna ");
        actions.onChat(player, "yes");
    }

    @Test void hatchConsumesOnlyAfterConfirmationAndCreatesConfiguredPet() {
        startHatch();
        confirmHatch();
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
        var hatched = runtime.store().all().stream().filter(p -> !p.id().equals(pet.id())).findFirst().orElseThrow();
        assertEquals("wolf", hatched.typeId());
        assertEquals("Luna", hatched.name());
        assertEquals(PetSex.FEMALE, hatched.sex());
        assertEquals(player.getUniqueId(), hatched.ownerId());
        assertNotNull(runtime.entity(hatched));
        assertNull(runtime.sessions().hatch(player.getUniqueId()));
    }

    @Test void cancellingHatchKeepsEggAndCreatesNothing() {
        startHatch();
        actions.onChat(player, "cancel");
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertEquals(1, runtime.store().all().size());
        assertNull(runtime.sessions().hatch(player.getUniqueId()));
    }

    @Test void removingEggBeforeConfirmationCreatesNothing() {
        startHatch();
        hold(Material.STONE, 2);
        confirmHatch();
        assertEquals(1, runtime.store().all().size());
        assertEquals(Material.STONE, player.getInventory().getItemInMainHand().getType());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertNull(runtime.sessions().hatch(player.getUniqueId()));
    }

    @Test void reachingPetLimitDuringPromptKeepsEggAtConfirmation() {
        startHatch();
        assertTrue(actions.spawnTestPet(player, "wolf", "Second"));
        confirmHatch();
        assertEquals(2, runtime.store().all().size());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertNull(runtime.sessions().hatch(player.getUniqueId()));
    }

    @Test void fullPetLimitDoesNotStartAHatchPrompt() {
        assertTrue(actions.spawnTestPet(player, "wolf", "Second"));
        var hand = hold(Material.WOLF_SPAWN_EGG, 2);
        actions.useWorld(player, hand, null, null, false, true);
        assertNull(runtime.sessions().hatch(player.getUniqueId()));
        assertEquals(2, hand.getAmount());
        assertEquals(2, runtime.store().all().size());
    }

    @Test void expiredHatchPromptKeepsEggAndCreatesNothing() {
        hold(Material.WOLF_SPAWN_EGG, 2);
        runtime.sessions().hatch(player.getUniqueId(), new net.tfminecraft.companionpets.session.HatchPrompt("wolf", 0));
        actions.onChat(player, "yes");
        assertNull(runtime.sessions().hatch(player.getUniqueId()));
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertEquals(1, runtime.store().all().size());
    }
}
