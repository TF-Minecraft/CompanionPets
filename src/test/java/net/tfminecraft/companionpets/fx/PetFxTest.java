package net.tfminecraft.companionpets.fx;

import static org.junit.jupiter.api.Assertions.*;

import com.destroystokyo.paper.entity.ai.GoalKey;
import io.papermc.paper.entity.LookAnchor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.item.ItemRef;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.testutil.GoalServerMock;
import net.tfminecraft.companionpets.visual.PetVisual;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.CowMock;
import org.mockbukkit.mockbukkit.entity.FoxMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class PetFxTest {
    private GoalServerMock server;
    private WorldMock world;
    private FeedbackPlayer player;
    private WolfMock wolf;
    private final List<SoundOutput> sounds = new ArrayList<>();
    private final List<ParticleOutput> particles = new ArrayList<>();
    private record SoundOutput(Location location, Sound sound, float volume, float pitch) { }
    private record ParticleOutput(Particle particle, Location location, int count,
            double x, double y, double z, double extra, Object data) { }

    @BeforeEach void setup() {
        server = MockBukkit.mock(new GoalServerMock());
        world = new WorldMock() {
            @Override public void playSound(Location at, Sound sound, float volume, float pitch) {
                sounds.add(new SoundOutput(at.clone(), sound, volume, pitch));
            }
            @Override public void spawnParticle(Particle particle, Location at, int count,
                    double x, double y, double z, double extra) {
                particles.add(new ParticleOutput(particle, at.clone(), count, x, y, z, extra, null));
            }
            @Override public <T> void spawnParticle(Particle particle, Location at, int count,
                    double x, double y, double z, double extra, T data) {
                particles.add(new ParticleOutput(particle, at.clone(), count, x, y, z, extra,
                        data instanceof ItemStack item ? item.clone() : data));
            }
        };
        server.addWorld(world);
        player = new FeedbackPlayer();
        server.addPlayer(player);
        player.teleport(new Location(world, 5, 64, 6));
        wolf = new WolfMock(server, UUID.randomUUID());
        wolf.teleport(new Location(world, 1, 64, 2));
    }

    @AfterEach void cleanup() {
        if (player != null) PetFx.clearPlayer(player.getUniqueId());
        MockBukkit.unmock();
    }

    @Test void chatTipsAndCuesKeepTheirFormattingAndTargetOnlyThePlayer() {
        PetFx.tell(player, "Luna needs food");
        PetFx.tip(player, "Use a treat");
        assertEquals(Component.text("✦ ", NamedTextColor.GOLD).append(Component.text("Luna needs food", NamedTextColor.YELLOW)),
                player.nextComponentMessage());
        assertEquals(Component.text("   Tip: ", NamedTextColor.AQUA).append(Component.text("Use a treat", NamedTextColor.GRAY)),
                player.nextComponentMessage());
        assertNull(player.nextComponentMessage());
        PetFx.cue(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.25f);
        assertEquals(List.of(new SoundOutput(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.25f)), player.cues);
        assertTrue(sounds.isEmpty(), "Private cues must not become world sounds");
    }

    @Test void actionBarShowsNewMessagesSuppressesImmediateDuplicatesAndClearsOnDeparture() {
        Component message = Component.text("Luna is listening", NamedTextColor.YELLOW);
        PetFx.bar(player, message);
        PetFx.bar(player, message);
        assertEquals(List.of(message), player.bars);
        PetFx.bar(player, "Reward Luna now");
        assertEquals(List.of(message, Component.text("Reward Luna now")), player.bars);
        PetFx.clearPlayer(player.getUniqueId());
        PetFx.bar(player, "Reward Luna now");
        assertEquals(3, player.bars.size(), "Reconnect/departure cleanup permits the same notice again");
        var second = new FeedbackPlayer();
        server.addPlayer(second);
        try {
            PetFx.bar(second, "Reward Luna now");
            assertEquals(List.of(Component.text("Reward Luna now")), second.bars, "Suppression belongs to one player");
        } finally { PetFx.clearPlayer(second.getUniqueId()); }
    }

    @Test void heartsAndGenericParticlesKeepTheRequestedShapeAboveAnUnchangedBody() {
        Location original = wolf.getLocation();
        PetFx.hearts(wolf, 7);
        PetFx.particle(wolf, Particle.HAPPY_VILLAGER, 2);
        assertEquals(List.of(
                new ParticleOutput(Particle.HEART, original.clone().add(0, 1, 0), 7, 0.3, 0.3, 0.3, 0, null),
                new ParticleOutput(Particle.HAPPY_VILLAGER, original.clone().add(0, 0.8, 0), 2, 0.25, 0.3, 0.25, 0, null)), particles);
        assertEquals(original, wolf.getLocation());
    }

    @Test void hungerParticlesUseConfiguredFoodOrAnAvailableFallback() {
        PetFx.need(wolf, Need.HUNGER, ItemRef.vanilla(Material.COD));
        PetFx.need(wolf, Need.HUNGER, null);
        PetFx.need(wolf, Need.HUNGER, ItemRef.parse("itemsadder:missing:food"));
        assertEquals(3, particles.size());
        for (int index = 0; index < particles.size(); index++) {
            var output = particles.get(index);
            assertEquals(Particle.ITEM, output.particle());
            assertEquals(wolf.getLocation().add(0, 0.9, 0), output.location());
            assertEquals(4, output.count());
            assertEquals(0.2, output.x()); assertEquals(0.2, output.y()); assertEquals(0.2, output.z());
            assertEquals(0.02, output.extra());
            assertEquals(new ItemStack(index == 0 ? Material.COD : Material.COOKED_BEEF), output.data());
        }
    }

    @Test void eachRemainingNeedHasItsOwnSignalAndMissingNeedDoesNothing() {
        PetFx.need(wolf, null, null);
        assertTrue(particles.isEmpty());
        for (Need need : List.of(Need.MOOD, Need.ENERGY, Need.CLEANLINESS, Need.HEALTH)) PetFx.need(wolf, need, null);
        assertEquals(List.of(Particle.SPLASH, Particle.CLOUD, Particle.DUST_PLUME, Particle.DAMAGE_INDICATOR),
                particles.stream().map(ParticleOutput::particle).toList());
        assertEquals(List.of(3, 3, 4, 3), particles.stream().map(ParticleOutput::count).toList());
    }

    @Test void speciesSoundsUseTheAdvertisedAmbientHurtAndEmotionCues() {
        record Voice(EntityType type, Sound ambient, Sound hurt, Sound quiet, Sound loud, Sound sad) { }
        for (var voice : List.of(
                new Voice(EntityType.WOLF, Sound.ENTITY_WOLF_AMBIENT, Sound.ENTITY_WOLF_HURT, Sound.ENTITY_WOLF_PANT, Sound.ENTITY_WOLF_AMBIENT, Sound.ENTITY_WOLF_WHINE),
                new Voice(EntityType.CAT, Sound.ENTITY_CAT_AMBIENT, Sound.ENTITY_CAT_HURT, Sound.ENTITY_CAT_PURR, Sound.ENTITY_CAT_PURREOW, Sound.ENTITY_CAT_BEG_FOR_FOOD),
                new Voice(EntityType.FOX, Sound.ENTITY_FOX_AMBIENT, Sound.ENTITY_FOX_HURT, Sound.ENTITY_FOX_SNIFF, Sound.ENTITY_FOX_AMBIENT, Sound.ENTITY_FOX_SNIFF),
                new Voice(EntityType.PARROT, Sound.ENTITY_PARROT_AMBIENT, Sound.ENTITY_PARROT_HURT, Sound.ENTITY_PARROT_AMBIENT, Sound.ENTITY_PARROT_AMBIENT, Sound.ENTITY_PARROT_AMBIENT),
                new Voice(EntityType.FROG, Sound.ENTITY_FROG_AMBIENT, Sound.ENTITY_FROG_HURT, Sound.ENTITY_FROG_AMBIENT, Sound.ENTITY_FROG_AMBIENT, Sound.ENTITY_FROG_AMBIENT))) {
            Entity entity = world.spawnEntity(wolf.getLocation(), voice.type());
            sounds.clear();
            assertEquals(voice.ambient(), PetFx.ambientSound(voice.type()));
            assertEquals(voice.hurt(), PetFx.hurtSound(voice.type()));
            PetFx.ambient(entity); PetFx.hurt(entity); PetFx.happy(entity, false); PetFx.happy(entity, true); PetFx.sad(entity); PetFx.eat(entity);
            assertEquals(List.of(
                    new SoundOutput(entity.getLocation(), voice.ambient(), 0.8f, 1f),
                    new SoundOutput(entity.getLocation(), voice.hurt(), 1f, 1f),
                    new SoundOutput(entity.getLocation(), voice.quiet(), 0.9f, 1.1f),
                    new SoundOutput(entity.getLocation(), voice.loud(), 0.9f, 1.1f),
                    new SoundOutput(entity.getLocation(), voice.sad(), 0.8f, 0.9f),
                    new SoundOutput(entity.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.8f, 1f)), sounds, voice.type().name());
        }
    }

    @Test void otherMobsUseTheirNativeSoundsAndSilentEntitiesStaySilent() {
        var cow = new CowMock(server, UUID.randomUUID()) {
            @Override public Sound getAmbientSound() { return Sound.ENTITY_COW_AMBIENT; }
            @Override public Sound getHurtSound() { return Sound.ENTITY_COW_HURT; }
        };
        cow.teleport(wolf.getLocation());
        assertNull(PetFx.ambientSound(EntityType.COW));
        assertNull(PetFx.hurtSound(EntityType.COW));
        assertEquals(Sound.ENTITY_COW_AMBIENT, PetFx.ambientSound(cow));
        PetFx.ambient(cow); PetFx.hurt(cow); PetFx.happy(cow, true); PetFx.sad(cow);
        assertEquals(List.of(Sound.ENTITY_COW_AMBIENT, Sound.ENTITY_COW_HURT, Sound.ENTITY_COW_AMBIENT, Sound.ENTITY_COW_AMBIENT),
                sounds.stream().map(SoundOutput::sound).toList());
        sounds.clear();
        Entity item = world.dropItem(wolf.getLocation(), new ItemStack(Material.STICK));
        PetFx.ambient(item); PetFx.hurt(item); PetFx.happy(item, false); PetFx.sad(item);
        assertTrue(sounds.isEmpty());
        assertNull(PetFx.ambientSound(item));
    }

    @Test void sittingAndLyingAllowLandPosturesButReleaseThemForSwimming() {
        wolf.setInWater(false);
        PetFx.sit(wolf, true); assertTrue(wolf.isSitting());
        wolf.setPose(Pose.SLEEPING);
        PetFx.lie(wolf, true);
        assertEquals(Pose.STANDING, wolf.getPose()); assertTrue(wolf.isSitting());
        wolf.setInWater(true);
        PetFx.lie(wolf, true); assertFalse(wolf.isSitting());
        PetFx.sit(wolf, true); assertFalse(wolf.isSitting());
        wolf.setInWater(false);
        PetFx.sit(wolf, true); assertTrue(wolf.isSitting());
        PetFx.lie(wolf, false); assertFalse(wolf.isSitting());
    }

    @Test void foxPosturesWakeTheFoxAndNeverKeepItSittingInWater() {
        var fox = new FoxMock(server, UUID.randomUUID());
        fox.teleport(wolf.getLocation());
        fox.setSleeping(true);
        PetFx.lie(fox, true);
        assertFalse(fox.isSleeping()); assertTrue(fox.isSitting());
        fox.setSleeping(true);
        PetFx.sit(fox, false);
        assertFalse(fox.isSleeping()); assertFalse(fox.isSitting());
        fox.setInWater(true); fox.setSleeping(true);
        PetFx.lie(fox, true);
        assertFalse(fox.isSleeping()); assertFalse(fox.isSitting());
    }

    @Test void jumpingChangesOnlyTheVerticalVelocity() {
        wolf.setVelocity(new Vector(0.12, -0.3, -0.08));
        PetFx.jump(wolf, true);
        assertEquals(new Vector(0.12, 0.28, -0.08), wolf.getVelocity());
        PetFx.jump(wolf, false);
        assertEquals(new Vector(0.12, 0.48, -0.08), wolf.getVelocity());
    }

    @Test void mobLookingUsesOneRealGoalAndReleasesHeldVisualAttention() {
        var body = new PetLookGoalTest.WatchingWolf(server);
        body.teleport(wolf.getLocation());
        PetFx.look(body, player);
        var key = GoalKey.of(Mob.class, new NamespacedKey("companionpets", "look"));
        var goal = server.getMobGoals().getGoal(body, key);
        assertNotNull(goal);
        goal.tick(); assertEquals(player.getEyeLocation(), body.lookedAt);
        Location point = body.getLocation().add(3, 2, 4);
        PetFx.look(body, point);
        assertSame(goal, server.getMobGoals().getGoal(body, key));
        goal.tick(); assertEquals(point, body.lookedAt);
        int[] released = {0};
        PetFx.holdLooking(body, new PetVisual() {
            @Override public void apply(Entity entity, PetTypeDef type) { }
            @Override public void releaseHeadLook(Entity entity) { assertSame(body, entity); released[0]++; }
        });
        PetFx.releaseLooking(body);
        assertEquals(1, released[0]); assertFalse(goal.shouldActivate());
        PetFx.look(body, player); assertTrue(goal.shouldActivate());
        PetFx.stopLooking(body); assertFalse(goal.shouldActivate());
        PetFx.stopLooking(body);
        assertSame(goal, server.getMobGoals().getGoal(body, key), "The bounded registered goal remains safely inactive");
        PetFx.stopLooking(player);
    }

    @Test void nonMobLookTargetsUseEyeAnchorsAndIgnoreMissingOrOtherWorldTargets() {
        PetFx.look(player, wolf);
        assertEquals(List.of(wolf.getEyeLocation()), player.looks);
        assertEquals(List.of(LookAnchor.EYES), player.anchors);
        Entity item = world.dropItem(wolf.getLocation(), new ItemStack(Material.BONE));
        PetFx.look(player, item);
        assertEquals(item.getLocation(), player.looks.getLast());
        PetFx.look(player, wolf.getLocation());
        assertEquals(wolf.getLocation(), player.looks.getLast());
        int calls = player.looks.size();
        PetFx.look(player, (Entity) null); PetFx.look(player, (Location) null);
        PetFx.look(player, new Location(server.addSimpleWorld("elsewhere"), 0, 64, 0));
        PetFx.look(item, wolf);
        assertEquals(calls, player.looks.size());
        var away = server.addPlayer();
        away.teleport(new Location(server.getWorld("elsewhere"), 0, 64, 0));
        PetFx.look(wolf, away); PetFx.look(wolf, away.getLocation());
        var key = GoalKey.of(Mob.class, new NamespacedKey("companionpets", "look"));
        assertNull(server.getMobGoals().getGoal(wolf, key));
    }

    private class FeedbackPlayer extends PlayerMock {
        final List<Component> bars = new ArrayList<>();
        final List<SoundOutput> cues = new ArrayList<>();
        final List<Location> looks = new ArrayList<>();
        final List<LookAnchor> anchors = new ArrayList<>();
        FeedbackPlayer() { super(PetFxTest.this.server, "Feedback" + UUID.randomUUID().toString().substring(0, 6)); }
        @Override public void sendActionBar(Component text) { bars.add(text); }
        @Override public void playSound(Location at, Sound sound, float volume, float pitch) {
            cues.add(new SoundOutput(at.clone(), sound, volume, pitch));
        }
        @Override public void lookAt(double x, double y, double z, LookAnchor anchor) {
            looks.add(new Location(getWorld(), x, y, z)); anchors.add(anchor);
        }
    }
}
