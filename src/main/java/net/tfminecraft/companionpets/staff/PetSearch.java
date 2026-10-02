package net.tfminecraft.companionpets.staff;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Tameable;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.runtime.PetRuntime;

/** Search loaded bodies and saved locations without loading chunks or respawning anything. */
final class PetSearch {
    record Result(Pet pet, UUID id, String description) { }

    static List<Result> find(PetRuntime runtime, UUID owner, UUID selected, String name) {
        Map<UUID, List<Entity>> bodies = new LinkedHashMap<>();
        for (var world : Bukkit.getWorlds()) for (Entity entity : world.getEntities()) {
            UUID id = runtime.bodies().readId(entity);
            if (id != null && entity.isValid() && !entity.isDead()) bodies.computeIfAbsent(id, k -> new ArrayList<>()).add(entity);
        }
        String filter = name.toLowerCase(Locale.ROOT);
        List<Result> result = new ArrayList<>();
        for (Pet pet : runtime.store().all()) {
            if (selected != null && !selected.equals(pet.id()) || owner != null && !owner.equals(pet.ownerId())
                    || !pet.name().toLowerCase(Locale.ROOT).contains(filter)) continue;
            var found = bodies.getOrDefault(pet.id(), List.of());
            String status;
            if (pet.stored()) status = found.isEmpty() ? "in Pet House" : "in Pet House; unexpected loaded body";
            else if (!found.isEmpty()) status = (found.size() > 1 ? "duplicate bodies (" + found.size() + ")" : "loaded body") + ": " + position(found.getFirst());
            else {
                var world = Bukkit.getWorld(pet.worldName());
                boolean loaded = world != null && world.isChunkLoaded(((int) Math.floor(pet.x())) >> 4, ((int) Math.floor(pet.z())) >> 4);
                boolean entitiesLoaded = loaded && world.getChunkAt(((int) Math.floor(pet.x())) >> 4, ((int) Math.floor(pet.z())) >> 4).isEntitiesLoaded();
                status = world == null ? "saved world unavailable" : entitiesLoaded ? "body missing in loaded chunk" : "body not loaded";
                status += "; last known: " + pet.worldName() + " " + Math.round(pet.x()) + ", " + Math.round(pet.y()) + ", " + Math.round(pet.z());
            }
            if (selected == null && owner == null && filter.isBlank() && !pet.dead() && (pet.stored() && found.isEmpty() || found.size() == 1 && !pet.stored())) continue;
            result.add(new Result(pet, pet.id(), pet.name() + " (" + pet.typeId() + "): " + status));
        }
        for (var entry : bodies.entrySet()) {
            if (runtime.store().get(entry.getKey()) != null || selected != null && !selected.equals(entry.getKey())) continue;
            for (Entity entity : entry.getValue()) {
                if (owner != null && !(entity instanceof Tameable tameable && tameable.getOwner() != null && owner.equals(tameable.getOwner().getUniqueId()))) continue;
                if (!filter.isBlank() && (entity.getCustomName() == null || !entity.getCustomName().toLowerCase(Locale.ROOT).contains(filter))) continue;
                result.add(new Result(null, entry.getKey(), "Unregistered pet " + (entity.getCustomName() == null ? entity.getType().name() : entity.getCustomName())
                        + (runtime.store().isDeleted(entry.getKey()) ? " [deleted identity]" : " [no saved record]") + ": " + position(entity)));
            }
        }
        return List.copyOf(result);
    }

    private static String position(Entity entity) {
        var at = entity.getLocation();
        return at.getWorld().getName() + " " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ();
    }
}
