package net.tfminecraft.companionpets.config;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

/** Optional spontaneous actions, independent of taught tricks and essential care. */
public enum PetBehavior {
    GREETING, GREETING_CIRCLES, GREETING_APPROACH, GREETING_JUMPS, GREETING_TAIL_WAG,
    GREETING_MEOWS, TOY_ANTICIPATION, TOY_VOCALIZING, TOY_JUMPS, TOY_TAIL_WAG, FETCH, CAT_PLAY, ROAM,
    SOCIAL_GREETING, SOCIAL_TAIL_WAG, SOCIAL_JUMPS, SOCIAL_VOCALIZING,
    SOCIAL_SNIFF, SOCIAL_CHASE, SOCIAL_PROTEST, AFFECTION, AFFECTION_JUMPS, BELLY_RUB, MISCHIEF,
    DIG_GIFTS, RECOGNIZE_CARERS, PET_FRIENDSHIPS;

    public String id() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    public static Set<PetBehavior> defaults(EntityType entity) {
        var result = EnumSet.of(GREETING, GREETING_APPROACH, SOCIAL_GREETING, SOCIAL_SNIFF,
                AFFECTION, RECOGNIZE_CARERS, PET_FRIENDSHIPS);
        if (entity == null) return Set.copyOf(result);
        switch (entity) {
            case WOLF -> result.addAll(EnumSet.of(GREETING_CIRCLES, GREETING_JUMPS,
                    GREETING_TAIL_WAG, TOY_ANTICIPATION, TOY_VOCALIZING, TOY_JUMPS, TOY_TAIL_WAG, FETCH, SOCIAL_CHASE,
                    SOCIAL_PROTEST, SOCIAL_TAIL_WAG, SOCIAL_JUMPS, SOCIAL_VOCALIZING,
                    AFFECTION_JUMPS, BELLY_RUB, MISCHIEF, DIG_GIFTS));
            case CAT -> result.addAll(EnumSet.of(GREETING_CIRCLES, GREETING_MEOWS,
                    TOY_ANTICIPATION, TOY_VOCALIZING, FETCH, CAT_PLAY, SOCIAL_CHASE, SOCIAL_PROTEST, SOCIAL_VOCALIZING, BELLY_RUB));
            case FOX -> result.addAll(EnumSet.of(GREETING_CIRCLES, TOY_ANTICIPATION, TOY_VOCALIZING,
                    FETCH, SOCIAL_CHASE, SOCIAL_PROTEST, SOCIAL_VOCALIZING, DIG_GIFTS));
            case FROG -> result.addAll(EnumSet.of(GREETING_JUMPS, TOY_ANTICIPATION, TOY_VOCALIZING, TOY_JUMPS, FETCH, SOCIAL_JUMPS, SOCIAL_VOCALIZING));
            default -> { }
        }
        return Set.copyOf(result);
    }

    public static Set<PetBehavior> read(ConfigurationSection section, EntityType entity, Logger logger) {
        return read(section, defaults(entity), logger);
    }

    public static Set<PetBehavior> read(ConfigurationSection section, Set<PetBehavior> inherited, Logger logger) {
        return PetLists.read(section, "behaviors", inherited,
                id -> valueOf(id.replace('-', '_').toUpperCase(Locale.ROOT)), logger);
    }
}
