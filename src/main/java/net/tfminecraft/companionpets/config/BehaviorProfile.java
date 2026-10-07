package net.tfminecraft.companionpets.config;

import static net.tfminecraft.companionpets.config.PetBehavior.*;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.entity.EntityType;

/** Fixed characters; individual actions are internal and adapted to model capabilities. */
public enum BehaviorProfile {
    /** Exuberant: tail, jumps, circles and wiggles; digs and makes mischief. */
    DOG(EnumSet.of(GREETING_CIRCLES, GREETING_JUMPS, GREETING_TAIL_WAG, TOY_ANTICIPATION, TOY_VOCALIZING,
            TOY_JUMPS, TOY_TAIL_WAG, TOY_WIGGLE, FETCH, SOCIAL_CHASE, SOCIAL_PROTEST, SOCIAL_TAIL_WAG,
            SOCIAL_JUMPS, SOCIAL_VOCALIZING, AFFECTION_JUMPS, BELLY_RUB, MISCHIEF, DIG_GIFTS)),
    /** Restrained: no tail or happy jumps; chattier greetings and a stalk before an uncontested toy. */
    CAT(EnumSet.of(GREETING_CIRCLES, GREETING_MEOWS, TOY_ANTICIPATION, TOY_VOCALIZING, FETCH, CAT_PLAY,
            SOCIAL_CHASE, SOCIAL_PROTEST, SOCIAL_VOCALIZING, BELLY_RUB, MISCHIEF, DIG_GIFTS)),
    /** Neutral: only small hops and sounds, for animals without dog or cat traits. */
    BASIC(EnumSet.of(GREETING_JUMPS, TOY_ANTICIPATION, TOY_VOCALIZING, TOY_JUMPS, FETCH,
            SOCIAL_JUMPS, SOCIAL_VOCALIZING));

    /** Every companion greets, meets other pets, enjoys affection and remembers carers.
     * A holder class, because enum constructors run before the enum's own static fields. */
    private static final class Common {
        static final Set<PetBehavior> BEHAVIORS = EnumSet.of(GREETING, GREETING_APPROACH, SOCIAL_GREETING,
                SOCIAL_SNIFF, AFFECTION, RECOGNIZE_CARERS, PET_FRIENDSHIPS);
    }

    private final Set<PetBehavior> behaviors;
    BehaviorProfile(EnumSet<PetBehavior> own) {
        own.addAll(Common.BEHAVIORS);
        behaviors = Set.copyOf(own);
    }

    public Set<PetBehavior> behaviors() { return behaviors; }

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static BehaviorProfile forBody(EntityType body) { return body == EntityType.CAT ? CAT : DOG; }

    public static BehaviorProfile read(Object raw, EntityType body, Logger logger) {
        if (raw == null) return forBody(body);
        if (raw instanceof String name) {
            try { return valueOf(name.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { }
        }
        logger.warning("Unknown behavior profile " + raw + "; valid profiles: dog, cat, basic; using " + forBody(body).id());
        return forBody(body);
    }
}
