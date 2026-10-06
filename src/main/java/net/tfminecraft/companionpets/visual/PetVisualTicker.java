package net.tfminecraft.companionpets.visual;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Cat;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Wolf;

import net.tfminecraft.companionpets.behavior.Locomotion;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.runtime.PetRuntime;

/**
 * Overlays postures, gestures and the tail wag. ModelEngine detects idle, walk,
 * jump and fly on its own, so this runs a few times a second without sampling motion.
 */
public final class PetVisualTicker implements Runnable {
    public static final long PERIOD_TICKS = 4L;
    private final PetRuntime runtime;
    private final Map<UUID, Boolean> shaking = new HashMap<>();

    public PetVisualTicker(PetRuntime runtime) { this.runtime = runtime; }

    @Override
    public void run() {
        Set<UUID> loaded = new HashSet<>();
        long now = System.currentTimeMillis();
        for (Pet pet : runtime.store().active()) {
            PetTypeDef type = runtime.config().type(pet.typeId());
            if (type == null || !(runtime.entity(pet) instanceof Mob body)) continue;
            UUID id = body.getUniqueId();
            loaded.add(id);
            boolean fetching = pet.fetch() != null;
            boolean greeting = pet.activity() == net.tfminecraft.companionpets.pet.Activity.GREETING;
            boolean toyFocus = pet.activity() == net.tfminecraft.companionpets.pet.Activity.TOY_FOCUS;
            boolean trainingFocus = runtime.trainingFocused(pet, body);
            if (body instanceof Wolf wolf) {
                if (fetching || greeting || toyFocus || trainingFocus || pet.socialTailHz() > 0) net.tfminecraft.companionpets.integration.WolfShake.defer(wolf);
                else net.tfminecraft.companionpets.integration.WolfShake.restore(wolf);
            }
            if (!type.appearance().modeled()) continue;
            Locomotion.Mode mode = Locomotion.choose(pet.illness(), pet.need(Need.HEALTH),
                    pet.need(Need.ENERGY), pet.need(Need.HUNGER), pet.activity(), pet.fetch() != null,
                    now < pet.forcedSitUntilMillis(), pet.order(), pet.staying());
            boolean sitting = body instanceof Sittable sittable && sittable.isSitting()
                    || body instanceof Fox fox && fox.isSitting();
            PetAnimation pose = VisualPose.select(mode, sitting, body.isInWater());
            if (body instanceof Cat && body.isSneaking() && pose == PetAnimation.IDLE) pose = PetAnimation.CROUCH;
            if (fetching) runtime.visual().cancelAction(body);
            runtime.visual().trainingAttention(body, type, trainingFocus);
            runtime.visual().update(body, type, pose);
            // Let the lying/sitting pose blend to standing before capturing the tail's base transform.
            double feeling = net.tfminecraft.companionpets.behavior.GreetingMood.of(pet, pet.bond()).intensity();
            double tailHz = greeting && now - pet.lastGreetingMillis() >= 300
                    ? 1 + (runtime.config().greeting().tailWagHz() - 1) * feeling
                    : toyFocus
                            ? 3.2 + 1.3 * feeling + (now < pet.toyExcitedUntilMillis() ? 0.8 : 0) : pet.socialTailHz();
            runtime.visual().wagTail(body, runtime.behaves(pet, toyFocus
                    ? net.tfminecraft.companionpets.config.PetBehavior.TOY_TAIL_WAG
                    : greeting ? net.tfminecraft.companionpets.config.PetBehavior.GREETING_TAIL_WAG
                    : net.tfminecraft.companionpets.config.PetBehavior.SOCIAL_TAIL_WAG) ? tailHz : 0);
            if (!fetching && runtime.visual().holdsMovement(body)) net.tfminecraft.companionpets.integration.PetMotion.stop(body);
            boolean shakes = !fetching && !greeting && !toyFocus && !trainingFocus && body instanceof Wolf wolf && net.tfminecraft.companionpets.integration.WolfShake.shaking(wolf);
            if (shakes && !Boolean.TRUE.equals(shaking.get(id))) {
                runtime.visual().play(body, type, "SHAKE");
            }
            if (shakes && runtime.visual().attached(body)) {
                // The hidden vanilla wolf cannot render its client-side water droplets.
                body.getWorld().spawnParticle(org.bukkit.Particle.SPLASH,
                        body.getLocation().add(0, body.getHeight() * 0.55, 0), 24,
                        body.getWidth() * 0.55, body.getHeight() * 0.25, body.getWidth() * 0.55, 0.08);
            }
            shaking.put(id, shakes);
        }
        shaking.keySet().retainAll(loaded);
        net.tfminecraft.companionpets.integration.WolfShake.retain(loaded);
        runtime.visual().retain(loaded);
    }
}
