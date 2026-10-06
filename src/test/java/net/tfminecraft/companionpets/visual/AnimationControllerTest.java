package net.tfminecraft.companionpets.visual;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.tfminecraft.companionpets.config.PetAppearance.Clip;

class AnimationControllerTest {
    @Test void shakingNeverStopsNavigationAndWalkingInterruptsTheGesture() {
        Player player = new Player();
        AnimationController controller = new AnimationController(player, clips());
        controller.update(PetAnimation.IDLE);
        assertTrue(controller.play(PetAnimation.SHAKE));
        assertFalse(controller.holdsMovement());
        controller.update(PetAnimation.WALK);
        assertFalse(player.active.contains("shake"));
        assertEquals("play:walk:true", player.events.getLast());
    }
    @Test
    void currentModelsUseIdleInAirWalkInWaterAndSleepWhenLyingDown() {
        Player player = new Player();
        Map<PetAnimation, Clip> clips = new EnumMap<>(PetAnimation.class);
        for (PetAnimation animation : List.of(PetAnimation.IDLE, PetAnimation.WALK, PetAnimation.SIT, PetAnimation.SLEEP, PetAnimation.PAW))
            clips.put(animation, new Clip(animation.name().toLowerCase(java.util.Locale.ROOT), 1, 0.15));
        clips.put(PetAnimation.LIE, clips.get(PetAnimation.SLEEP));
        AnimationController controller = new AnimationController(player, clips);
        controller.update(PetAnimation.JUMP);
        controller.update(PetAnimation.FALL);
        assertEquals(List.of("play:idle:true"), player.events);
        controller.update(PetAnimation.SWIM);
        assertEquals("play:walk:true", player.events.getLast());
        controller.update(PetAnimation.LIE);
        controller.update(PetAnimation.SLEEP);
        assertEquals("play:sleep:true", player.events.getLast());
        assertEquals(1, player.events.stream().filter("play:sleep:true"::equals).count());
        controller.update(PetAnimation.SIT);
        assertEquals("play:sit:true", player.events.getLast());
        assertTrue(controller.play(PetAnimation.PAW));
        assertEquals("play:paw:false", player.events.getLast());
    }

    @Test
    void repeatedUpdatesDoNotRestartLoopsAndWakeStopsSleep() {
        Player player = new Player();
        AnimationController controller = new AnimationController(player, clips());
        controller.update(PetAnimation.SLEEP);
        controller.update(PetAnimation.SLEEP);
        assertEquals(List.of("play:sleep:true"), player.events);
        controller.update(PetAnimation.WALK);
        assertEquals(List.of("play:sleep:true", "stop:sleep", "play:walk:true"), player.events);
    }

    @Test
    void gestureFinishesIntoLatestPoseAndReleasesNavigation() {
        Player player = new Player();
        AnimationController controller = new AnimationController(player, clips());
        controller.update(PetAnimation.IDLE);
        assertTrue(controller.play(PetAnimation.PAW));
        assertTrue(controller.holdsMovement());
        controller.update(PetAnimation.WALK);
        assertEquals("play:paw:false", player.events.getLast());
        player.active.remove("paw");
        controller.update(PetAnimation.WALK);
        assertEquals("play:walk:true", player.events.getLast());
        assertFalse(controller.holdsMovement());
    }

    @Test
    void sleepAndNewCommandsInterruptGestures() {
        Player player = new Player();
        AnimationController controller = new AnimationController(player, clips());
        controller.play(PetAnimation.PAW);
        controller.update(PetAnimation.SLEEP);
        assertEquals(List.of("play:paw:false", "stop:paw", "play:sleep:true"), player.events);
        controller.play(PetAnimation.PAW);
        controller.cancelAction();
        controller.update(PetAnimation.IDLE);
        assertEquals("play:idle:true", player.events.getLast());
        assertFalse(controller.holdsMovement());
    }

    @Test
    void missingExtrasFallBackWithoutInterruptingValidAnimation() {
        Player player = new Player();
        Map<PetAnimation, Clip> clips = clips();
        clips.remove(PetAnimation.SLEEP);
        clips.remove(PetAnimation.LIE);
        clips.remove(PetAnimation.PAW);
        AnimationController controller = new AnimationController(player, clips);
        controller.update(PetAnimation.SLEEP);
        assertEquals(List.of("play:sit:true"), player.events);
        assertFalse(controller.play(PetAnimation.PAW));
        assertEquals(List.of("play:sit:true"), player.events);
    }

    private static Map<PetAnimation, Clip> clips() {
        Map<PetAnimation, Clip> clips = new EnumMap<>(PetAnimation.class);
        for (PetAnimation animation : PetAnimation.values()) {
            clips.put(animation, new Clip(animation.name().toLowerCase(java.util.Locale.ROOT), 1, 0.15));
        }
        return clips;
    }

    @Test
    void bellyTransitionsHoldStaticPoseAndWaitAfterLastRub() {
        Player player = new Player();
        player.lengths.put("belly_up", 0.0);
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong();
        AnimationController controller = new AnimationController(player, clips(), now::get);
        controller.update(PetAnimation.SIT);
        assertTrue(controller.startBelly(5000));
        assertEquals("hold:lie_back", player.events.getLast());
        assertTrue(controller.holdsMovement());
        now.set(1200);
        controller.update(PetAnimation.SIT);
        assertEquals("hold:belly_up", player.events.getLast());
        now.set(5000);
        assertTrue(controller.rubBelly());
        now.set(7000);
        controller.update(PetAnimation.SIT);
        assertTrue(controller.belly());
        assertEquals("hold:belly_up", player.events.getLast());
        now.set(10000);
        controller.update(PetAnimation.SIT);
        assertEquals("hold:get_up", player.events.getLast());
        assertFalse(controller.rubBelly());
        now.set(11200);
        controller.update(PetAnimation.SIT);
        assertEquals("play:sit:true", player.events.getLast());
        assertFalse(controller.belly());
        assertFalse(controller.holdsMovement());
    }

    @Test
    void bellyRequiresAllThreeClipsWithoutDisturbingCurrentPose() {
        for (PetAnimation missing : List.of(PetAnimation.LIE_BACK, PetAnimation.BELLY_UP, PetAnimation.GET_UP)) {
            Player player = new Player();
            var clips = clips(); clips.remove(missing);
            AnimationController controller = new AnimationController(player, clips);
            controller.update(PetAnimation.IDLE);
            assertFalse(controller.startBelly(5000));
            assertEquals(List.of("play:idle:true"), player.events);
        }
    }

    @Test
    void waterAndExplicitInterruptionsReleaseBellyImmediately() {
        Player player = new Player();
        AnimationController controller = new AnimationController(player, clips());
        assertTrue(controller.startBelly(5000));
        controller.update(PetAnimation.SWIM);
        assertFalse(controller.belly());
        assertFalse(controller.holdsMovement());
        controller.update(PetAnimation.IDLE);
        assertTrue(controller.startBelly(5000));
        controller.cancelAction();
        assertFalse(controller.belly());
    }

    @Test
    void staticHeadTiltIsHeldBrieflyOverSitting() {
        Player player = new Player(); player.lengths.put("head_tilt", 0.0);
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong();
        AnimationController controller = new AnimationController(player, clips(), now::get);
        controller.update(PetAnimation.SIT);
        assertTrue(controller.play(PetAnimation.HEAD_TILT));
        assertTrue(player.active.contains("sit"));
        assertEquals("hold:head_tilt", player.events.getLast());
        now.set(1500); controller.update(PetAnimation.SIT);
        assertFalse(player.active.contains("head_tilt"));
        assertTrue(player.active.contains("sit"));
    }

    @Test
    void jumpDoesNotRepeatWhileFallingAndMissingClipsAreSafe() {
        Player player = new Player(); var clips = clips(); clips.remove(PetAnimation.FALL);
        AnimationController controller = new AnimationController(player, clips);
        controller.update(PetAnimation.JUMP); controller.update(PetAnimation.FALL);
        controller.update(PetAnimation.FALL);
        assertEquals(List.of("hold:jump"), player.events);
        controller.update(PetAnimation.IDLE);
        assertEquals("play:idle:true", player.events.getLast());
        AnimationController empty = new AnimationController(player, Map.of());
        assertDoesNotThrow(() -> empty.update(PetAnimation.SWIM));
        assertFalse(empty.play(PetAnimation.PET));
    }

    @Test
    void customStaticGestureUsesTimeoutAndRestoresPose() {
        Player player = new Player(); player.lengths.put("salute", 0.0);
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong();
        AnimationController controller = new AnimationController(player, clips(), now::get);
        controller.update(PetAnimation.IDLE);
        assertTrue(controller.playCustom(new Clip("salute", 1, 0), 2));
        assertTrue(controller.holdsMovement());
        now.set(2100); controller.update(PetAnimation.IDLE);
        assertFalse(controller.holdsMovement());
        assertEquals("play:idle:true", player.events.getLast());
    }

    @Test void trainingHeadTiltLoopsWithoutRestartingAndEndsWithTheSession() {
        Player player = new Player();
        var now = new java.util.concurrent.atomic.AtomicLong();
        AnimationController controller = new AnimationController(player, clips(), now::get);
        controller.update(PetAnimation.IDLE);
        controller.trainingAttention(true);
        now.set(60_000);
        controller.trainingAttention(true);
        controller.update(PetAnimation.IDLE);
        assertTrue(player.active.contains("head_tilt"));
        assertEquals(1, player.events.stream().filter("play:head_tilt:true"::equals).count());
        controller.trainingAttention(false);
        controller.update(PetAnimation.IDLE);
        assertFalse(player.active.contains("head_tilt"));
        assertTrue(player.active.contains("idle"));
    }

    @Test void trainingAttentionYieldsToTricksAndWaterThenResumes() {
        Player player = new Player();
        var now = new java.util.concurrent.atomic.AtomicLong();
        AnimationController controller = new AnimationController(player, clips(), now::get);
        controller.trainingAttention(true);
        assertTrue(controller.play(PetAnimation.PAW));
        controller.update(PetAnimation.SIT);
        assertFalse(player.active.contains("head_tilt"));
        now.set(2000);
        controller.update(PetAnimation.SIT);
        assertTrue(player.active.contains("head_tilt"));
        controller.update(PetAnimation.JUMP);
        assertFalse(player.active.contains("head_tilt"));
        controller.update(PetAnimation.LIE);
        assertTrue(player.active.contains("head_tilt"));
        controller.trainingAttention(false);
        controller.update(PetAnimation.SWIM);
        assertFalse(player.active.contains("head_tilt"));
    }

    @Test void staticTrainingHeadTiltStaysHeldAndMissingClipIsOptional() {
        Player player = new Player(); player.lengths.put("head_tilt", 0.0);
        var now = new java.util.concurrent.atomic.AtomicLong();
        AnimationController controller = new AnimationController(player, clips(), now::get);
        controller.trainingAttention(true);
        now.set(60_000); controller.update(PetAnimation.SIT);
        assertTrue(player.active.contains("head_tilt"));
        assertEquals(1, player.events.stream().filter("hold:head_tilt"::equals).count());
        var missing = clips(); missing.remove(PetAnimation.HEAD_TILT);
        AnimationController optional = new AnimationController(new Player(), missing);
        assertDoesNotThrow(() -> { optional.trainingAttention(true); optional.update(PetAnimation.IDLE); optional.trainingAttention(false); });
    }

    private static final class Player implements AnimationPlayer {
        final List<String> events = new ArrayList<>();
        final Set<String> active = new HashSet<>();
        final Map<String, Double> lengths = new java.util.HashMap<>();
        public double length(String clip) { return lengths.getOrDefault(clip, 1.0); }
        public boolean hold(Clip clip) { events.add("hold:" + clip.name()); active.add(clip.name()); return true; }
        public boolean play(Clip clip, boolean loop) {
            events.add("play:" + clip.name() + ":" + loop);
            active.add(clip.name());
            return true;
        }
        public void stop(String clip) { events.add("stop:" + clip); active.remove(clip); }
        public boolean playing(String clip) { return active.contains(clip); }
    }
}
