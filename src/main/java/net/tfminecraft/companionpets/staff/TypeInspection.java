package net.tfminecraft.companionpets.staff;

import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.bukkit.command.CommandSender;
import net.tfminecraft.companionpets.config.PetBehavior;
import net.tfminecraft.companionpets.config.PetSounds;
import net.tfminecraft.companionpets.pet.Trick;
import net.tfminecraft.companionpets.runtime.PetRuntime;

final class TypeInspection {
    private TypeInspection() { }

    static void show(PetRuntime runtime, CommandSender sender, String[] args) {
        StaffCommands.require(args.length == 2, "Usage: /companionpets inspect <configured-type>");
        var type = runtime.config().type(args[1]);
        StaffCommands.require(type != null, "Unknown configured pet type: " + args[1]);
        var capabilities = runtime.capabilities(type);
        sender.sendMessage("Pet type: " + type.id() + " | Species: " + (type.species() == null ? "legacy (entity defaults)" : type.species())
                + " | Body: " + type.entity());
        sender.sendMessage("Egg: " + type.egg() + " | Sex: " + type.sexMode().name().toLowerCase(Locale.ROOT)
                + " | Native combat: " + type.nativeCombat());
        sender.sendMessage("Voice: " + type.sounds().preset() + " | Pitch multiplier: " + type.sounds().pitch()
                + " | Native sounds: " + type.sounds().nativeSounds()
                + " | Ambient interval: " + type.sounds().ambientIntervalSeconds() + "s");
        for (PetSounds.Event event : PetSounds.Event.values()) {
            var cue = type.sounds().cue(event);
            sender.sendMessage("  " + event.name().toLowerCase(Locale.ROOT).replace('_', '-') + ": "
                    + (cue == null ? "disabled" : String.join(", ", cue.sounds()) + " (volume=" + cue.volume()
                    + ", pitch=" + cue.pitch() + ", cooldown=" + cue.minIntervalSeconds() + "s)"));
        }
        sender.sendMessage("Behaviors configured: " + ids(type.behaviors(), PetBehavior::id));
        sender.sendMessage("Behaviors active: " + ids(capabilities.behaviors(type), PetBehavior::id));
        capabilities.disabledBehaviors().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                sender.sendMessage("  Disabled " + entry.getKey().id() + ": " + entry.getValue()));
        sender.sendMessage("Tricks configured: " + ids(type.tricks(), t -> t.name().toLowerCase(Locale.ROOT)));
        sender.sendMessage("Default learned tricks: " + ids(type.defaultTricks(), t -> t.name().toLowerCase(Locale.ROOT)));
        for (Trick trick : type.tricks().stream().sorted(Comparator.comparing(Trick::name)).toList()) {
            var custom = runtime.config().customTrick(trick);
            if (custom != null && custom.fallbackText().isBlank() && !capabilities.clips().contains(custom.animation()))
                sender.sendMessage("  Unavailable trick " + trick.name().toLowerCase(Locale.ROOT) + ": missing animation " + custom.animation());
        }
        if (!type.appearance().modeled()) {
            sender.sendMessage("Appearance: vanilla | Model animations: not applicable");
            return;
        }
        sender.sendMessage("Model: " + type.appearance().model() + " | Scale: " + type.appearance().scale()
                + " | Available: " + capabilities.modelAvailable() + " | Tail bone: " + capabilities.tail());
        sender.sendMessage("Blueprint clips found: " + ids(capabilities.clips(), Function.identity()));
        capabilities.animations().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                sender.sendMessage("  Found " + entry.getKey().name().toLowerCase(Locale.ROOT) + " -> " + entry.getValue().name()
                        + " (speed=" + entry.getValue().speed() + ", blend=" + entry.getValue().blend() + ")"));
        capabilities.missingAnimations(type).entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                sender.sendMessage("  Missing " + entry.getKey() + " -> " + entry.getValue() + " (optional; fallback applies)"));
        for (var animation : net.tfminecraft.companionpets.visual.PetAnimation.values())
            if (!type.appearance().animations().containsKey(animation))
                sender.sendMessage("  Disabled animation: " + animation.name().toLowerCase(Locale.ROOT));
    }

    private static <T> String ids(Collection<T> values, Function<T, String> id) {
        return values.isEmpty() ? "none" : String.join(", ", values.stream().map(id).sorted().toList());
    }
}
