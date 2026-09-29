package net.tfminecraft.companionpets.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import net.tfminecraft.companionpets.pet.Trick;

public record CustomTrick(String displayName, String animation, String fallbackText, double duration) {
    public static Map<Trick, CustomTrick> read(ConfigurationSection section, Logger logger) {
        Map<Trick, CustomTrick> result = new LinkedHashMap<>();
        if (section == null) return Map.of();
        for (String id : section.getKeys(false)) {
            try {
                Trick trick = Trick.valueOf(id);
                if (trick.kind() != Trick.Kind.CUSTOM || result.containsKey(trick) || id.equalsIgnoreCase("custom"))
                    throw new IllegalArgumentException("duplicate or reserved ID");
                ConfigurationSection entry = section.getConfigurationSection(id);
                if (entry == null) throw new IllegalArgumentException("expected a section");
                for (String key : new String[]{"display-name", "animation", "fallback-text"})
                    if (entry.contains(key) && !entry.isString(key)) throw new IllegalArgumentException(key + " must be text");
                String animation = entry.getString("animation", "").trim();
                String fallback = entry.getString("fallback-text", "").trim();
                String display = entry.getString("display-name", id).trim();
                double duration = entry.getDouble("duration", 2);
                if (display.isEmpty() || animation.isEmpty() && fallback.isEmpty())
                    throw new IllegalArgumentException("needs a name and animation or fallback-text");
                if (entry.contains("duration") && !(entry.get("duration") instanceof Number)
                        || !Double.isFinite(duration) || duration <= 0 || duration > 60)
                    throw new IllegalArgumentException("duration must be 0 < seconds <= 60");
                result.put(trick, new CustomTrick(display, animation, fallback, duration));
            } catch (IllegalArgumentException ex) { logger.warning("Skipping custom trick " + id + ": " + ex.getMessage()); }
        }
        return java.util.Collections.unmodifiableMap(result);
    }
}
