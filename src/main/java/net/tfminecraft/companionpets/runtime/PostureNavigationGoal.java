package net.tfminecraft.companionpets.runtime;

import java.util.EnumSet;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mob;
import com.destroystokyo.paper.entity.ai.*;
import net.tfminecraft.companionpets.behavior.Locomotion;
import net.tfminecraft.companionpets.behavior.WaterEscape;
import net.tfminecraft.companionpets.integration.PetMotion;
import net.tfminecraft.companionpets.pet.*;

/**
 * Keeps staying, sitting, lying and sleeping pets still with native AI awake, like a
 * vanilla sitting pet. Their head is left to the native look goals, except while asleep.
 */
final class PostureNavigationGoal implements Goal<Mob> {
    private final GoalKey<Mob> key;
    private final GoalKey<Mob> trainingKey;
    private final PetRuntime runtime;
    private final Pet pet;
    private final Mob body;

    private PostureNavigationGoal(GoalKey<Mob> key, PetRuntime runtime, Pet pet, Mob body) {
        this.key = key; this.runtime = runtime; this.pet = pet; this.body = body;
        trainingKey = GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "training_navigation"));
    }

    static void hold(PetRuntime runtime, Pet pet, Mob body) {
        GoalKey<Mob> key = GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "posture_navigation"));
        var existing = Bukkit.getMobGoals().getGoal(body, key);
        PostureNavigationGoal goal;
        if (existing instanceof PostureNavigationGoal registered) goal = registered;
        else {
            goal = new PostureNavigationGoal(key, runtime, pet, body);
            Bukkit.getMobGoals().addGoal(body, 0, goal);
            Bukkit.getMobGoals().addGoal(body, 0, new SleepingLook(
                    GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "sleeping_look")), goal));
        }
        PetMotion.stop(body);
        body.setAware(true);
        goal.tick();
    }

    private Locomotion.Mode mode() {
        Locomotion.Mode mode = Locomotion.choose(pet.illness(), pet.need(Need.HEALTH), pet.need(Need.ENERGY),
                pet.need(Need.HUNGER), pet.activity(), pet.fetch() != null,
                System.currentTimeMillis() < pet.forcedSitUntilMillis(), pet.order(), pet.staying());
        // Like the ticker, an idle pet that may not follow its owner right now stays where it is.
        if (mode == Locomotion.Mode.FOLLOW && pet.activity() == Activity.NONE
                && !runtime.followingAllowed(pet, Bukkit.getPlayer(pet.ownerId()))) return Locomotion.Mode.STAY;
        return mode;
    }

    @Override public boolean shouldActivate() {
        if (!body.isValid() || body.isDead() || pet.stored() || pet.dead() || WaterEscape.needed(body)) return false;
        var training = Bukkit.getMobGoals().getGoal(body, trainingKey);
        if (training instanceof TrainingNavigationGoal focus && focus.shouldActivate()) return false;
        if (runtime.visual().belly(body)) return true;
        var mode = mode();
        return mode == Locomotion.Mode.STAY || mode == Locomotion.Mode.SIT
                || mode == Locomotion.Mode.LIE || mode == Locomotion.Mode.SLEEP;
    }
    // A sleeping pet still turns its head to whoever just said its name.
    boolean asleep() {
        return System.currentTimeMillis() >= pet.listeningUntilMillis() && shouldActivate() && mode() == Locomotion.Mode.SLEEP;
    }
    @Override public boolean shouldStayActive() { return shouldActivate(); }
    // A push would give the body speed that ModelEngine reads as walking before tick() clears it.
    @Override public void stop() { body.setCollidable(true); }
    @Override public void tick() {
        if (!shouldActivate()) return;
        PetMotion.stop(body);
        body.setTarget(null);
        if (body.isCollidable()) body.setCollidable(false);
    }
    @Override public GoalKey<Mob> getKey() { return key; }
    @Override public EnumSet<GoalType> getTypes() { return EnumSet.of(GoalType.MOVE, GoalType.JUMP); }

    /** Holds LOOK while asleep so native look goals do not turn a sleeping head. */
    private record SleepingLook(GoalKey<Mob> key, PostureNavigationGoal posture) implements Goal<Mob> {
        @Override public boolean shouldActivate() { return posture.asleep(); }
        @Override public boolean shouldStayActive() { return shouldActivate(); }
        @Override public GoalKey<Mob> getKey() { return key; }
        @Override public EnumSet<GoalType> getTypes() { return EnumSet.of(GoalType.LOOK); }
    }
}
