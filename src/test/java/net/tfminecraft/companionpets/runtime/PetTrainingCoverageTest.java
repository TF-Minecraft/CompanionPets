package net.tfminecraft.companionpets.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.gui.MenuHolder;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.session.TrainingSession;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.testutil.GoalServerMock;
import net.tfminecraft.companionpets.visual.PetVisual;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class PetTrainingCoverageTest {
    private GoalServerMock server;
    private WorldMock world;
    private PlayerMock owner;
    private WolfMock body;
    private Pet pet;
    private PetRuntime runtime;
    private PetActions actions;
    private PetTraining training;
    private YamlConfiguration yaml;
    private final List<String> animations = new ArrayList<>();
    private final List<Boolean> attention = new ArrayList<>();
    private long now;

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(new GoalServerMock());
        world = server.addSimpleWorld("training");
        owner = server.addPlayer();
        owner.teleport(new Location(world, 0, 64, 0));
        var plugin = MockBukkit.createMockPlugin();
        yaml = new YamlConfiguration();
        yaml.loadFromString("""
                items: {treats: [COD, SALMON]}
                pets:
                  wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG, default-tricks: []}
                """);
        var visual = new PetVisual() {
            @Override public void apply(Entity entity, PetTypeDef type) { }
            @Override public boolean play(Entity entity, PetTypeDef type, String action) {
                animations.add(action); return false;
            }
            @Override public void trainingAttention(Entity entity, PetTypeDef type, boolean focused) { attention.add(focused); }
        };
        var key = new NamespacedKey(plugin, "pet");
        var store = new PetStore(plugin);
        assertTrue(store.load());
        runtime = new PetRuntime(plugin, CompanionConfig.load(plugin, yaml), store, new Sessions(),
                new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        actions = new PetActions(runtime);
        // Keep the real PetActions executor and its actual posture changes; only access the clocked training entry point.
        var field = PetActions.class.getDeclaredField("training");
        field.setAccessible(true);
        training = (PetTraining) field.get(actions);
        pet = new Pet(UUID.randomUUID(), owner.getUniqueId(), "wolf", "Toby", PetSex.MALE);
        body = wolf(owner.getLocation().add(1, 0, 0));
        body.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.STRING, pet.id().toString());
        pet.stored(false);
        for (Need need : Need.values()) pet.need(need, 80);
        pet.bond(0);
        store.add(pet);
        runtime.remember(pet, body);
        now = System.currentTimeMillis() + 10_000;
        hold(Material.COD, 8);
    }

    private WolfMock wolf(Location location) {
        var wolf = new WolfMock(server, UUID.randomUUID()) {
            @Override public boolean isInWater() { return false; }
            @Override public float getBodyYaw() { return 0; }
            @Override public com.destroystokyo.paper.entity.Pathfinder getPathfinder() {
                return (com.destroystokyo.paper.entity.Pathfinder) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{com.destroystokyo.paper.entity.Pathfinder.class}, (proxy, method, args) -> switch (method.getName()) {
                            case "setCanFloat" -> { assertEquals(true, args[0]); yield null; }
                            case "stopPathfinding" -> null;
                            case "hasPath" -> false;
                            default -> throw new AssertionError("Unexpected navigation: " + method.getName());
                        });
            }
        };
        server.registerEntity(wolf);
        wolf.teleport(location);
        return wolf;
    }

    @AfterEach void cleanup() {
        if (actions != null) actions.holograms().clear();
        if (runtime != null) runtime.store().close();
        if (owner != null) PetFx.clearPlayer(owner.getUniqueId());
        MockBukkit.unmock();
    }

    private void configure(String key, Object value) {
        yaml.set(key, value);
        runtime.config(CompanionConfig.load(runtime.plugin(), yaml));
    }
    private ItemStack hold(Material material, int count) {
        owner.getInventory().setItemInMainHand(new ItemStack(material, count));
        return owner.getInventory().getItemInMainHand();
    }
    private TrainingSession start(double progress) {
        pet.bindWord("sit", Trick.SIT);
        pet.progress(Trick.SIT, progress);
        assertTrue(actions.useOnPet(owner, body, owner.getInventory().getItemInMainHand()));
        assertNotNull(session());
        clearMessages();
        return session();
    }
    private TrainingSession session() { return runtime.sessions().training(owner.getUniqueId()); }
    private void say(String word) { training.handleTrainingChat(owner, word, now, pet, body); }
    private void attempt(boolean positiveRoll) {
        runtime.random().setSeed(positiveRoll ? 0 : 4096);
        say("sit");
    }
    private void reward() { assertTrue(actions.useOnPet(owner, body, owner.getInventory().getItemInMainHand())); }
    private void watch(long at) throws Exception {
        var method = PetTicker.class.getDeclaredMethod("watchTraining", long.class);
        method.setAccessible(true);
        method.invoke(new PetTicker(runtime, actions), at);
    }
    private String bars() {
        StringBuilder result = new StringBuilder();
        Component bar;
        while ((bar = owner.nextActionBar()) != null) result.append(PlainTextComponentSerializer.plainText().serialize(bar)).append('\n');
        return result.toString();
    }
    private String messages() {
        StringBuilder result = new StringBuilder();
        String message;
        while ((message = owner.nextMessage()) != null) result.append(message).append('\n');
        return result.toString();
    }
    private void clearMessages() { bars(); messages(); }
    private String hologram() {
        return world.getEntities().stream().filter(e -> e instanceof ArmorStand && e.isValid())
                .map(e -> PlainTextComponentSerializer.plainText().serialize(e.customName())).findFirst().orElse("");
    }

    @Test void unknownWordIsBoundThenPracticeSpendsCareAndOnlyTheTreatTeaches() {
        start(0);
        say("  Down!  ");
        assertEquals("down", session().pendingWord());
        assertEquals("down", ((MenuHolder) owner.getOpenInventory().getTopInventory().getHolder()).word());
        say("another word");
        assertEquals("down", session().pendingWord(), "Finish choosing the first word before accepting another");
        training.bindTrick(owner, pet, "down", Trick.SIT);
        assertEquals(Trick.SIT, pet.trickFor("down"));
        assertNull(session().pendingWord());
        runtime.random().setSeed(0);
        say("down");
        assertEquals(1, session().attempts());
        assertEquals("down", session().lastWord());
        assertFalse(session().rewardSuccess());
        assertEquals(now + 3_000, session().rewardUntil());
        assertEquals(72, pet.need(Need.ENERGY));
        assertEquals(75, pet.need(Need.HUNGER));
        assertEquals(76, pet.need(Need.MOOD));
        assertEquals(0, pet.progress(Trick.SIT));
        assertTrue(pet.forcedSitUntilMillis() > 0, "A partial sit has an actual temporary posture");
        assertEquals(8, owner.getInventory().getItemInMainHand().getAmount());
        assertTrue(hologram().contains("Almost got it"));
        reward();
        assertEquals(2, pet.progress(Trick.SIT));
        assertEquals(78, pet.need(Need.HUNGER));
        assertEquals(7, owner.getInventory().getItemInMainHand().getAmount());
        assertNull(session().rewardTrick());
        assertTrue(animations.contains("EAT"));
        assertTrue(pet.nextCryAtMillis() > System.currentTimeMillis());
        assertTrue(hologram().contains("A little closer"));
        reward();
        assertEquals(2, pet.progress(Trick.SIT), "A repeated click without a new attempt earns no second reward");
        assertEquals(7, owner.getInventory().getItemInMainHand().getAmount());
        assertTrue(runtime.store().save());
        var restored = new PetStore(runtime.plugin());
        assertTrue(restored.load());
        assertEquals(2, restored.get(pet.id()).progress(Trick.SIT));
        assertEquals(Trick.SIT, restored.get(pet.id()).trickFor("down"));
        assertEquals(78, restored.get(pet.id()).need(Need.HUNGER));
        restored.close();
    }

    @Test void failedAttemptTiltsTheHeadWithCooldownAndStillAcceptsEncouragement() {
        start(0);
        attempt(false);
        assertFalse(session().rewardSuccess());
        assertTrue(body.isInterested());
        assertTrue(hologram().contains("Doesn't get it"));
        long tilts = animations.stream().filter("HEAD_TILT"::equals).count();
        now += 500; attempt(false);
        assertEquals(tilts, animations.stream().filter("HEAD_TILT"::equals).count());
        server.getScheduler().performTicks(24);
        assertFalse(body.isInterested());
        now += 3_000; attempt(false);
        assertEquals(tilts + 1, animations.stream().filter("HEAD_TILT"::equals).count());
        reward();
        assertEquals(2, pet.progress(Trick.SIT));
    }

    @Test void successfulAttemptPerformsThePostureAndEarnsTheLargerReward() {
        start(40);
        attempt(true);
        assertTrue(session().rewardSuccess());
        assertEquals(PetOrder.SIT, pet.order());
        assertTrue(body.isSitting());
        assertTrue(hologram().contains("Nailed it"));
        reward();
        assertEquals(62.28, pet.progress(Trick.SIT), 0.0001);
        assertTrue(hologram().contains("Good job"));
    }

    @Test void reachingSometimesThresholdExplainsThatRewardedSuccessesNowTeachFaster() {
        start(39);
        attempt(false);
        reward();
        assertEquals(41, pet.progress(Trick.SIT));
        assertTrue(messages().contains("catching on"));
        assertTrue(hologram().contains("Catching on"));
    }

    @Test void learnedTrickAndNewAliasWorkWithoutAnyFurtherPracticeCost() {
        start(79);
        attempt(true); reward();
        assertEquals(100, pet.progress(Trick.SIT));
        assertTrue(messages().contains("has learned Sit"));
        assertTrue(hologram().contains("Learned Sit"));
        double energy = pet.need(Need.ENERGY);
        say("sit");
        assertEquals(1, session().attempts());
        assertNull(session().rewardTrick());
        assertEquals(energy, pet.need(Need.ENERGY));
        training.bindTrick(owner, pet, "settle", Trick.SIT);
        assertEquals(Trick.SIT, pet.trickFor("settle"));
        assertTrue(messages().contains("already knows this trick"));
        training.endTraining(owner, pet, "finished");
        pet.order(PetOrder.FOLLOW); body.setSitting(false);
        say("settle");
        assertEquals(PetOrder.SIT, pet.order());
        assertTrue(body.isSitting());
        assertEquals(7, owner.getInventory().getItemInMainHand().getAmount());
    }

    @Test void lastAttemptAllowsOneRewardBeforeRestAndStopsFurtherCommands() {
        configure("training.attempts-before-bored", 1);
        var original = start(40);
        attempt(true);
        assertTrue(original.bored());
        long restUntil = runtime.sessions().restUntil(pet.id());
        assertEquals(now + 90_000, restUntil);
        say("sit");
        assertEquals(1, original.attempts());
        reward();
        assertNull(session());
        assertEquals(62.28, pet.progress(Trick.SIT), 0.0001);
        assertEquals(restUntil, runtime.sessions().restUntil(pet.id()));
        assertTrue(messages().contains("needs a break"));
        assertFalse(attention.getLast());
        training.beginTraining(owner, pet, body);
        assertNull(session());
        assertTrue(hologram().contains("Resting"));
    }

    @Test void expiredSuccessLosesItsRewardButKeepsTheSessionAndInventory() throws Exception {
        var original = start(40);
        attempt(true); clearMessages();
        watch(original.rewardUntil() + 1);
        assertSame(original, session());
        assertNull(original.rewardTrick());
        assertEquals(0, original.rewardUntil());
        assertEquals(40, pet.progress(Trick.SIT));
        assertEquals(8, owner.getInventory().getItemInMainHand().getAmount());
        assertTrue(bars().contains("missed the moment"));
    }

    @Test void expiredFailedAttemptIsQuietAndExpiredLastAttemptEndsForRest() throws Exception {
        start(0); attempt(false); clearMessages();
        watch(session().rewardUntil() + 1);
        assertNull(session().rewardTrick());
        assertTrue(bars().isBlank());
        configure("training.attempts-before-bored", 2);
        attempt(false);
        assertTrue(session().bored());
        watch(session().rewardUntil() + 1);
        assertNull(session());
        assertEquals(0, pet.progress(Trick.SIT));
        assertTrue(runtime.sessions().resting(pet.id(), now));
    }

    @Test void careDeterioratingDuringTrainingAbortsBeforeSpendingOrTeaching() {
        for (String reason : List.of("hunger", "energy", "sick", "weakened")) {
            pet.illness(Illness.NONE);
            pet.need(Need.HUNGER, 80); pet.need(Need.ENERGY, 80);
            start(20);
            switch (reason) {
                case "hunger" -> pet.need(Need.HUNGER, 24);
                case "energy" -> pet.need(Need.ENERGY, 24);
                case "sick" -> pet.illness(Illness.SICK);
                case "weakened" -> pet.illness(Illness.WEAKENED);
            }
            double hunger = pet.need(Need.HUNGER), energy = pet.need(Need.ENERGY);
            var old = session(); say("sit");
            assertNull(session(), reason);
            assertEquals(0, old.attempts());
            assertEquals(hunger, pet.need(Need.HUNGER));
            assertEquals(energy, pet.need(Need.ENERGY));
            assertEquals(20, pet.progress(Trick.SIT));
            assertEquals(8, owner.getInventory().getItemInMainHand().getAmount());
            String explanation = messages();
            assertTrue(explanation.contains(reason.equals("hunger") ? "hungry" : reason.equals("energy") ? "tired" : "unwell"));
        }
    }

    @Test void anotherAcceptedTreatCanRewardAndCreativeModeDoesNotConsumeIt() {
        start(40); attempt(true);
        hold(Material.SALMON, 3);
        owner.setGameMode(GameMode.CREATIVE);
        reward();
        assertEquals(3, owner.getInventory().getItemInMainHand().getAmount());
        assertEquals(62.28, pet.progress(Trick.SIT), 0.0001);
        assertNull(session().rewardTrick());
    }

    @Test void rewardWithoutAnItemPreservesTheAttemptUntilAnItemCanBeConsumed() {
        start(40); attempt(true);
        owner.getInventory().setItemInMainHand(null);
        // Exercise the reward boundary's own consumption check with an actual empty inventory.
        training.reward(owner, pet, session(), owner.getInventory().getItemInMainHand());
        assertEquals(Trick.SIT, session().rewardTrick());
        assertEquals(40, pet.progress(Trick.SIT));
        assertEquals(75, pet.need(Need.HUNGER));
        hold(Material.COD, 2); reward();
        assertEquals(62.28, pet.progress(Trick.SIT), 0.0001);
        assertEquals(1, owner.getInventory().getItemInMainHand().getAmount());
    }

    @Test void removingAnAllowedTrickDuringItsRewardWindowPreservesTheTreatAndLearning() {
        start(40); attempt(true);
        configure("pets.wolf.tricks", List.of("follow"));
        reward();
        assertNull(session().rewardTrick());
        assertEquals(40, pet.progress(Trick.SIT));
        assertEquals(8, owner.getInventory().getItemInMainHand().getAmount());
        assertTrue(bars().contains("not available"));
        clearMessages(); say("sit");
        assertEquals(1, session().attempts());
        assertEquals(40, pet.progress(Trick.SIT));
        training.bindTrick(owner, pet, "new sit", Trick.SIT);
        assertNull(pet.trickFor("new sit"));
    }

    @Test void untrainedCommandsExplainStartingAndEachStageOfRestWithoutTeaching() {
        pet.bindWord("sit", Trick.SIT);
        say("unrecognized"); assertTrue(bars().isBlank());
        say("sit"); assertTrue(bars().contains("isn't training"));
        runtime.sessions().rest(pet.id(), now + 90_000);
        say("sit"); assertTrue(bars().contains("good rest"));
        now += 50_000;
        say("sit"); assertTrue(bars().contains("still resting"));
        now += 30_000;
        say("sit"); assertTrue(bars().contains("almost ready"));
        assertEquals(0, pet.progress(Trick.SIT));
        assertNull(session());
    }

    @Test void onlyAnAvailableOwnedActivePetCanStartOrReceiveTrainingWords() {
        var stranger = server.addPlayer();
        training.beginTraining(stranger, pet, body);
        assertNull(runtime.sessions().training(stranger.getUniqueId()));
        training.handleTrainingChat(stranger, "sit", now, pet, body);
        training.bindTrick(stranger, pet, "stolen", Trick.SIT);
        assertNull(pet.trickFor("stolen"));
        pet.stored(true);
        training.beginTraining(owner, pet, body); say("sit");
        assertNull(session());
        pet.stored(false);
        configure("pets.wolf.tricks", List.of());
        training.beginTraining(owner, pet, body);
        assertNull(session());
        assertTrue(bars().contains("no available tricks"));
        configure("pets.wolf", null);
        training.beginTraining(owner, pet, body);
        assertNull(session());
        assertEquals("a treat", training.treatName(pet));
    }

    @Test void switchingPetsEndsThePreviousSessionAndReopeningRetainsCurrentProgress() {
        var first = start(20);
        attempt(false);
        training.beginTraining(owner, pet, body);
        assertSame(first, session());
        var second = new Pet(UUID.randomUUID(), owner.getUniqueId(), "wolf", "Milo", PetSex.FEMALE);
        second.stored(false);
        runtime.store().add(second);
        var secondBody = wolf(owner.getLocation().add(2, 0, 0));
        runtime.remember(second, secondBody);
        training.beginTraining(owner, second, secondBody);
        assertNotSame(first, session());
        assertEquals(second.id(), session().petId());
        assertEquals(20, pet.progress(Trick.SIT));
        assertTrue(messages().contains("started training another pet"));
    }

    @Test void unavailableWordsAreOmittedFromPracticeHintsAndEmptyChatSpendsNothing() {
        pet.bindWord("obsolete", Trick.PAW);
        configure("pets.wolf.tricks", List.of("sit"));
        start(20);
        training.endTraining(owner, pet, "test break"); clearMessages();
        training.beginTraining(owner, pet, body);
        String hints = messages();
        assertTrue(hints.contains("Still practicing: “sit”"));
        assertFalse(hints.contains("obsolete"));
        say("   ");
        assertEquals(0, session().attempts());
    }

    @Test void sleepingPetDoesNotSpendEffortOnAnActionThatCannotWakeIt() {
        start(0);
        pet.bindWord("speak", Trick.SPEAK);
        pet.activity(Activity.SLEEPING);
        say("speak");
        assertEquals(Activity.SLEEPING, pet.activity());
        assertEquals(0, session().attempts());
        assertNull(session().rewardTrick());
        assertEquals(80, pet.need(Need.ENERGY));
        assertTrue(bars().contains("is resting"));
    }

    @Test void disconnectEndsTrainingWithoutSendingMoreMessagesAndKeepsLearning() {
        start(40); clearMessages();
        owner.disconnect();
        training.endTraining(owner, pet, "disconnected");
        assertNull(session());
        assertFalse(attention.getLast());
        assertEquals(40, pet.progress(Trick.SIT));
        assertTrue(messages().isBlank());
    }
}
