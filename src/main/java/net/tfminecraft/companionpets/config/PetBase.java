package net.tfminecraft.companionpets.config;

import org.bukkit.entity.EntityType;

/** Tameable alone includes mounts which have no native owner-follow goal. */
public final class PetBase {
    private PetBase() { }

    public static boolean supported(EntityType type) {
        return type == EntityType.WOLF || type == EntityType.CAT;
    }
}
