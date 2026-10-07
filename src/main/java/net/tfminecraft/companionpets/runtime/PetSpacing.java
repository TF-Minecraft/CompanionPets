package net.tfminecraft.companionpets.runtime;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import net.tfminecraft.companionpets.pet.*;

/** Individual destinations avoid converging on the same player's coordinates. */
final class PetSpacing {
    /** Vertical reach of a standing spot; a pet more than this far above or below does not crowd it. */
    private static final double LEVEL = 1.5;
    private static final double MIN_CLEARANCE = 0.9;
    private PetSpacing() { }
    static Location toyFront(PetRuntime runtime, Pet pet, Player owner) {
        int slot = Math.max(0, formation(runtime, pet, owner).slot());
        double yaw = Math.toRadians(owner.getLocation().getYaw());
        double width = runtime.entity(pet) instanceof Mob body ? body.getWidth() : 0.6;
        double lateral = switch (slot % 3) { case 1 -> -1; case 2 -> 1; default -> 0; } * Math.max(1.35, width + 0.5);
        double forward = 2.4 + 1.4 * (slot / 3);
        Location center = owner.getLocation();
        for (double offset : new double[]{0, -0.35, 0.35}) {
            Location point = PetGreetings.safeGround(center.clone().add(
                    -Math.sin(yaw) * forward + Math.cos(yaw) * (lateral + offset), 0,
                    Math.cos(yaw) * forward + Math.sin(yaw) * (lateral + offset)));
            if (point != null && free(runtime, pet, point)) return point;
        }
        return null;
    }
    static boolean free(PetRuntime runtime, Pet self, Location at) {
        Mob body = runtime.entity(self) instanceof Mob mob ? mob : null;
        double width = body == null ? 0.6 : body.getWidth();
        // Only nearby bodies can crowd a spot. An entity's box reaches the query box whenever its centre is
        // within the clearance below, so a world query replaces scanning every pet on the server.
        double reach = Math.max(MIN_CLEARANCE, width * 0.5 + 0.25) + 0.01;
        for (Entity entity : at.getWorld().getNearbyEntities(at, reach, LEVEL + 0.01, reach)) {
            if (!(entity instanceof Mob other) || other == body
                    || Math.abs(other.getLocation().getY() - at.getY()) > LEVEL) continue;
            Pet pet = runtime.byEntity(other);
            if (pet == null || pet.id().equals(self.id()) || pet.stored() || pet.dead()
                    || !other.getUniqueId().equals(pet.entityId())) continue;
            double clearance = Math.max(MIN_CLEARANCE, (width + other.getWidth()) * 0.5 + 0.25);
            Location there = other.getLocation();
            double dx = there.getX() - at.getX(), dz = there.getZ() - at.getZ();
            if (dx * dx + dz * dz < clearance * clearance) return false;
        }
        return true;
    }
    private record Formation(int count, int slot) { }

    private static Formation formation(PetRuntime runtime, Pet pet, Player owner) {
        int count = 0, slot = 0;
        boolean included = false;
        for (Pet other : runtime.store().of(pet.ownerId())) {
            if (other.stored() || other.dead()
                    || other.activity() != Activity.TOY_FOCUS
                    || !(runtime.entity(other) instanceof Mob body) || !body.getWorld().equals(owner.getWorld())) continue;
            count++;
            if (other.id().compareTo(pet.id()) < 0) slot++;
            if (other == pet) included = true;
        }
        // Stable UUID ranks give the same slots without allocating and sorting a list.
        return new Formation(count, included ? slot : -1);
    }
}
