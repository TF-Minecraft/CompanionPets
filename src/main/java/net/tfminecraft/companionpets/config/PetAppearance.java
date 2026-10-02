package net.tfminecraft.companionpets.config;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;

import net.tfminecraft.companionpets.visual.PetAnimation;

public record PetAppearance(String model, double scale, double runSpeed,
                            Map<PetAnimation, Clip> animations) {
    public static final PetAppearance VANILLA = new PetAppearance(null, 1, 0.22, Map.of());

    public record Clip(String name, double speed, double blend) { }

    public boolean modeled() { return model != null; }

    /** Resolve optional clips against the actual blueprint, respecting overrides and disabled entries. */
    public Map<PetAnimation, Clip> availableClips(java.util.Set<?> available) {
        Map<PetAnimation, Clip> clips = new EnumMap<>(PetAnimation.class);
        for (var entry : animations.entrySet()) {
            if (available.contains(entry.getValue().name())) clips.put(entry.getKey(), entry.getValue());
        }
        Clip lie = animations.get(PetAnimation.LIE);
        if (!clips.containsKey(PetAnimation.LIE) && lie != null && lie.name().equals("lie")) {
            if (available.contains("lay")) {
                clips.put(PetAnimation.LIE, new Clip("lay", lie.speed(), lie.blend()));
            } else if (clips.containsKey(PetAnimation.SLEEP)) {
                clips.put(PetAnimation.LIE, clips.get(PetAnimation.SLEEP));
            }
        }
        return Map.copyOf(clips);
    }

    public static PetAppearance read(ConfigurationSection pet, String id, Logger logger) {
        ConfigurationSection section = pet.getConfigurationSection("appearance");
        if (section == null) {
            String legacy = pet.getString("model");
            if (legacy == null || legacy.isBlank()) return VANILLA;
            logger.warning("Pet " + id + ": move model to appearance.type: modelengine and appearance.model");
            return model(legacy.trim(), 1, 0.22, null);
        }
        String type = section.getString("type", "vanilla").trim().toLowerCase(Locale.ROOT);
        if (type.equals("vanilla")) return VANILLA;
        if (!type.equals("modelengine")) throw new IllegalArgumentException("unknown appearance.type " + type);
        String model = section.getString("model", "").trim();
        if (model.isEmpty()) throw new IllegalArgumentException("appearance.model is required for modelengine");
        return model(model, positive(section, "scale", 1), positive(section, "run-speed", 0.22),
                section.getConfigurationSection("animations"));
    }

    private static PetAppearance model(String model, double scale, double runSpeed, ConfigurationSection section) {
        Map<PetAnimation, Clip> clips = new EnumMap<>(PetAnimation.class);
        for (PetAnimation animation : PetAnimation.values()) {
            clips.put(animation, new Clip(animation.name().toLowerCase(Locale.ROOT), 1, 0.15));
        }
        if (section != null) {
            for (String key : section.getKeys(false)) {
                PetAnimation animation;
                try {
                    animation = PetAnimation.valueOf(key.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("unknown appearance animation " + key);
                }
                ConfigurationSection detail = section.getConfigurationSection(key);
                String name = detail == null ? section.getString(key, "").trim() : detail.getString("name", "").trim();
                if (name.isEmpty()) {
                    clips.remove(animation);
                } else {
                    double speed = detail == null ? 1 : positive(detail, "speed", 1);
                    double blend = detail == null ? 0.15 : detail.getDouble("blend", 0.15);
                    if (!Double.isFinite(blend) || blend < 0) throw new IllegalArgumentException(key + ".blend must be nonnegative");
                    clips.put(animation, new Clip(name, speed, blend));
                }
            }
        }
        return new PetAppearance(model, scale, runSpeed, Map.copyOf(clips));
    }

    private static double positive(ConfigurationSection section, String key, double fallback) {
        double value = section.getDouble(key, fallback);
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(key + " must be positive");
        return value;
    }
}
