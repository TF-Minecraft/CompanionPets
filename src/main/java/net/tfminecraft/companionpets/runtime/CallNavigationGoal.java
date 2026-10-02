package net.tfminecraft.companionpets.runtime;

import java.util.EnumSet;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mob;
import com.destroystokyo.paper.entity.ai.*;
import net.tfminecraft.companionpets.pet.*;

/** Name calls own MOVE so native wandering/follow goals cannot replace their route. */
final class CallNavigationGoal implements Goal<Mob> {
    private final GoalKey<Mob> key;
    private final Pet pet;
    private final Runnable step;
    private CallNavigationGoal(GoalKey<Mob> key, Pet pet, Runnable step) { this.key = key; this.pet = pet; this.step = step; }

    static void ensure(PetRuntime runtime, Pet pet, Mob body, Runnable step) {
        GoalKey<Mob> key = GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "call_navigation"));
        if (!(Bukkit.getMobGoals().getGoal(body, key) instanceof CallNavigationGoal))
            Bukkit.getMobGoals().addGoal(body, 1, new CallNavigationGoal(key, pet, step));
    }
    @Override public boolean shouldActivate() { return pet.activity() == Activity.ATTENDING && pet.order() == PetOrder.FOLLOW && !pet.stored() && !pet.dead(); }
    @Override public boolean shouldStayActive() { return shouldActivate(); }
    @Override public void tick() { if (shouldActivate()) step.run(); }
    @Override public GoalKey<Mob> getKey() { return key; }
    @Override public EnumSet<GoalType> getTypes() { return EnumSet.of(GoalType.MOVE); }
}
