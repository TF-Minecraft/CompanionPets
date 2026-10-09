package net.tfminecraft.companionpets.runtime;

import java.util.EnumSet;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mob;
import com.destroystokyo.paper.entity.ai.*;
import net.tfminecraft.companionpets.behavior.WaterEscape;
import net.tfminecraft.companionpets.integration.PetMotion;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.PetOrder;

/** Idle land pets walk a native route to a dry bank while FloatGoal keeps them afloat. */
final class WaterNavigationGoal implements Goal<Mob> {
    private final GoalKey<Mob> key;
    private final PetRuntime runtime;
    private final Pet pet;
    private final Mob body;
    private Location exit;
    private long routeAt;

    private WaterNavigationGoal(GoalKey<Mob> key, PetRuntime runtime, Pet pet, Mob body) {
        this.key = key; this.runtime = runtime; this.pet = pet; this.body = body;
    }

    static void ensure(PetRuntime runtime, Pet pet, Mob body) {
        var key = GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "water_navigation"));
        var existing = Bukkit.getMobGoals().getGoal(body, key);
        if (!(existing instanceof WaterNavigationGoal))
            Bukkit.getMobGoals().addGoal(body, 0, new WaterNavigationGoal(key, runtime, pet, body));
    }

    @Override public boolean shouldActivate() {
        return body.isValid() && !body.isDead() && runtime.entity(pet) == body
                && !pet.stored() && !pet.dead() && WaterEscape.needed(body)
                && pet.fetch() == null && pet.activity() != Activity.ATTENDING
                && !(pet.order() == PetOrder.FOLLOW && !pet.staying()
                    && runtime.followingAllowed(pet, Bukkit.getPlayer(pet.ownerId())));
    }
    @Override public boolean shouldStayActive() { return shouldActivate(); }
    @Override public void start() { routeAt = 0; tick(); }
    @Override public void tick() { tick(System.currentTimeMillis()); }

    void tick(long now) {
        if (!shouldActivate()) return;
        WaterEscape.swim(body);
        if (now < routeAt) return;
        routeAt = now + 500L;
        Location target = runtime.lastGround(pet);
        if (target == null || !target.getWorld().equals(body.getWorld()) || !WaterEscape.safe(target))
            target = exit != null && exit.getWorld().equals(body.getWorld()) && WaterEscape.safe(exit)
                    ? exit : WaterEscape.exit(body.getLocation());
        if (target != null && (!target.equals(exit) || !body.getPathfinder().hasPath()))
            PetMotion.moveTo(body, target, 1.1);
        exit = target;
    }

    @Override public void stop() { exit = null; routeAt = 0; }
    @Override public GoalKey<Mob> getKey() { return key; }
    @Override public EnumSet<GoalType> getTypes() { return EnumSet.of(GoalType.MOVE); }
}
