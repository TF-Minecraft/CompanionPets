package net.tfminecraft.companionpets.training;

import java.util.Locale;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.Trick;

/** Grant configured foundations without deleting learning or rebinding existing words. */
public final class DefaultTricks {
    private DefaultTricks() { }

    public static boolean apply(CompanionConfig config, Pet pet) {
        var type = config.type(pet.typeId());
        if (type == null) return false;
        boolean changed = false;
        for (Trick trick : type.defaultTricks()) {
            if (pet.progress(trick) < 100) {
                pet.progress(trick, 100);
                changed = true;
            }
            String word = trick.name().toLowerCase(Locale.ROOT);
            if (pet.trickFor(word) == null) {
                pet.bindWord(word, trick);
                changed = true;
            }
        }
        return changed;
    }
}
