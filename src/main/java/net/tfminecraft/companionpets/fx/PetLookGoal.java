package net.tfminecraft.companionpets.fx;

import java.util.EnumSet;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import net.tfminecraft.companionpets.integration.PetHeadRotation;
import net.tfminecraft.companionpets.visual.PetVisual;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;

/** Keeps the native look controller engaged between the ten-tick behavior decisions. */
final class PetLookGoal implements Goal<Mob> {
    private static final GoalKey<Mob> KEY = GoalKey.of(Mob.class, new NamespacedKey("companionpets", "look"));
    private static final int HOLD_TICKS = 14;
    private static final Map<Mob, WeakReference<PetLookGoal>> GOALS = new WeakHashMap<>();
    private final Mob body;
    private Entity target;
    private Location point;
    private int expiresAt;
    private int targetExpiresAt;
    private Float restingYaw;
    private RestingLook.Angles restingAngles;
    private PetVisual visual;

    PetLookGoal(Mob body) { this.body = body; }

    static void look(Mob body, Entity target) {
        ensure(body).track(target);
    }

    static void look(Mob body, Location point) {
        ensure(body).track(point);
    }

    void track(Entity target) {
        this.target = target;
        point = null;
        targetExpiresAt = expiresAt = body.getTicksLived() + HOLD_TICKS;
    }

    void track(Location point) {
        target = null;
        this.point = point.clone();
        targetExpiresAt = expiresAt = body.getTicksLived() + HOLD_TICKS;
    }

    // A goal callback may release attention while GoalSelector is iterating.
    // Keep the bounded per-body goal registered and make it inactive instead.
    static void remove(Mob body) { release(body); }

    static void hold(Mob body, PetVisual visual) {
        ensure(body).hold(visual);
    }

    void hold(PetVisual visual) {
        if (restingYaw == null) {
            restingYaw = RestingLook.wrap(body.getBodyYaw());
            restingAngles = new RestingLook.Angles(restingYaw, 0);
        }
        this.visual = visual;
        expiresAt = body.getTicksLived() + HOLD_TICKS;
    }

    static void release(Mob body) {
        PetLookGoal goal = registered(body);
        if (goal != null) goal.stop();
    }

    static void forget(Mob body) { release(body); GOALS.remove(body); }

    private static PetLookGoal registered(Mob body) {
        var cached = GOALS.get(body);
        if (cached != null && cached.get() != null) return cached.get();
        if (cached == null && GOALS.containsKey(body)) return null;
        var registered = Bukkit.getMobGoals().getGoal(body, KEY);
        PetLookGoal goal = registered instanceof PetLookGoal look ? look : null;
        // Weak values prevent the goal's body reference from retaining the weak map key.
        GOALS.put(body, goal == null ? null : new WeakReference<>(goal));
        return goal;
    }

    private static PetLookGoal ensure(Mob body) {
        PetLookGoal goal = registered(body);
        if (goal != null) return goal;
        goal = new PetLookGoal(body);
        Bukkit.getMobGoals().addGoal(body, 0, goal);
        GOALS.put(body, new WeakReference<>(goal));
        return goal;
    }

    @Override public boolean shouldActivate() {
        if (!body.isValid() || body.isDead() || body.getTicksLived() >= expiresAt || body.getTarget() != null) {
            stop();
            return false;
        }
        boolean valid = body.getTicksLived() < targetExpiresAt && (target != null ? target.isValid() && !target.isDead()
                && (!(target instanceof Player player) || player.isOnline())
                && body.getWorld().equals(target.getWorld())
                : point != null && body.getWorld().equals(point.getWorld()));
        if (!valid) { target = null; point = null; }
        return valid || restingYaw != null;
    }

    @Override public void tick() {
        if (!shouldActivate()) return;
        if (restingYaw != null) {
            Location desired = target instanceof LivingEntity living ? living.getEyeLocation()
                    : target != null ? target.getLocation() : point;
            restingAngles = RestingLook.next(restingYaw, restingAngles, body.getEyeLocation(), desired);
            Location direction = body.getEyeLocation();
            direction.setYaw(restingAngles.yaw()); direction.setPitch(restingAngles.pitch());
            Location aim = direction.clone().add(direction.getDirection());
            boolean applied = PetHeadRotation.apply(body, restingYaw, restingAngles.yaw(), restingAngles.pitch());
            // Keep LookControl engaged so its idle recentering cannot undo the head pose.
            // LookControl resets pitch to zero first; allow it to restore exactly
            // this tick's bounded pitch, while leaving the head yaw untouched.
            body.lookAt(aim, applied ? 0 : RestingLook.STEP,
                    applied ? Math.abs(restingAngles.pitch()) : RestingLook.STEP);
            visual.holdHeadLook(body, restingYaw, restingAngles.yaw(), restingAngles.pitch());
            return;
        }
        // These Mob overloads use LookControl's gradual head/body rotation, rather
        // than LivingEntity.lookAt(..., LookAnchor), which sets the angles at once.
        if (target != null) body.lookAt(target, body.getHeadRotationSpeed(), body.getMaxHeadPitch());
        else body.lookAt(point, body.getHeadRotationSpeed(), body.getMaxHeadPitch());
    }

    @Override public void stop() {
        target = null; point = null;
        expiresAt = targetExpiresAt = 0;
        if (restingYaw != null) visual.releaseHeadLook(body);
        restingYaw = null; restingAngles = null; visual = null;
    }
    @Override public GoalKey<Mob> getKey() { return KEY; }
    @Override public EnumSet<GoalType> getTypes() { return EnumSet.of(GoalType.LOOK); }
}
