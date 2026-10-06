package net.tfminecraft.companionpets.runtime;

import java.util.EnumSet;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import com.destroystokyo.paper.entity.ai.*;
import net.tfminecraft.companionpets.behavior.Locomotion;
import net.tfminecraft.companionpets.behavior.WaterEscape;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.integration.PetMotion;
import net.tfminecraft.companionpets.pet.*;

/** Keeps awake sitting/lying pets still while their native look controller runs. */
final class PostureNavigationGoal implements Goal<Mob> {
    private final GoalKey<Mob> key;
    private final PetRuntime runtime;
    private final Pet pet;
    private final Mob body;
    private Entity target;
    private int nextLookAt;

    private PostureNavigationGoal(GoalKey<Mob> key, PetRuntime runtime, Pet pet, Mob body) {
        this.key = key; this.runtime = runtime; this.pet = pet; this.body = body;
    }

    static void hold(PetRuntime runtime, Pet pet, Mob body) {
        GoalKey<Mob> key = GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "posture_navigation"));
        var existing = Bukkit.getMobGoals().getGoal(body, key);
        PostureNavigationGoal goal;
        if (existing instanceof PostureNavigationGoal registered) goal = registered;
        else {
            goal = new PostureNavigationGoal(key, runtime, pet, body);
            Bukkit.getMobGoals().addGoal(body, 0, goal);
        }
        PetMotion.stop(body);
        body.setAware(true);
        goal.tick();
    }

    @Override public boolean shouldActivate() {
        if (!body.isValid() || body.isDead() || pet.stored() || pet.dead()
                || pet.activity() == Activity.SLEEPING || WaterEscape.needed(body)) return false;
        if (runtime.visual().belly(body)) return true;
        var mode = Locomotion.choose(pet.illness(), pet.need(Need.HEALTH), pet.need(Need.ENERGY),
                pet.need(Need.HUNGER), pet.activity(), pet.fetch() != null,
                System.currentTimeMillis() < pet.forcedSitUntilMillis(), pet.order(), pet.staying());
        return mode == Locomotion.Mode.SIT || mode == Locomotion.Mode.LIE;
    }
    @Override public boolean shouldStayActive() { return shouldActivate(); }
    @Override public void tick() {
        if (!shouldActivate()) return;
        PetMotion.stop(body);
        body.setTarget(null);
        PetFx.holdLooking(body, runtime.visual());
        if (!nearby(target) || body.getTicksLived() >= nextLookAt) {
            var candidates = body.getNearbyEntities(6, 3, 6).stream().filter(this::nearby).toList();
            target = candidates.isEmpty() ? null : candidates.get(runtime.random().nextInt(candidates.size()));
            nextLookAt = body.getTicksLived() + 80;
        }
        if (target != null) PetFx.look(body, target);
    }
    private boolean nearby(Entity entity) {
        return entity instanceof LivingEntity && !(entity instanceof ArmorStand) && entity != body
                && entity.isValid() && !entity.isDead() && body.getWorld().equals(entity.getWorld())
                && body.getLocation().distanceSquared(entity.getLocation()) <= 36
                && (!(entity instanceof Player player) || player.isOnline() && player.getGameMode() != GameMode.SPECTATOR);
    }
    @Override public void stop() { target = null; nextLookAt = 0; PetFx.releaseLooking(body); }
    @Override public GoalKey<Mob> getKey() { return key; }
    @Override public EnumSet<GoalType> getTypes() { return EnumSet.of(GoalType.MOVE, GoalType.JUMP); }
}
