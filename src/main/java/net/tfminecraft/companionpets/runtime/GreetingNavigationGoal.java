package net.tfminecraft.companionpets.runtime;

import java.util.EnumSet;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mob;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import net.tfminecraft.companionpets.behavior.WaterEscape;
import net.tfminecraft.companionpets.pet.Pet;

/** Prevents vanilla follow, teleport and idle movement from competing with the circle path. */
final class GreetingNavigationGoal implements Goal<Mob> {
    private final GoalKey<Mob> key;
    private final Pet pet;
    private final Mob body;
    private final PetGreetings greetings;

    private GreetingNavigationGoal(GoalKey<Mob> key, Pet pet, Mob body, PetGreetings greetings) {
        this.key = key;
        this.pet = pet; this.body = body;
        this.greetings = greetings;
    }

    static void ensure(PetRuntime runtime, Pet pet, Mob body, PetGreetings greetings) {
        var key = GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "greeting_navigation"));
        if (Bukkit.getMobGoals().getGoal(body, key) == null)
            Bukkit.getMobGoals().addGoal(body, 1, new GreetingNavigationGoal(key, pet, body, greetings));
    }


    @Override public boolean shouldActivate() { return !WaterEscape.needed(body) && greetings.active(pet); }
    @Override public boolean shouldStayActive() { return shouldActivate(); }
    @Override public void tick() { greetings.advance(pet, System.currentTimeMillis()); }
    @Override public GoalKey<Mob> getKey() { return key; }
    @Override public EnumSet<GoalType> getTypes() { return EnumSet.of(GoalType.MOVE, GoalType.JUMP); }
}
