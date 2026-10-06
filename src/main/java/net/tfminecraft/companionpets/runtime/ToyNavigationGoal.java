package net.tfminecraft.companionpets.runtime;

import java.util.EnumSet;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mob;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import net.tfminecraft.companionpets.pet.Pet;

/** Keeps native wandering and follow goals from distracting a pet watching a toy. */
final class ToyNavigationGoal implements Goal<Mob> {
    private final GoalKey<Mob> key;
    private final Pet pet;
    private final PetToyAnticipation anticipation;
    private ToyNavigationGoal(GoalKey<Mob> key, Pet pet, PetToyAnticipation anticipation) {
        this.key = key; this.pet = pet; this.anticipation = anticipation;
    }
    static void ensure(PetRuntime runtime, Pet pet, Mob body, PetToyAnticipation anticipation) {
        var key = GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "toy_navigation"));
        if (Bukkit.getMobGoals().getGoal(body, key) == null)
            Bukkit.getMobGoals().addGoal(body, 1, new ToyNavigationGoal(key, pet, anticipation));
    }
    @Override public boolean shouldActivate() { return anticipation.active(pet); }
    @Override public boolean shouldStayActive() { return shouldActivate(); }
    @Override public void tick() { anticipation.advance(pet, System.currentTimeMillis()); }
    @Override public GoalKey<Mob> getKey() { return key; }
    @Override public EnumSet<GoalType> getTypes() { return EnumSet.of(GoalType.MOVE, GoalType.JUMP); }
}
