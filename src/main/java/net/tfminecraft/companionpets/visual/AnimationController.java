package net.tfminecraft.companionpets.visual;

import java.util.Map;

import net.tfminecraft.companionpets.config.PetAppearance.Clip;

/** One body pose or gesture, plus an optional head-only training gesture. */
public final class AnimationController {
    private final AnimationPlayer player;
    private final Map<PetAnimation, Clip> clips;
    private PetAnimation pose = PetAnimation.IDLE;
    private PetAnimation action;
    private Clip active;
    private Clip headTilt;
    private final java.util.function.LongSupplier clock;
    private long headUntil, actionUntil;
    private boolean customAction;
    private PetAnimation belly;
    private long bellyUntil, bellyIdleMillis, stageUntil;

    public AnimationController(AnimationPlayer player, Map<PetAnimation, Clip> clips) {
        this(player, clips, System::currentTimeMillis);
    }

    public AnimationController(AnimationPlayer player, Map<PetAnimation, Clip> clips, java.util.function.LongSupplier clock) {
        this.player = player;
        this.clips = clips;
        this.clock = clock;
    }

    public void update(PetAnimation next) {
        if (!next.pose()) throw new IllegalArgumentException("Expected a pose: " + next);
        if (action == PetAnimation.SHAKE && (next == PetAnimation.WALK || next == PetAnimation.RUN
                || next == PetAnimation.CROUCH || next == PetAnimation.FLY || next == PetAnimation.HOVER)) cancelAction();
        if (next != pose && (next == PetAnimation.SLEEP || next == PetAnimation.LIE
                || next == PetAnimation.SWIM || next == PetAnimation.JUMP || next == PetAnimation.FALL)) cancelAction();
        pose = next;
        long now = clock.getAsLong();
        if (headTilt != null && (now >= headUntil || !player.playing(headTilt.name()))) stopHeadTilt();
        if (advanceBelly(now)) return;
        if (action != null || customAction) {
            if (now < actionUntil && active != null && player.playing(active.name())) return;
            stopActive();
            action = null;
            customAction = false;
        }
        boolean airborne = pose == PetAnimation.JUMP || pose == PetAnimation.FALL;
        Clip jump = clips.get(PetAnimation.JUMP);
        Clip wanted = airborne && jump != null && !clips.containsKey(PetAnimation.FALL) ? jump : resolve(pose);
        if (wanted == null) { stopActive(); return; }
        if (wanted != null && (!wanted.equals(active) || !player.playing(wanted.name()))) {
            stopActive();
            if (airborne && wanted == jump ? player.hold(wanted) : player.play(wanted, true)) active = wanted;
        }
    }

    public boolean play(PetAnimation next) {
        if (next.pose() || next == PetAnimation.DEATH) return false;
        Clip clip = clips.get(next);
        if (clip == null) return false;
        if (belly != null) return false;
        if ((next == PetAnimation.PET || next == PetAnimation.SHAKE) && (action != null || customAction)) return false;
        if (next == PetAnimation.HEAD_TILT) {
            if (action != null || customAction || pose == PetAnimation.SLEEP || pose == PetAnimation.LIE
                    || pose == PetAnimation.SWIM || pose == PetAnimation.JUMP || pose == PetAnimation.FALL) return false;
            if (headTilt != null && player.playing(headTilt.name())) return false;
            if (player.length(clip.name()) <= 0 ? !player.hold(clip) : !player.play(clip, false)) return false;
            headTilt = clip;
            headUntil = clock.getAsLong() + duration(clip, 1.2);
            return true;
        }
        stopHeadTilt();
        stopActive();
        action = null;
        customAction = false;
        if (player.length(clip.name()) <= 0 ? !player.hold(clip) : !player.play(clip, false)) return false;
        action = next;
        active = clip;
        actionUntil = clock.getAsLong() + duration(clip, 2);
        return true;
    }

    public boolean playCustom(Clip clip, double staticSeconds) {
        cancelAction();
        stopActive();
        if (player.length(clip.name()) <= 0 ? !player.hold(clip) : !player.play(clip, false)) return false;
        customAction = true;
        active = clip;
        actionUntil = clock.getAsLong() + duration(clip, staticSeconds);
        return true;
    }

    private long duration(Clip clip, double staticSeconds) {
        double seconds = player.length(clip.name());
        return Math.max(100, Math.round(((seconds > 0 ? seconds / clip.speed() : staticSeconds) + clip.blend()) * 1000));
    }

    public boolean belly() { return belly != null; }

    public boolean startBelly(long idleMillis) {
        if (belly != null || action != null || customAction || !clips.containsKey(PetAnimation.LIE_BACK)
                || !clips.containsKey(PetAnimation.BELLY_UP) || !clips.containsKey(PetAnimation.GET_UP)) return false;
        cancelAction();
        bellyIdleMillis = idleMillis;
        return bellyStage(PetAnimation.LIE_BACK);
    }

    public boolean rubBelly() {
        if (belly == null || belly == PetAnimation.GET_UP) return false;
        bellyUntil = clock.getAsLong() + bellyIdleMillis;
        return true;
    }

    private boolean bellyStage(PetAnimation stage) {
        stopActive();
        Clip clip = clips.get(stage);
        boolean held = stage != PetAnimation.BELLY_UP || player.length(clip.name()) <= 0;
        boolean success = held ? player.hold(clip) : player.play(clip, stage == PetAnimation.BELLY_UP);
        if (!success) { belly = null; return false; }
        belly = stage;
        active = clip;
        long now = clock.getAsLong();
        stageUntil = now + duration(clip, 1);
        if (stage == PetAnimation.BELLY_UP) bellyUntil = now + bellyIdleMillis;
        return true;
    }

    private boolean advanceBelly(long now) {
        if (belly == null) return false;
        if (belly == PetAnimation.LIE_BACK && now >= stageUntil) return bellyStage(PetAnimation.BELLY_UP);
        if (belly == PetAnimation.BELLY_UP && now >= bellyUntil) return bellyStage(PetAnimation.GET_UP);
        if (belly == PetAnimation.GET_UP && now >= stageUntil) { cancelAction(); return false; }
        return true;
    }

    public boolean holdsMovement() {
        return belly != null || ((customAction || action != null && action.holdsMovement())
                && active != null && clock.getAsLong() < actionUntil && player.playing(active.name()));
    }

    public void cancelAction() {
        stopHeadTilt();
        if (action != null || customAction || belly != null) stopActive();
        action = null;
        customAction = false;
        belly = null;
    }

    private Clip resolve(PetAnimation state) {
        for (PetAnimation current = state; current != null; current = current.fallback()) {
            Clip clip = clips.get(current);
            if (clip != null) return state == PetAnimation.RUN && current == PetAnimation.WALK
                    ? new Clip(clip.name(), clip.speed() * 1.5, clip.blend()) : clip;
        }
        return clips.get(PetAnimation.IDLE);
    }

    private void stopActive() {
        if (active != null) player.stop(active.name());
        active = null;
    }

    private void stopHeadTilt() {
        if (headTilt != null) player.stop(headTilt.name());
        headTilt = null;
    }
}
