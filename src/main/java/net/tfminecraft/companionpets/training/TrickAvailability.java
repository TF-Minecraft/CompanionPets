package net.tfminecraft.companionpets.training;

import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.Trick;
import net.tfminecraft.companionpets.runtime.PetRuntime;

public final class TrickAvailability {
    private TrickAvailability() { }
    public static boolean allows(PetRuntime runtime, Pet pet, Trick trick) {
        var type = runtime.config().type(pet.typeId());
        if (type == null || !type.allowsTrick(trick)) return false;
        if (trick.kind() != Trick.Kind.CUSTOM) return true;
        var definition = runtime.config().customTrick(trick);
        return definition != null && (!definition.fallbackText().isBlank()
                || runtime.visual().hasClip(runtime.entity(pet), type, definition.animation()));
    }
    public static String name(PetRuntime runtime, Trick trick) {
        var definition = runtime.config().customTrick(trick);
        return definition == null ? net.tfminecraft.companionpets.text.PetTexts.trickName(trick) : definition.displayName();
    }
}
