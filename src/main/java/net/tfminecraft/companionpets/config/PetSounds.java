package net.tfminecraft.companionpets.config;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

/** Sound identity is independent of the entity supplying navigation and the visible model. */
public record PetSounds(Map<Event, Cue> cues, double ambientIntervalSeconds, boolean nativeSounds, String preset, float pitch) {
    private static final java.util.Set<String> PRESETS = java.util.Set.of("wolf", "cat", "parrot", "fox", "frog");
    public enum Origin { PRESET, GENERATED, NONE }
    public Origin origin() {
        return preset.equals("none") ? Origin.NONE : PRESETS.contains(preset) ? Origin.PRESET : Origin.GENERATED;
    }
    public enum Event { AMBIENT, HAPPY, HAPPY_QUIET, SAD, HURT, DEATH, GREETING, TOY, SOCIAL, PROTEST, EAT }
    public record Cue(List<String> sounds, float volume, float pitch, double minIntervalSeconds) {
        public Cue { sounds = List.copyOf(sounds); }
    }

    public PetSounds { cues = Map.copyOf(cues); }
    public Cue cue(Event event) { return cues.get(event); }

    public static PetSounds read(ConfigurationSection pet, EntityType identity, Logger logger) {
        if (Boolean.FALSE.equals(pet.get("sounds"))) return new PetSounds(Map.of(), 0, false, "none", 1);
        ConfigurationSection section = pet.getConfigurationSection("sounds");
        if (section == null && pet.contains("sounds")) {
            logger.warning("Pet " + pet.getName() + ": sounds must be a mapping or false; disabling sounds");
            return new PetSounds(Map.of(), 0, false, "none", 1);
        }
        String preset = section == null ? identity.name().toLowerCase(Locale.ROOT)
                : section.getString("preset", identity.name()).trim().toLowerCase(Locale.ROOT);
        Map<Event, Cue> cues = defaults(preset, logger);
        double interval = section == null ? 0 : number(section, "ambient-interval-seconds", 25, 0, 3600, logger);
        if (section != null) for (Event event : Event.values()) {
            String key = event.name().toLowerCase(Locale.ROOT).replace('_', '-');
            if (!section.contains(key)) continue;
            Object raw = section.get(key);
            if (Boolean.FALSE.equals(raw)) { cues.remove(event); continue; }
            ConfigurationSection entry = section.getConfigurationSection(key);
            Object names = entry == null ? raw : entry.get("sounds");
            List<?> values = names instanceof List<?> list ? list : names instanceof String s ? List.of(s) : List.of();
            var sounds = new java.util.ArrayList<String>();
            for (Object value : values) {
                String parsed = value instanceof String s ? soundKey(s) : null;
                if (parsed == null) logger.warning("Pet " + pet.getName() + ": invalid " + key + " sound " + value);
                else sounds.add(parsed);
            }
            if (sounds.isEmpty()) { cues.remove(event); continue; }
            Cue previous = cues.get(event);
            cues.put(event, new Cue(sounds,
                    (float) number(entry, "volume", previous == null ? .8 : previous.volume(), 0, 4, logger),
                    (float) number(entry, "pitch", previous == null ? 1 : previous.pitch(), .1, 2, logger),
                    number(entry, "min-interval-seconds", 0, 0, 3600, logger)));
        }
        float pitch = (float) number(section, "pitch", 1, .1, 2, logger);
        if (pitch != 1) cues.replaceAll((event, cue) -> new Cue(cue.sounds(), cue.volume(),
                Math.max(.1f, Math.min(2, cue.pitch() * pitch)), cue.minIntervalSeconds()));
        return new PetSounds(cues, interval, section == null, preset, pitch);
    }

    private static String soundKey(String value) {
        String token = value.trim();
        if (token.isEmpty()) return null;
        // Enum-style names must actually exist; namespaced resource-pack keys are allowed.
        if (!token.contains(":")) {
            try {
                String key = Sound.valueOf(token.toUpperCase(Locale.ROOT)).getKey().toString();
                return registered(key) ? key : null;
            }
            catch (IllegalArgumentException ex) { return null; }
        }
        NamespacedKey key = NamespacedKey.fromString(token);
        return key == null || key.getNamespace().equals("minecraft") && !registered(key.toString()) ? null : key.toString();
    }

    private static boolean registered(String sound) {
        NamespacedKey key = NamespacedKey.fromString(sound);
        return key != null && Registry.SOUNDS.get(key) != null;
    }

    private static double number(ConfigurationSection section, String key, double fallback,
            double min, double max, Logger logger) {
        if (section == null || !section.contains(key)) return fallback;
        Object raw = section.get(key);
        if (raw instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() >= min && n.doubleValue() <= max)
            return n.doubleValue();
        logger.warning("Invalid " + section.getCurrentPath() + "." + key + "; using " + fallback);
        return fallback;
    }

    private static Map<Event, Cue> defaults(String preset, Logger logger) {
        return defaults(preset, logger, PetSounds::registered);
    }

    /** The predicate models the running server's registry, including gaps in vanilla entity sound names. */
    static Map<Event, Cue> defaults(String preset, Logger logger, java.util.function.Predicate<String> knownSound) {
        Map<Event, Cue> result = new EnumMap<>(Event.class);
        if (preset.equals("none")) return result;
        String entity = preset;
        if (!PRESETS.contains(preset)) {
            EntityType type = java.util.Arrays.stream(EntityType.values())
                    .filter(value -> value != EntityType.UNKNOWN)
                    .filter(value -> value.name().equalsIgnoreCase(preset) || value.getKey().getKey().equals(preset))
                    .findFirst().orElse(null);
            if (type == null) {
                logger.warning("Unknown vanilla entity for pet voice " + preset + "; no voice sounds generated");
                return result;
            }
            entity = type.getKey().getKey();
        }
        String ambient = "minecraft:entity." + entity + ".ambient";
        put(result, Event.AMBIENT, ambient, .6f, 1);
        put(result, Event.GREETING, ambient, preset.equals("cat") ? .8f : .9f, preset.equals("cat") ? 1 : 1.1f);
        put(result, Event.TOY, ambient, .4f, 1.05f);
        put(result, Event.HAPPY, preset.equals("cat") ? "minecraft:entity.cat.purreow" : ambient, .9f, 1.1f);
        String quiet = switch (preset) {
            case "wolf" -> "minecraft:entity.wolf.pant";
            case "cat" -> "minecraft:entity.cat.purr";
            case "fox" -> "minecraft:entity.fox.sniff";
            default -> ambient;
        };
        String sad = switch (preset) {
            case "wolf" -> "minecraft:entity.wolf.whine";
            case "cat" -> "minecraft:entity.cat.beg_for_food";
            case "fox" -> "minecraft:entity.fox.sniff";
            default -> ambient;
        };
        String protest = switch (preset) {
            case "wolf" -> "minecraft:entity.wolf.growl";
            case "cat" -> "minecraft:entity.cat.hiss";
            case "fox" -> "minecraft:entity.fox.aggro";
            default -> ambient;
        };
        put(result, Event.HAPPY_QUIET, quiet, .9f, 1.1f);
        put(result, Event.SAD, sad, .8f, .9f);
        put(result, Event.SOCIAL, preset.equals("wolf") ? sad : ambient, .3f, 1.1f);
        put(result, Event.PROTEST, protest, .45f, .9f);
        put(result, Event.HURT, "minecraft:entity." + entity + ".hurt", 1, 1);
        put(result, Event.DEATH, "minecraft:entity." + entity + ".death", 1, 1);
        put(result, Event.EAT, "minecraft:entity.generic.eat", .8f, 1);
        var warned = new java.util.HashSet<String>();
        for (Event event : Event.values()) {
            Cue cue = result.get(event);
            String sound = cue.sounds().getFirst();
            if (knownSound.test(sound)) continue;
            boolean fallback = knownSound.test(ambient);
            if (warned.add(sound)) logger.warning("Pet voice " + preset + ": sound " + sound + " is absent from the registry; "
                    + (fallback ? "using " + ambient : "disabling affected events"));
            if (fallback) result.put(event, new Cue(List.of(ambient), cue.volume(), cue.pitch(), cue.minIntervalSeconds()));
            else result.remove(event);
        }
        if (result.keySet().stream().noneMatch(event -> event != Event.EAT)) {
            logger.warning("Pet voice " + preset + " has no valid entity sounds; disabling voice");
            result.clear();
        }
        return result;
    }

    private static void put(Map<Event, Cue> cues, Event event, String sound, float volume, float pitch) {
        cues.put(event, new Cue(List.of(sound), volume, pitch, 0));
    }
}
