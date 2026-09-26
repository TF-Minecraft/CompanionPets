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

    private FetchNavigationGoal(GoalKey<Mob> key, Pet pet) {
        this.key = key;
        this.pet = pet;
    }

    static void ensure(PetRuntime runtime, Pet pet, Mob body) {
        GoalKey<Mob> key = GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "fetch_navigation"));
        if (!Bukkit.getMobGoals().hasGoal(body, key)) {
            Bukkit.getMobGoals().addGoal(body, 0, new FetchNavigationGoal(key, pet));
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
    public GoalKey<Mob> getKey() {
        return key;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.MOVE);
    }
}
