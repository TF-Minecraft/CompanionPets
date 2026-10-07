package net.tfminecraft.companionpets.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;

/** A list replaces inheritance; a mapping edits it, with removal taking precedence. */
final class PetLists {
    private PetLists() { }

    static <T> Set<T> read(ConfigurationSection section, String key, Set<T> inherited,
            Function<String, T> parse, Logger logger) {
        if (!section.contains(key)) return Set.copyOf(inherited);
        if (section.isList(key)) return entries(section.getList(key), key, parse, logger);
        ConfigurationSection patch = section.getConfigurationSection(key);
        if (patch == null) {
            logger.warning(section.getCurrentPath() + "." + key + " must be a list or add/remove mapping; disabling it");
            return Set.of();
        }
        var result = new LinkedHashSet<>(inherited);
        for (String option : patch.getKeys(false)) {
            if (!option.equals("add") && !option.equals("remove"))
                logger.warning("Unknown list adjustment " + patch.getCurrentPath() + "." + option);
        }
        for (String option : List.of("add", "remove")) {
            if (!patch.contains(option)) continue;
            if (!patch.isList(option)) {
                logger.warning(patch.getCurrentPath() + "." + option + " must be a list; ignoring adjustment");
                continue;
            }
            Set<T> values = entries(patch.getList(option), patch.getCurrentPath() + "." + option, parse, logger);
            if (option.equals("add")) result.addAll(values);
            else result.removeAll(values);
        }
        return Set.copyOf(result);
    }

    private static <T> Set<T> entries(List<?> values, String path, Function<String, T> parse, Logger logger) {
        var result = new LinkedHashSet<T>();
        for (Object value : values) {
            try {
                if (!(value instanceof String id)) throw new IllegalArgumentException();
                result.add(parse.apply(id.trim()));
            } catch (IllegalArgumentException ex) {
                logger.warning(path + ": skipping unknown ID " + value);
            }
        }
        return Set.copyOf(result);
    }
}
