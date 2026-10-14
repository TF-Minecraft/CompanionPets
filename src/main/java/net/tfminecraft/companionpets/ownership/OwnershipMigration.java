package net.tfminecraft.companionpets.ownership;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Single-file expansion; immutable source and planned bytes precede publication. */
public final class OwnershipMigration {
    private final Path source;
    private final Path folder;
    private final Function<UUID, Optional<UUID>> heirs;

    public OwnershipMigration(Path source, Function<UUID, Optional<UUID>> heirs) {
        this.source = source;
        this.folder = source.getParent().resolve("ownership-migrations");
        this.heirs = heirs;
    }

    public void run() throws IOException {
        Files.createDirectories(folder);
        int next = 1;
        try (var entries = Files.list(folder)) {
            for (Path file : entries.filter(path -> path.getFileName().toString().matches("plan-[0-9]{4}.yml"))
                    .sorted().toList()) {
                YamlConfiguration plan = read(file);
                next = Math.max(next, Integer.parseInt(file.getFileName().toString().substring(5, 9)) + 1);
                if (!plan.getBoolean("complete")) publish(file, plan);
            }
        }
        if (!Files.exists(source)) return;
        String original = Files.readString(source);
        Map<String, Object> data = raw(original);
        boolean changed = false;
        if (data.get("pets") instanceof Map<?, ?> pets) {
            for (Object value : pets.values()) {
                if (!(value instanceof Map<?, ?> values)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> pet = (Map<String, Object>) values;
                changed |= owner(pet);
                changed |= carers(pet);
            }
        }
        if (data.get("kennels") instanceof List<?> kennels) {
            for (Object value : kennels) {
                if (value instanceof Map<?, ?> values) {
                    @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) values;
                    changed |= owner(row);
                }
            }
        }
        if (!Integer.valueOf(2).equals(data.get("identity-version"))) { data.put("identity-version", 2); changed = true; }
        if (!changed) return;
        String name = "plan-%04d".formatted(next);
        Path archive = folder.resolve(name + ".original.yml");
        if (Files.exists(archive) && !hash(Files.readString(archive)).equals(hash(original)))
            throw new IOException("BLOCKED: orphaned migration archive differs from source: " + archive);
        if (!Files.exists(archive)) DurableFiles.write(archive, original);
        if (!hash(Files.readString(archive)).equals(hash(original))) throw new IOException("Migration backup verification failed");
        YamlConfiguration plan = new YamlConfiguration();
        plan.set("before", hash(original));
        plan.set("after", hash(dump(data)));
        plan.set("planned", dump(data));
        plan.set("complete", false);
        Path file = folder.resolve(name + ".yml");
        DurableFiles.write(file, plan.saveToString());
        publish(file, plan);
    }

    private void publish(Path file, YamlConfiguration plan) throws IOException {
        if (!Files.exists(source)) throw new IOException("BLOCKED: migration source is missing");
        Path archive = file.resolveSibling(file.getFileName().toString().replace(".yml", ".original.yml"));
        if (!Files.exists(archive) || !hash(Files.readString(archive)).equals(plan.getString("before")))
            throw new IOException("BLOCKED: migration source archive is missing or corrupt");
        String current = hash(Files.readString(source));
        String planned = plan.getString("planned");
        if (planned == null || !hash(planned).equals(plan.getString("after")))
            throw new IOException("BLOCKED: migration planned bytes are corrupt");
        if (current.equals(plan.getString("before"))) DurableFiles.write(source, planned);
        else if (!current.equals(plan.getString("after")))
            throw new IOException("BLOCKED: migration source changed after planning; preserve it for review");
        plan.set("complete", true);
        DurableFiles.write(file, plan.saveToString());
    }

    private boolean owner(Map<String, Object> row) {
        if (row.containsKey("owner-character")) return false;
        UUID account = uuid(row.get("owner"));
        Optional<UUID> heir = account == null ? Optional.empty() : heirs.apply(account);
        if (heir.isEmpty()) return false;
        row.put("owner-character", heir.get().toString());
        return true;
    }

    private boolean carers(Map<String, Object> pet) {
        boolean changed = false;
        if (!Boolean.TRUE.equals(pet.get("carers-characters"))) {
            if (pet.containsKey("carers")) pet.put("legacy-carers", pet.remove("carers"));
            pet.put("carers-characters", true);
            changed = true;
        }
        if (!(pet.get("legacy-carers") instanceof Map<?, ?> original)) return changed;
        @SuppressWarnings("unchecked") Map<String, Object> legacy = (Map<String, Object>) original;
        Map<String, Object> current;
        if (pet.get("carers") instanceof Map<?, ?> existing) {
            @SuppressWarnings("unchecked") Map<String, Object> memory = (Map<String, Object>) existing;
            current = memory;
        } else current = new LinkedHashMap<>();
        for (String id : List.copyOf(legacy.keySet())) {
            UUID account = uuid(id);
            Optional<UUID> heir = account == null ? Optional.empty() : heirs.apply(account);
            if (heir.isEmpty()) continue;
            String target = heir.get().toString();
            Object memory = legacy.get(id);
            if (current.containsKey(target) && !java.util.Objects.equals(current.get(target), memory)) continue;
            current.put(target, memory); legacy.remove(id); changed = true;
        }
        if (!current.isEmpty()) pet.put("carers", current);
        return changed;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> raw(String text) throws IOException {
        try {
            var options = new org.yaml.snakeyaml.LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(Integer.MAX_VALUE);
            options.setCodePointLimit(Integer.MAX_VALUE);
            Object data = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(options)).load(text);
            if (!(data instanceof Map<?, ?>)) throw new IOException("Ownership document must be a mapping");
            Map<String, Object> document = (Map<String, Object>) data;
            validateVersion(document);
            return document;
        } catch (org.yaml.snakeyaml.error.YAMLException ex) { throw new IOException("Unreadable ownership document", ex); }
    }
    private static void validateVersion(Map<String, Object> document) throws IOException {
        if (!document.containsKey("identity-version")) return;
        Object value = document.get("identity-version");
        try {
            if (!(value instanceof Number)) throw new ArithmeticException("Identity version must be numeric");
            int version = new java.math.BigDecimal(value.toString()).intValueExact();
            if (version != 1 && version != 2) throw new ArithmeticException("Unsupported identity version");
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IOException("Unsupported pet ownership schema; saving refused", ex);
        }
    }
    public static String dump(Map<String, Object> data) { return new org.yaml.snakeyaml.Yaml().dump(data); }
    public static UUID uuid(Object value) {
        try { return value == null ? null : UUID.fromString(String.valueOf(value)); }
        catch (IllegalArgumentException ex) { return null; }
    }
    public static YamlConfiguration read(Path path) throws IOException { return parse(Files.readString(path)); }
    public static YamlConfiguration parse(String data) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString(data); }
        catch (InvalidConfigurationException ex) { throw new IOException("Unreadable ownership document", ex); }
        return yaml;
    }
    public static String hash(String data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
