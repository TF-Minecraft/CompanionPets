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
    @Test void nativeShakePlaysTheClipAtItsOwnSpeedAndLastsAsLongAsTheClip() {
        Player player = new Player(); player.lengths.put("shake", 1.04);
        var now = new java.util.concurrent.atomic.AtomicLong();
        AnimationController controller = new AnimationController(player, clips(), now::get);
        assertTrue(controller.shake(.05));
        assertEquals(1, player.lastClip.speed(), "The configured clip speed is never stretched to the native clock");
        now.set(600); controller.update(PetAnimation.RUN);
        assertTrue(controller.shake(1.25)); assertTrue(player.active.contains("shake"));
        now.set(1100);
        assertTrue(controller.shake(0), "The native end does not cut the clip short");
        now.set(1200);
        assertFalse(controller.shake(1.6), "The droplets stop when the clip ends"); assertFalse(player.active.contains("shake"));
        assertFalse(controller.shake(1.7), "The same native cycle never replays it");
        assertFalse(controller.shake(.7), "A model attached late must skip the cycle");
        controller.shake(0); controller.play(PetAnimation.PAW);
        assertFalse(controller.shake(.05)); controller.cancelAction();
        assertFalse(controller.shake(.5), "Finishing another action must not restart the shake");
    }
    @Test void firstSampleWithinTheTickerWindowStartsTheShake() {
        Player player = new Player(); player.lengths.put("shake", 1.04);
        AnimationController controller = new AnimationController(player, clips());
        assertTrue(controller.shake(.2), "One 4-tick visual sample after the native start");
        controller.cancelAction(); controller.shake(0);
        assertFalse(controller.shake(.3), "Later than one sample window skips the cycle");
    }
    @Test void waterAndRestStopShakeWithoutReplayingTheSameCycle() {
        for (PetAnimation pose : List.of(PetAnimation.SWIM, PetAnimation.LIE, PetAnimation.SLEEP)) {
            Player player = new Player(); AnimationController controller = new AnimationController(player, clips());
            assertTrue(controller.shake(.05)); controller.update(pose);
            assertFalse(controller.shake(.5)); assertFalse(player.active.contains("shake"));
            controller.update(PetAnimation.IDLE); assertFalse(controller.shake(.7));
        }
    }
    @Test void shakingNeverStopsNavigationAndSurvivesWalking() {
        Player player = new Player();
        AnimationController controller = new AnimationController(player, clips());
        controller.update(PetAnimation.IDLE);
        assertTrue(controller.play(PetAnimation.SHAKE));
        assertFalse(controller.holdsMovement());
        controller.update(PetAnimation.WALK);
        assertTrue(player.active.contains("shake"));
    }
    @Test
    void airAndWaterWithoutClipsAreLeftToModelEngineAndLyingDownSleeps() {
        Player player = new Player();
        Map<PetAnimation, Clip> clips = new EnumMap<>(PetAnimation.class);
        for (PetAnimation animation : List.of(PetAnimation.IDLE, PetAnimation.WALK, PetAnimation.SIT, PetAnimation.SLEEP, PetAnimation.PAW))
            clips.put(animation, new Clip(animation.name().toLowerCase(java.util.Locale.ROOT), 1, 0.15));
        clips.put(PetAnimation.LIE, clips.get(PetAnimation.SLEEP));
        AnimationController controller = new AnimationController(player, clips);
        controller.update(PetAnimation.JUMP);
        controller.update(PetAnimation.FALL);
        assertEquals(List.of(), player.events);
        controller.update(PetAnimation.SWIM);
        assertEquals(List.of(), player.events);
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
        assertEquals(List.of("play:sleep:true", "stop:sleep"), player.events);
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
        assertTrue(player.active.isEmpty());
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
        assertTrue(player.active.isEmpty());
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
            assertEquals(List.of(), player.events);
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
    void airbornePosesAreLeftToModelEngineAndMissingClipsAreSafe() {
        Player player = new Player();
        AnimationController controller = new AnimationController(player, clips());
        for (PetAnimation pose : List.of(PetAnimation.JUMP, PetAnimation.FALL, PetAnimation.FLY, PetAnimation.HOVER, PetAnimation.RUN))
            controller.update(pose);
        assertEquals(List.of(), player.events);
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
        assertEquals("stop:salute", player.events.getLast()); assertTrue(player.active.isEmpty());
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
        assertTrue(player.active.isEmpty());
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

    @Test void configuredSwimClipOverlaysWaterAndLeavingItHandsBackToModelEngine() {
        Player player = new Player();
        AnimationController controller = new AnimationController(player, clips());
        controller.update(PetAnimation.SWIM);
        assertEquals(List.of("play:swim:true"), player.events);
        controller.update(PetAnimation.IDLE);
        assertEquals(List.of("play:swim:true", "stop:swim"), player.events);
    }

    private static final class Player implements AnimationPlayer {
        final List<String> events = new ArrayList<>();
        final Set<String> active = new HashSet<>();
        final Map<String, Double> lengths = new java.util.HashMap<>();
        Clip lastClip;
        public double length(String clip) { return lengths.getOrDefault(clip, 1.0); }
        public boolean hold(Clip clip) { events.add("hold:" + clip.name()); active.add(clip.name()); return true; }
        public boolean play(Clip clip, boolean loop) {
            lastClip = clip;
            events.add("play:" + clip.name() + ":" + loop);
            active.add(clip.name());
            return true;
        }
        public void stop(String clip) { events.add("stop:" + clip); active.remove(clip); }
        public boolean playing(String clip) { return active.contains(clip); }
    }
}
