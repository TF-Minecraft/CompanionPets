package net.tfminecraft.companionpets.config;

import java.util.Locale;

/** Internal spontaneous actions. Configuration selects a BehaviorProfile, never these individually. */
public enum PetBehavior {
    GREETING, GREETING_CIRCLES, GREETING_APPROACH, GREETING_JUMPS, GREETING_TAIL_WAG,
    GREETING_MEOWS, TOY_ANTICIPATION, TOY_VOCALIZING, TOY_JUMPS, TOY_TAIL_WAG, TOY_WIGGLE, FETCH, CAT_PLAY,
    SOCIAL_GREETING, SOCIAL_TAIL_WAG, SOCIAL_JUMPS, SOCIAL_VOCALIZING,
    SOCIAL_SNIFF, SOCIAL_CHASE, SOCIAL_PROTEST, AFFECTION, AFFECTION_JUMPS, BELLY_RUB, MISCHIEF,
    DIG_GIFTS, RECOGNIZE_CARERS, PET_FRIENDSHIPS;

    public String id() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }
}
