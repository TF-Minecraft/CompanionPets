package net.tfminecraft.companionpets.runtime;

import java.util.EnumSet;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mob;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.Pet;

/** Brief play sessions take precedence over native follow and stroll goals. */
final class PlayNavigationGoal implements Goal<Mob> {
    private final GoalKey<Mob> key;
    private final Pet pet;
    private Runnable step;
    private long nextStep;

    private PlayNavigationGoal(GoalKey<Mob> key, Pet pet, Runnable step) {
        this.key = key; this.pet = pet; this.step = step;
    }

    static PlayNavigationGoal ensure(PetRuntime runtime, Pet pet, Mob body, Runnable step) {
        var key = GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "play_navigation"));
        var registered = Bukkit.getMobGoals().getGoal(body, key);
        if (registered instanceof PlayNavigationGoal goal) { goal.step = step; return goal; }
        var goal = new PlayNavigationGoal(key, pet, step);
        Bukkit.getMobGoals().addGoal(body, 1, goal);
        return goal;
    }

    @Override public boolean shouldActivate() {
        return pet.activity() == Activity.PLAYING && pet.fetch() == null && !pet.stored() && !pet.dead();
    }
    @Override public boolean shouldStayActive() { return shouldActivate(); }
    @Override public void tick() {
        long now = System.currentTimeMillis();
        if (shouldActivate() && now >= nextStep) { nextStep = now + 500; step.run(); }
    }
    @Override public void stop() { nextStep = 0; }
    @Override public GoalKey<Mob> getKey() { return key; }
    @Override public EnumSet<GoalType> getTypes() { return EnumSet.of(GoalType.MOVE, GoalType.JUMP); }
}
