package net.tfminecraft.companionpets.visual;

import org.bukkit.entity.Entity;
import java.util.Set;
import java.util.UUID;

import net.tfminecraft.companionpets.config.PetTypeDef;

public interface PetVisual {
    void apply(Entity entity, PetTypeDef type);

    default boolean play(Entity entity, PetTypeDef type, String action) { return false; }

    default void update(Entity entity, PetTypeDef type, PetAnimation pose) { }

    default boolean holdsMovement(Entity entity) { return false; }

    default boolean attached(Entity entity) { return false; }
    default boolean hasClip(Entity entity, PetTypeDef type, String clip) { return false; }
    default Set<String> clips(PetTypeDef type) { return Set.of(); }
    default boolean modelAvailable(PetTypeDef type) { return !type.appearance().modeled(); }
    default boolean playClip(Entity entity, PetTypeDef type, String clip, double duration) { return false; }
    default boolean belly(Entity entity) { return false; }
    default boolean startBelly(Entity entity, PetTypeDef type, long idleMillis) { return false; }
    default boolean rubBelly(Entity entity) { return false; }

    default void cancelAction(Entity entity) { }

    default void remove(Entity entity) { }

    /** Delete the body as well as its visual; this must not reveal a hidden vanilla body. */
    default void removeBody(Entity entity) {
        if (entity == null) return;
        try { remove(entity); }
        finally { entity.remove(); }
    }

    default void retain(Set<UUID> loaded) { }

    default void close() { }
}
