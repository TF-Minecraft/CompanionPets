package net.tfminecraft.companionpets.visual;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.tfminecraft.companionpets.config.PetAppearance.Clip;
import net.tfminecraft.companionpets.config.PetBehavior;
import net.tfminecraft.companionpets.config.PetTypeDef;

/** Resolved model capabilities adapt gameplay to the available bones and clips. */
public record PetCapabilities(boolean modelAvailable, boolean tail, Set<String> clips,
        Map<PetAnimation, Clip> animations, Map<PetBehavior, String> disabledBehaviors) {
    private static final Set<PetBehavior> TAIL = EnumSet.of(PetBehavior.GREETING_TAIL_WAG,
            PetBehavior.TOY_TAIL_WAG, PetBehavior.SOCIAL_TAIL_WAG);

    public PetCapabilities {
        clips = Set.copyOf(clips);
        animations = Map.copyOf(animations);
        disabledBehaviors = Map.copyOf(disabledBehaviors);
    }

    public static PetCapabilities inspect(PetTypeDef type, PetVisual visual) {
        boolean modeled = type.appearance().modeled();
        boolean available = visual.modelAvailable(type);
        Set<String> clips = modeled && available ? visual.clips(type) : Set.of();
        var animations = type.appearance().availableClips(clips);
        boolean tail = modeled && available && visual.hasTail(type);
        Map<PetBehavior, String> disabled = new EnumMap<>(PetBehavior.class);
        if (modeled && !tail) for (PetBehavior behavior : TAIL) {
            if (type.behaves(behavior)) disabled.put(behavior, available ? "no tail bone" : "model unavailable");
        }
        if (type.behaves(PetBehavior.BELLY_RUB)) {
            var missing = new java.util.ArrayList<String>();
            for (PetAnimation animation : Set.of(PetAnimation.LIE_BACK, PetAnimation.BELLY_UP, PetAnimation.GET_UP))
                if (!animations.containsKey(animation)) missing.add(animation.name().toLowerCase(java.util.Locale.ROOT));
            missing.sort(String::compareTo);
            if (!missing.isEmpty()) disabled.put(PetBehavior.BELLY_RUB, "missing animations: " + String.join(", ", missing));
        }
        return new PetCapabilities(available, tail, clips, animations, disabled);
    }

    public Set<PetBehavior> behaviors(PetTypeDef type) {
        var result = EnumSet.noneOf(PetBehavior.class);
        result.addAll(type.behaviors());
        result.removeAll(disabledBehaviors.keySet());
        return Set.copyOf(result);
    }

    public Map<String, String> missingAnimations(PetTypeDef type) {
        Map<String, String> result = new LinkedHashMap<>();
        for (PetAnimation action : PetAnimation.values()) {
            Clip requested = type.appearance().animations().get(action);
            if (requested != null && !animations.containsKey(action))
                result.put(action.name().toLowerCase(java.util.Locale.ROOT), requested.name());
        }
        return Map.copyOf(result);
    }
}
