package net.tfminecraft.companionpets.config;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import net.tfminecraft.companionpets.pet.Trick;

/** Resolves one species layer and one pet layer without modifying the source YAML. */
final class PetDefinitions {
    record Resolved(ConfigurationSection section, String species, Set<PetBehavior> behaviors, Set<Trick> tricks) { }
    private final Map<String, ConfigurationSection> species = new LinkedHashMap<>();
    private final Set<Trick> builtinTricks = new LinkedHashSet<>(List.of(Trick.values()));
    private final Map<Trick, CustomTrick> custom;
    private final Logger logger;

    PetDefinitions(ConfigurationSection definitions, Map<Trick, CustomTrick> custom, Logger logger) {
        this.custom = custom;
        this.logger = logger;
        builtin("dog", EntityType.WOLF);
        builtin("cat", EntityType.CAT);
        if (definitions != null) for (String id : definitions.getKeys(false)) {
            ConfigurationSection definition = definitions.getConfigurationSection(id);
            if (definition == null) {
                logger.warning("Species " + id + " must be a mapping; ignoring it");
                continue;
            }
            String key = id.toLowerCase(Locale.ROOT);
            ConfigurationSection base = new YamlConfiguration().createSection("species." + key);
            merge(base, species.get(key));
            try {
                merge(base, normalize(definition));
                species.put(key, base);
            } catch (IllegalArgumentException ex) {
                logger.warning("Ignoring species " + id + ": " + ex.getMessage());
            }
        }
    }

    private void builtin(String id, EntityType entity) {
        var section = new YamlConfiguration().createSection("species." + id);
        section.set("entity", entity.name());
        species.put(id, section);
    }

    Resolved resolve(ConfigurationSection pet) {
        String id = null;
        ConfigurationSection template = null;
        if (pet.contains("species")) {
            Object raw = pet.get("species");
            if (!(raw instanceof String name) || name.isBlank())
                throw new IllegalArgumentException("species must be a nonempty species ID");
            id = name.trim().toLowerCase(Locale.ROOT);
            template = species.get(id);
            if (template == null) throw new IllegalArgumentException("unknown species " + id);
        }
        ConfigurationSection effective = new YamlConfiguration().createSection("pets." + pet.getName());
        merge(effective, template);
        merge(effective, normalize(pet));
        EntityType entity = entity(effective, "WOLF");
        EntityType speciesEntity = template == null ? entity : entity(template, entity.name());
        Set<PetBehavior> behaviors = template == null ? PetBehavior.defaults(entity)
                : PetBehavior.read(template, speciesEntity, logger);
        Set<Trick> tricks = template == null ? builtinTricks : tricks(template, builtinTricks);
        return new Resolved(effective, id, PetBehavior.read(pet, behaviors, logger), tricks(pet, tricks));
    }

    private static EntityType entity(ConfigurationSection section, String fallback) {
        try {
            return EntityType.valueOf(section.getString("entity", fallback).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("entity is invalid");
        }
    }

    private Set<Trick> tricks(ConfigurationSection section, Set<Trick> inherited) {
        return PetLists.read(section, "tricks", inherited, id -> {
            Trick trick = Trick.valueOf(id);
            if (trick.equals(Trick.SPIN) || trick.kind() == Trick.Kind.CUSTOM && !custom.containsKey(trick))
                throw new IllegalArgumentException();
            return trick;
        }, logger);
    }

    private static ConfigurationSection normalize(ConfigurationSection source) {
        ConfigurationSection result = copy(source);
        if (source.contains("model")) {
            result.set("appearance.type", "modelengine");
            result.set("appearance.model", source.get("model"));
            ConfigurationSection appearance = source.getConfigurationSection("appearance");
            if (appearance != null) merge(result.getConfigurationSection("appearance"), appearance);
        }
        if (source.contains("animations")) {
            ConfigurationSection animations = source.getConfigurationSection("animations");
            if (animations == null) throw new IllegalArgumentException("animations must be a mapping");
            var clips = copy(animations);
            merge(clips, source.getConfigurationSection("appearance.animations"));
            result.set("appearance.animations", clips);
            result.set("animations", null);
        }
        if (source.contains("voice")) {
            Object voice = source.get("voice");
            if (Boolean.FALSE.equals(voice)) result.set("sounds", false);
            else {
                var sounds = new YamlConfiguration();
                if (voice instanceof String preset && !preset.isBlank()) sounds.set("preset", preset.trim());
                else if (voice instanceof ConfigurationSection mapping) merge(sounds, mapping);
                else throw new IllegalArgumentException("voice must be a preset, mapping or false");
                merge(sounds, source.getConfigurationSection("sounds"));
                result.set("sounds", sounds);
            }
            // The advanced legacy syntax wins when both are explicitly configured.
            if (source.contains("sounds") && !source.isConfigurationSection("sounds"))
                result.set("sounds", source.get("sounds"));
            else if (Boolean.FALSE.equals(voice) && source.isConfigurationSection("sounds"))
                result.set("sounds", copy(source.getConfigurationSection("sounds")));
            result.set("voice", null);
        }
        return result;
    }

    private static ConfigurationSection copy(ConfigurationSection source) {
        var result = new YamlConfiguration();
        merge(result, source);
        return result;
    }

    private static void merge(ConfigurationSection target, ConfigurationSection source) {
        if (source == null) return;
        for (String key : source.getKeys(false)) {
            ConfigurationSection mapping = source.getConfigurationSection(key);
            if (mapping == null) target.set(key, source.get(key));
            else {
                ConfigurationSection nested = target.getConfigurationSection(key);
                if (nested == null) nested = target.createSection(key);
                merge(nested, mapping);
            }
        }
    }
}
