package net.tfminecraft.companionpets.visual;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Bat;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Flying;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Vex;
import org.bukkit.entity.Wolf;

import net.tfminecraft.companionpets.behavior.Locomotion;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.runtime.PetRuntime;

/** Samples actual displacement, including vanilla AI, fetch, social play and roaming. */
public final class PetVisualTicker implements Runnable {
    private final PetRuntime runtime;
    private final Map<UUID, Sample> samples = new HashMap<>();

    public PetVisualTicker(PetRuntime runtime) { this.runtime = runtime; }

    @Override
    public void run() {
        Set<UUID> loaded = new HashSet<>();
        long now = System.currentTimeMillis();
        for (Pet pet : runtime.store().all()) {
            if (pet.stored() || pet.dead()) continue;
            PetTypeDef type = runtime.config().type(pet.typeId());
            if (type == null || !type.appearance().modeled() || !(runtime.entity(pet) instanceof Mob body)) continue;
            UUID id = body.getUniqueId();
            loaded.add(id);
            Sample previous = samples.get(id);
            Location at = body.getLocation();
            double speed = 0;
            if (previous != null && previous.at.getWorld().equals(at.getWorld())) {
                double distance = previous.at.distanceSquared(at);
                int ticks = body.getTicksLived() - previous.ticks;
                if (ticks > 0 && distance < 16) {
                    speed = Math.hypot(at.getX() - previous.at.getX(), at.getZ() - previous.at.getZ()) / ticks;
                }
            }
            Locomotion.Mode mode = Locomotion.choose(pet.illness(), pet.need(Need.HEALTH),
                    pet.need(Need.ENERGY), pet.need(Need.HUNGER), pet.activity(), pet.fetch() != null,
                    now < pet.forcedSitUntilMillis(), pet.order(), pet.staying());
            boolean sitting = body instanceof Sittable sittable && sittable.isSitting()
                    || body instanceof Fox fox && fox.isSitting();
            boolean flying = body instanceof Flying || body instanceof Parrot || body instanceof Bat
                    || body instanceof Allay || body instanceof Vex;
            PetAnimation pose = VisualPose.select(mode, sitting, body.isInWater(), body.isOnGround(), flying,
                    body.getVelocity().getY(), speed, type.appearance().runSpeed(), previous == null ? null : previous.pose);
            if (body instanceof Cat && body.isSneaking()
                    && (pose == PetAnimation.WALK || pose == PetAnimation.RUN)) pose = PetAnimation.CROUCH;
            runtime.visual().update(body, type, pose);
            if (runtime.visual().holdsMovement(body)) net.tfminecraft.companionpets.integration.PetMotion.stop(body);
            boolean shaking = body instanceof Wolf wolf && net.tfminecraft.companionpets.integration.WolfShake.shaking(wolf);
            if (shaking && (previous == null || !previous.shaking)) {
                runtime.visual().play(body, type, "SHAKE");
            }
            if (shaking && runtime.visual().attached(body)) {
                // The hidden vanilla wolf cannot render its client-side water droplets.
                body.getWorld().spawnParticle(org.bukkit.Particle.SPLASH,
                        at.clone().add(0, body.getHeight() * 0.55, 0), 8,
                        body.getWidth() * 0.55, body.getHeight() * 0.25, body.getWidth() * 0.55, 0.08);
            }
            samples.put(id, new Sample(at, body.getTicksLived(), pose, shaking));
        }
        samples.keySet().retainAll(loaded);
        runtime.visual().retain(loaded);
    }

    private record Sample(Location at, int ticks, PetAnimation pose, boolean shaking) { }
}
