package net.tfminecraft.companionpets.runtime;

import java.util.EnumSet;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mob;
import com.destroystokyo.paper.entity.ai.*;
import net.tfminecraft.companionpets.behavior.WaterEscape;
import net.tfminecraft.companionpets.pet.Pet;

/** Owns movement while swimming, ahead of rest, calls, fetching and native owner following. */
final class WaterNavigationGoal implements Goal<Mob> {
    private final GoalKey<Mob> key;
    private final PetRuntime runtime;
    private final Pet pet;
    private final Mob body;
    private Location exit;
    private long searchAt;

    private WaterNavigationGoal(GoalKey<Mob> key, PetRuntime runtime, Pet pet, Mob body) {
        this.key = key; this.runtime = runtime; this.pet = pet; this.body = body;
    }

    static void ensure(PetRuntime runtime, Pet pet, Mob body) {
        GoalKey<Mob> key = GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "water_navigation"));
        if (!(Bukkit.getMobGoals().getGoal(body, key) instanceof WaterNavigationGoal))
            Bukkit.getMobGoals().addGoal(body, 0, new WaterNavigationGoal(key, runtime, pet, body));
    }

    @Override public boolean shouldActivate() { return !pet.stored() && !pet.dead() && WaterEscape.needed(body); }
    @Override public boolean shouldStayActive() { return shouldActivate(); }
    @Override public void start() { searchAt = 0; tick(); }
    @Override public void tick() {
        if (!shouldActivate()) return;
        long now = System.currentTimeMillis();
        if (now >= searchAt) {
            var owner = Bukkit.getPlayer(pet.ownerId());
            Location preferred = pet.order() == net.tfminecraft.companionpets.pet.PetOrder.FOLLOW && !pet.staying()
                    && runtime.followingAllowed(pet, owner) ? owner.getLocation() : null;
            exit = WaterEscape.exit(body.getLocation(), preferred);
            if (exit != null) body.getPathfinder().moveTo(exit, 1.1);
            searchAt = now + 1_000L;
        }
        WaterEscape.swim(body, exit);
    }
    @Override public void stop() { exit = null; body.getPathfinder().stopPathfinding(); }
    @Override public GoalKey<Mob> getKey() { return key; }
    @Override public EnumSet<GoalType> getTypes() { return EnumSet.of(GoalType.MOVE); }
}
