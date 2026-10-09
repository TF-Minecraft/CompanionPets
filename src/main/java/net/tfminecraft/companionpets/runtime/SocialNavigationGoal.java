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

/** Claims movement during a meeting and remains registered, inactive, after it ends. */
final class SocialNavigationGoal implements Goal<Mob> {
    private final GoalKey<Mob> key;
    private final Pet pet;
    private final Mob body;
    private final PetSocial social;
    private SocialNavigationGoal(GoalKey<Mob> key, Pet pet, Mob body, PetSocial social) {
        this.key = key; this.pet = pet; this.body = body; this.social = social;
    }
    static void ensure(PetRuntime runtime, Pet pet, Mob body, PetSocial social) {
        var key = GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "social_navigation"));
        if (Bukkit.getMobGoals().getGoal(body, key) == null)
            Bukkit.getMobGoals().addGoal(body, 1, new SocialNavigationGoal(key, pet, body, social));
    }
    @Override public boolean shouldActivate() { return !WaterEscape.needed(body) && social.engaged(pet); }
    @Override public boolean shouldStayActive() { return shouldActivate(); }
    @Override public void tick() { social.advance(pet, System.currentTimeMillis()); }
    @Override public GoalKey<Mob> getKey() { return key; }
    @Override public EnumSet<GoalType> getTypes() { return EnumSet.of(GoalType.MOVE, GoalType.JUMP); }
}
