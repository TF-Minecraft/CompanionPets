package net.tfminecraft.companionpets.runtime;

import java.util.EnumSet;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import com.destroystokyo.paper.entity.ai.*;
import net.tfminecraft.companionpets.behavior.WaterEscape;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.integration.PetMotion;
import net.tfminecraft.companionpets.pet.*;

/** Training temporarily owns movement and looking; native idle AI resumes afterwards. */
final class TrainingNavigationGoal implements Goal<Mob> {
    private final GoalKey<Mob> key;
    private final PetRuntime runtime;
    private final Pet pet;
    private final Mob body;
    private Player trainer;
    private boolean restingLook;

    private TrainingNavigationGoal(GoalKey<Mob> key, PetRuntime runtime, Pet pet, Mob body) {
        this.key = key; this.runtime = runtime; this.pet = pet; this.body = body;
    }

    static void begin(PetRuntime runtime, Pet pet, Mob body, Player trainer) {
        var key = key(runtime);
        var existing = Bukkit.getMobGoals().getGoal(body, key);
        TrainingNavigationGoal goal;
        if (existing instanceof TrainingNavigationGoal registered) goal = registered;
        else {
            goal = new TrainingNavigationGoal(key, runtime, pet, body);
            Bukkit.getMobGoals().addGoal(body, 0, goal);
        }
        goal.trainer = trainer;
        if (goal.shouldActivate()) {
            body.setAware(true);
            goal.tick();
        }
    }

    static boolean hold(PetRuntime runtime, Pet pet, Mob body) {
        var session = runtime.sessions().training(pet.ownerId());
        if (session == null || !session.petId().equals(pet.id())) return false;
        var goal = Bukkit.getMobGoals().getGoal(body, key(runtime));
        if (!(goal instanceof TrainingNavigationGoal training) || !training.shouldActivate()) return false;
        body.setAware(true);
        training.tick();
        return true;
    }

    private static GoalKey<Mob> key(PetRuntime runtime) {
        return GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "training_navigation"));
    }

    @Override public boolean shouldActivate() {
        return focused(runtime, pet, body, trainer);
    }

    static boolean focused(PetRuntime runtime, Pet pet, Mob body, Player trainer) {
        if (!body.isValid() || body.isDead() || pet.stored() || pet.dead() || pet.fetch() != null
                || pet.activity() == Activity.SLEEPING || pet.activity() == Activity.ATTENDING
                || WaterEscape.needed(body) || trainer == null || !trainer.isOnline()
                || !pet.ownerId().equals(trainer.getUniqueId()) || !body.getWorld().equals(trainer.getWorld())) return false;
        var session = runtime.sessions().training(trainer.getUniqueId());
        double range = runtime.config().training().sessionDistance();
        return session != null && session.petId().equals(pet.id())
                && body.getLocation().distanceSquared(trainer.getLocation()) <= range * range;
    }

    @Override public boolean shouldStayActive() { return shouldActivate(); }
    @Override public void tick() {
        if (!shouldActivate()) return;
        PetMotion.settle(body);
        body.setTarget(null);
        boolean resting = pet.order() == PetOrder.SIT || pet.order() == PetOrder.LAY
                || System.currentTimeMillis() < pet.forcedSitUntilMillis();
        if (resting) {
            PetFx.lie(body, pet.order() == PetOrder.LAY);
            if (pet.order() != PetOrder.LAY) PetFx.sit(body, true);
            PetFx.holdLooking(body, runtime.visual());
        } else if (restingLook) {
            PetFx.lie(body, false);
            PetFx.sit(body, pet.order() == PetOrder.STAY || pet.staying());
            PetFx.releaseLooking(body);
        }
        restingLook = resting;
        PetFx.look(body, trainer.getEyeLocation());
    }
    @Override public void stop() {
        if (restingLook) PetFx.releaseLooking(body);
        restingLook = false;
    }
    @Override public GoalKey<Mob> getKey() { return key; }
    // PetFx's reusable look goal owns LOOK, including bounded head rotation while resting.
    @Override public EnumSet<GoalType> getTypes() { return EnumSet.of(GoalType.MOVE, GoalType.JUMP); }
}
