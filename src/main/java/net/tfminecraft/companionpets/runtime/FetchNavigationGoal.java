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

/** Holds vanilla movement goals, including follow-owner teleport, while fetching. */
final class FetchNavigationGoal implements Goal<Mob> {
    private final GoalKey<Mob> key;
    private final Pet pet;
    private Runnable fetchStep;
    private long nextStepAt;

    private FetchNavigationGoal(GoalKey<Mob> key, Pet pet, Runnable fetchStep) {
        this.key = key;
        this.pet = pet;
        this.fetchStep = fetchStep;
    }

    static void ensure(PetRuntime runtime, Pet pet, Mob body, Runnable fetchStep) {
        GoalKey<Mob> key = GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "fetch_navigation"));
        Goal<Mob> registered = Bukkit.getMobGoals().getGoal(body, key);
        if (registered instanceof FetchNavigationGoal goal) {
            goal.fetchStep = fetchStep;
        } else {
            Bukkit.getMobGoals().addGoal(body, 1, new FetchNavigationGoal(key, pet, fetchStep));
        }
    }

    @Override
    public boolean shouldActivate() {
        return pet.fetch() != null && pet.activity() == Activity.PLAYING;
    }

    @Override
    public boolean shouldStayActive() {
        return shouldActivate();
    }

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        if (now >= nextStepAt) {
            fetchStep.run();
            nextStepAt = now + 500L;
        }
    }

    @Override
    public GoalKey<Mob> getKey() {
        return key;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.MOVE);
    }
}
