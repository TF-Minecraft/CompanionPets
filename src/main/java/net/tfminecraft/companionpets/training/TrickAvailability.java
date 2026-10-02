package net.tfminecraft.companionpets.training;

import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.Trick;
import net.tfminecraft.companionpets.runtime.PetRuntime;

public final class TrickAvailability {
    private TrickAvailability() { }
    public static boolean allows(PetRuntime runtime, Pet pet, Trick trick) {
        if (trick == null || trick.equals(Trick.SPIN)) return false;
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

    public static java.util.List<Trick> ordered(PetRuntime runtime, Pet pet, java.util.Collection<Trick> tricks) {
        var rank = new java.util.HashMap<Trick, Integer>();
        var configured = runtime.config().tricks();
        for (int i = 0; i < configured.size(); i++) rank.put(configured.get(i), i);
        return tricks.stream().sorted(java.util.Comparator
                .comparingInt((Trick t) -> pet.progress(t) >= runtime.config().training().learnedAt() ? 0 : pet.progress(t) > 0 ? 1 : 2)
                .thenComparingInt(t -> rank.getOrDefault(t, Integer.MAX_VALUE)).thenComparing(Trick::name)).toList();
    }
}
