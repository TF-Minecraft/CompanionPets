package net.tfminecraft.companionpets.fx;

import java.util.EnumSet;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;

/** Keeps the native look controller engaged between the ten-tick behavior decisions. */
final class PetLookGoal implements Goal<Mob> {
    private static final GoalKey<Mob> KEY = GoalKey.of(Mob.class, new NamespacedKey("companionpets", "look"));
    private static final int HOLD_TICKS = 14;
    private final Mob body;
    private Entity target;
    private Location point;
    private int expiresAt;

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
        expiresAt = body.getTicksLived() + HOLD_TICKS;
    }

    void track(Location point) {
        target = null;
        this.point = point.clone();
        expiresAt = body.getTicksLived() + HOLD_TICKS;
    }

    static void remove(Mob body) { Bukkit.getMobGoals().removeGoal(body, KEY); }

    private static PetLookGoal ensure(Mob body) {
        Goal<Mob> registered = Bukkit.getMobGoals().getGoal(body, KEY);
        if (registered instanceof PetLookGoal goal) return goal;
        PetLookGoal goal = new PetLookGoal(body);
        Bukkit.getMobGoals().addGoal(body, 0, goal);
        return goal;
    }

    @Override public boolean shouldActivate() {
        if (!body.isValid() || body.isDead() || body.getTicksLived() >= expiresAt || body.getTarget() != null) {
            stop();
            return false;
        }
        boolean valid = target != null ? target.isValid() && !target.isDead()
                && (!(target instanceof Player player) || player.isOnline())
                && body.getWorld().equals(target.getWorld())
                : point != null && body.getWorld().equals(point.getWorld());
        if (!valid) stop();
        return valid;
    }

    @Override public void tick() {
        if (!shouldActivate()) return;
        // These Mob overloads use LookControl's gradual head/body rotation, rather
        // than LivingEntity.lookAt(..., LookAnchor), which sets the angles at once.
        if (target != null) body.lookAt(target, body.getHeadRotationSpeed(), body.getMaxHeadPitch());
        else body.lookAt(point, body.getHeadRotationSpeed(), body.getMaxHeadPitch());
    }

    @Override public void stop() { target = null; point = null; }
    @Override public GoalKey<Mob> getKey() { return KEY; }
    @Override public EnumSet<GoalType> getTypes() { return EnumSet.of(GoalType.LOOK); }
}
