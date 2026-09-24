package net.tfminecraft.companionpets.text;

import java.util.Set;

import net.tfminecraft.companionpets.chat.SpokenOrder;
import net.tfminecraft.companionpets.pet.PetSex;

public final class Names {
    private static final Set<String> YES = Set.of("yes", "y", "yeah", "yep", "sure", "ok", "okay", "confirm");
    private static final Set<String> NO = Set.of("no", "nope", "cancel");
    private static final Set<String> MALE = Set.of("male", "boy", "m");
    private static final Set<String> FEMALE = Set.of("female", "girl", "f");

    private Names() {
    }

    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replace('§', ' ').trim().replaceAll("\\s+", " ");
        if (cleaned.length() > 32) {
            cleaned = cleaned.substring(0, 32).trim();
        }
        return cleaned;
    }

    public static boolean confirms(String text) {
        return YES.contains(SpokenOrder.key(text));
    }

    public static boolean cancels(String text) {
        return NO.contains(SpokenOrder.key(text));
    }

    public static PetSex sex(String text) {
        String key = SpokenOrder.key(text);
        if (MALE.contains(key)) {
            return PetSex.MALE;
        }
        if (FEMALE.contains(key)) {
            return PetSex.FEMALE;
        }
        return null;
    }
}
