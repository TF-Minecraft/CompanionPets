package net.tfminecraft.companionpets.runtime;

import java.util.EnumSet;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import net.tfminecraft.companionpets.behavior.Locomotion;
import net.tfminecraft.companionpets.pet.*;

/** Keeps native wandering from replacing follow paths between behavior ticks. */
final class FollowNavigationGoal implements Goal<Mob> {
    private final GoalKey<Mob> key;
    private final PetRuntime runtime;
    private final PetActions actions;
    private final Pet pet;
    private final Mob body;
    private Player owner;
    private Runnable follow;
    private long nextStepAt;

    private FollowNavigationGoal(GoalKey<Mob> key, PetRuntime runtime, PetActions actions,
            Pet pet, Mob body, Player owner, Runnable follow) {
        this.key = key; this.runtime = runtime; this.actions = actions;
        this.pet = pet; this.body = body; this.owner = owner; this.follow = follow;
    }
    static FollowNavigationGoal ensure(PetRuntime runtime, PetActions actions, Pet pet, Mob body, Player owner, Runnable follow) {
        var key = GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "follow_navigation"));
        var registered = Bukkit.getMobGoals().getGoal(body, key);
        if (registered instanceof FollowNavigationGoal goal) {
            goal.owner = owner; goal.follow = follow; return goal;
        }
        var goal = new FollowNavigationGoal(key, runtime, actions, pet, body, owner, follow);
        Bukkit.getMobGoals().addGoal(body, 2, goal);
        return goal;
    }
    @Override public boolean shouldActivate() {
        return runtime.plugin().isEnabled() && runtime.store().get(pet.id()) == pet && runtime.entity(pet) == body
                && !pet.stored() && !pet.dead() && pet.activity() == Activity.NONE && pet.fetch() == null
                && body.isValid() && !body.isDead() && !body.isInWater() && body.getTarget() == null
                && !runtime.visual().holdsMovement(body) && !actions.socializing(pet)
                && !actions.greeting(pet) && !actions.roaming().exploring(pet)
                && owner != null && owner.isOnline() && owner.getWorld().equals(body.getWorld())
                && pet.ownerId().equals(owner.getUniqueId())
                && runtime.followingAllowed(pet, owner)
                && Locomotion.choose(pet.illness(), pet.need(Need.HEALTH), pet.need(Need.ENERGY), pet.need(Need.HUNGER),
                        pet.activity(), false, System.currentTimeMillis() < pet.forcedSitUntilMillis(),
                        pet.order(), pet.staying()) == Locomotion.Mode.FOLLOW;
    }
    @Override public boolean shouldStayActive() { return shouldActivate(); }
    @Override public void tick() {
        long now = System.currentTimeMillis();
        if (now >= nextStepAt && shouldActivate()) advance(now);
    }
    /** Shared by the behavior task and native goal: one route update per interval. */
    void advance(long now) {
        if (now < nextStepAt) return;
        nextStepAt = now + 250;
        follow.run();
    }
    @Override public GoalKey<Mob> getKey() { return key; }
    @Override public EnumSet<GoalType> getTypes() { return EnumSet.of(GoalType.MOVE); }
}
