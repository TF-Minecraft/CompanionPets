package net.tfminecraft.companionpets.store;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetOrder;
import net.tfminecraft.companionpets.pet.PetPersonality;
import net.tfminecraft.companionpets.pet.PetSex;
import net.tfminecraft.companionpets.pet.Trick;

public final class PetStore {
    private final Logger logger;
    private final File file;
    private final File backup;
    private final File deletions;
    private final File session;
    private final Set<UUID> deleted = new LinkedHashSet<>();
    private final Map<UUID, Pet> pets = new LinkedHashMap<>();
    private List<Pet> snapshot;
    private final Map<String, UUID> kennels = new LinkedHashMap<>();
    private boolean loaded;
    private boolean deletionLogHealthy;
    private boolean sessionStarted;

    public PetStore(JavaPlugin plugin) {
        this(new File(plugin.getDataFolder(), "pets.yml"), plugin.getLogger());
    }

    PetStore(File file, Logger logger) {
        this.file = file;
        this.backup = new File(file.getParentFile(), file.getName() + ".bak");
        this.deletions = new File(file.getParentFile(), "pet-deletions.log");
        this.session = new File(file.getParentFile(), "pets-recovery-required");
        this.logger = logger;
    }

    public boolean load() {
        loaded = false;
        deletionLogHealthy = false;
        if (session.exists()) {
            logger.severe("Previous pet session did not commit a clean shutdown. Stop the server and reconcile pets.yml "
                    + "with pet-deletions.log and actual deaths before removing pets-recovery-required; saving is disabled");
            return false;
        }
        try {
            Set<UUID> nextDeleted = new LinkedHashSet<>();
            if (deletions.exists()) {
                String contents = Files.readString(deletions.toPath(), StandardCharsets.UTF_8);
                if (!contents.isEmpty() && !contents.endsWith("\n")) {
                    throw new IllegalArgumentException("Incomplete deletion log entry");
                }
                for (String line : Files.readAllLines(deletions.toPath(), StandardCharsets.UTF_8)) {
                    // A partial write must fail closed instead of forgetting a deletion.
                    if (!line.equals(UUID.fromString(line).toString())) throw new IllegalArgumentException("Invalid deletion ID");
                    nextDeleted.add(UUID.fromString(line));
                }
            }
            deleted.clear();
            deleted.addAll(nextDeleted);
            deletionLogHealthy = true;
        } catch (IOException | RuntimeException ex) {
            logger.log(Level.SEVERE, "Could not read pet-deletions.log; saving is disabled", ex);
            return false;
        }
        if (!file.exists() && !backup.exists() && !deletions.exists()) {
            pets.clear();
            snapshot = null;
            kennels.clear();
            loaded = true;
            return true;
        }
        if (file.exists() && read(file)) return true;
        logger.severe("Could not load pets.yml; saving is disabled. Restore pets.yml.bak manually with the server stopped, "
                + "after reconciling ownership changes and deleted pets in that older snapshot");
        return false;
    }

    private boolean read(File source) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(source);
            if (!yaml.contains("pets") && !yaml.contains("kennels")) {
                throw new IllegalArgumentException("Missing pets and kennels data");
            }
            Map<UUID, Pet> nextPets = new LinkedHashMap<>();
            Map<String, UUID> nextKennels = new LinkedHashMap<>();
            ConfigurationSection petSection = yaml.getConfigurationSection("pets");
            if (yaml.contains("pets") && petSection == null) throw new IllegalArgumentException("pets must be a section");
            if (petSection != null) {
                for (String id : petSection.getKeys(false)) {
                    Pet pet = readPet(UUID.fromString(id), petSection.getConfigurationSection(id));
                    if (pet == null) throw new IllegalArgumentException("Invalid pet record " + id);
                    nextPets.put(pet.id(), pet);
                }
            }
            if (yaml.contains("kennels") && !yaml.isList("kennels")) throw new IllegalArgumentException("kennels must be a list");
            for (Object entry : yaml.getList("kennels", List.of())) {
                if (!(entry instanceof Map<?, ?> row)) throw new IllegalArgumentException("Invalid kennel record");
                Object world = row.get("world");
                Object owner = row.get("owner");
                if (world == null || owner == null) throw new IllegalArgumentException("Invalid kennel owner or world");
                String key = kennelKey(
                        String.valueOf(world),
                        ((Number) row.get("x")).intValue(),
                        ((Number) row.get("y")).intValue(),
                        ((Number) row.get("z")).intValue());
                nextKennels.put(key, UUID.fromString(String.valueOf(owner)));
            }
            pets.clear();
            pets.putAll(nextPets);
            deleted.forEach(pets::remove);
            snapshot = null;
            kennels.clear();
            kennels.putAll(nextKennels);
            loaded = true;
            return true;
        } catch (IOException | InvalidConfigurationException | RuntimeException ex) {
            logger.log(Level.SEVERE, "Could not read " + source.getName(), ex);
            return false;
        }
    }

    public boolean save() {
        if (!loaded || !deletionLogHealthy) {
            return false;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", 1);
        for (Pet pet : pets.values()) {
            String path = "pets." + pet.id();
            yaml.set(path + ".owner", pet.ownerId().toString());
            yaml.set(path + ".type", pet.typeId());
            yaml.set(path + ".name", pet.name());
            yaml.set(path + ".sex", pet.sex().name());
            yaml.set(path + ".personality", pet.personality().name());
            yaml.set(path + ".born-at", pet.bornAt());
            yaml.set(path + ".last-owner-nearby-millis", pet.lastOwnerNearbyMillis());
            yaml.set(path + ".last-greeting-millis", pet.lastGreetingMillis());
            writeMemories(yaml, path + ".carers", pet.carers());
            writeMemories(yaml, path + ".friends", pet.friends());
            yaml.set(path + ".order", pet.order().name());
            yaml.set(path + ".staying", pet.staying());
            yaml.set(path + ".stored", pet.stored());
            yaml.set(path + ".world", pet.worldName());
            yaml.set(path + ".x", pet.x());
            yaml.set(path + ".y", pet.y());
            yaml.set(path + ".z", pet.z());
            yaml.set(path + ".yaw", pet.yaw());
            yaml.set(path + ".entity", pet.entityId() == null ? "" : pet.entityId().toString());
            for (Need need : Need.values()) {
                yaml.set(path + "." + need.name().toLowerCase(Locale.ROOT), pet.need(need));
            }
            yaml.set(path + ".bond", pet.bond());
            yaml.set(path + ".critical-millis", pet.criticalMillis());
            yaml.set(path + ".dirty-millis", pet.dirtyMillis());
            yaml.set(path + ".illness", pet.illness().name());
            yaml.set(path + ".treated", pet.treated());
            yaml.set(path + ".favorite-toy", pet.favoriteToy());
            yaml.set(path + ".carried-toy", pet.carriedToy());
            List<String> announced = new ArrayList<>();
            for (Need need : pet.announcedLowView()) {
                announced.add(need.name());
            }
            yaml.set(path + ".announced", announced);
            java.util.List<java.util.Map<String, Object>> wordRows = new ArrayList<>();
            for (Map.Entry<String, Trick> entry : pet.words().entrySet()) {
                java.util.Map<String, Object> row = new LinkedHashMap<>();
                row.put("word", entry.getKey());
                row.put("trick", entry.getValue().name());
                wordRows.add(row);
            }
            yaml.set(path + ".words", wordRows);
            for (Trick trick : pet.progressView().keySet()) {
                if (pet.progress(trick) > 0.0) {
                    yaml.set(path + ".progress." + trick.name(), pet.progress(trick));
                }
            }
        }
        java.util.List<java.util.Map<String, Object>> kennelRows = new ArrayList<>();
        for (Map.Entry<String, UUID> entry : kennels.entrySet()) {
            String[] parts = entry.getKey().split(",", 4);
            if (parts.length != 4) {
                continue;
            }
            java.util.Map<String, Object> row = new LinkedHashMap<>();
            row.put("world", parts[0]);
            row.put("x", Integer.parseInt(parts[1]));
            row.put("y", Integer.parseInt(parts[2]));
            row.put("z", Integer.parseInt(parts[3]));
            row.put("owner", entry.getValue().toString());
            kennelRows.add(row);
        }
        yaml.set("kennels", kennelRows);
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            yaml.save(temp);
            if (file.exists()) {
                File backupTemp = new File(file.getParentFile(), backup.getName() + ".tmp");
                Files.copy(file.toPath(), backupTemp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                replace(backupTemp, backup);
            }
            replace(temp, file);
            if (!backup.exists()) {
                File backupTemp = new File(file.getParentFile(), backup.getName() + ".tmp");
                Files.copy(file.toPath(), backupTemp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                replace(backupTemp, backup);
            }
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Could not save pets.yml", ex);
            return false;
        }
        return true;
    }

    /** Arm recovery protection before enabling any pet actions or observing deaths. */
    public boolean beginSession() {
        if (!loaded || !deletionLogHealthy || sessionStarted) return false;
        try {
            Files.createDirectories(session.toPath().getParent());
            try (FileChannel channel = FileChannel.open(session.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writeAndForce(channel, "Pet session active; reconcile terminal transitions if shutdown does not complete.\n");
            }
            sessionStarted = true;
            return true;
        } catch (IOException ex) {
            loaded = false;
            logger.log(Level.SEVERE, "Could not arm pet recovery protection; saving is disabled", ex);
            return false;
        }
    }

    public boolean close() {
        if (!save()) return false;
        if (sessionStarted) {
            try {
                Files.delete(session.toPath());
                sessionStarted = false;
            } catch (IOException ex) {
                logger.log(Level.SEVERE, "Could not confirm clean pet shutdown", ex);
                return false;
            }
        }
        return true;
    }

    private static void writeAndForce(FileChannel channel, String value) throws IOException {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(value);
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
    }

    public boolean canRestoreBodies() {
        return loaded && deletionLogHealthy;
    }

    public boolean isDeleted(UUID id) {
        return deleted.contains(id);
    }

    private static void replace(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Pet readPet(UUID id, ConfigurationSection section) {
        if (section == null || !section.contains("owner") || !section.contains("type")) {
            return null;
        }
        Pet pet = new Pet(
                id,
                UUID.fromString(section.getString("owner")),
                section.getString("type"),
                section.getString("name", "Mascota"),
                enumValue(PetSex.class, section.getString("sex"), PetSex.FEMALE));
        pet.bornAt(section.getLong("born-at", 0L));
        pet.lastOwnerNearbyMillis(section.getLong("last-owner-nearby-millis", 0L));
        pet.lastGreetingMillis(section.getLong("last-greeting-millis", 0L));
        readMemories(section.getConfigurationSection("carers"), pet.carers());
        readMemories(section.getConfigurationSection("friends"), pet.friends());
        pet.personality(enumValue(PetPersonality.class, section.getString("personality"), PetPersonality.forId(id)));
        pet.order(enumValue(PetOrder.class, section.getString("order"), PetOrder.FOLLOW));
        pet.staying(section.getBoolean("staying"));
        pet.stored(section.getBoolean("stored"));
        pet.place(
                section.getString("world", ""),
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z"),
                (float) section.getDouble("yaw"));
        String entity = section.getString("entity", "");
        if (!entity.isBlank()) {
            try {
                pet.entityId(UUID.fromString(entity));
            } catch (IllegalArgumentException ignored) {
                pet.entityId(null);
            }
        }
        for (Need need : Need.values()) {
            String key = need.name().toLowerCase(Locale.ROOT);
            if (section.contains(key)) {
                pet.need(need, section.getDouble(key));
            }
        }
        pet.bond(section.getDouble("bond"));
        pet.criticalMillis(section.getLong("critical-millis"));
        pet.dirtyMillis(section.getLong("dirty-millis"));
        pet.illness(enumValue(Illness.class, section.getString("illness"), Illness.NONE));
        pet.treated(section.getBoolean("treated"));
        pet.favoriteToy(blankToNull(section.getString("favorite-toy")));
        pet.carriedToy(blankToNull(section.getString("carried-toy")));
        EnumSet<Need> announced = EnumSet.noneOf(Need.class);
        for (String raw : section.getStringList("announced")) {
            try {
                announced.add(Need.valueOf(raw));
            } catch (IllegalArgumentException ignored) {
                continue;
            }
        }
        pet.announcedLow(announced);
        for (java.util.Map<?, ?> row : section.getMapList("words")) {
            Object word = row.get("word");
            Object trick = row.get("trick");
            if (word == null || trick == null) {
                continue;
            }
            try {
                Trick parsed = Trick.valueOf(String.valueOf(trick));
                if (!parsed.equals(Trick.SPIN)) pet.bindWord(String.valueOf(word), parsed);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
        }
        ConfigurationSection progress = section.getConfigurationSection("progress");
        if (progress != null) {
            for (String trickId : progress.getKeys(false)) {
                try {
                    Trick trick = Trick.valueOf(trickId);
                    if (trick.equals(Trick.SPIN)) continue;
                    pet.progress(trick, Math.max(pet.progress(trick), progress.getDouble(trickId)));
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
            }
        }
        // An earlier development build folded Come into Follow. Preserve its learned
        // literal word without treating every custom Follow word as a Come command.
        if (pet.trickFor("come") == Trick.FOLLOW) {
            pet.bindWord("come", Trick.COME);
            pet.progress(Trick.COME, Math.max(pet.progress(Trick.COME), pet.progress(Trick.FOLLOW)));
        }
        return pet;
    }

    private static void writeMemories(YamlConfiguration yaml, String path, net.tfminecraft.companionpets.pet.RelationshipMemory memory) {
        memory.entries().forEach((id, entry) -> {
            String at = path + "." + id;
            yaml.set(at + ".trust", entry.trust()); yaml.set(at + ".reinforced-at", entry.reinforcedAt());
            yaml.set(at + ".nearby-at", entry.nearbyAt()); yaml.set(at + ".greeted-at", entry.greetedAt());
        });
    }

    private static void readMemories(ConfigurationSection section, net.tfminecraft.companionpets.pet.RelationshipMemory memory) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                var entry = section.getConfigurationSection(key);
                if (entry == null) continue;
                memory.restore(UUID.fromString(key), new net.tfminecraft.companionpets.pet.RelationshipMemory.Memory(
                        entry.getDouble("trust"), entry.getLong("reinforced-at"), entry.getLong("nearby-at"), entry.getLong("greeted-at")));
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String raw, T fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    public Collection<Pet> all() {
        // Keep immutable iteration safe when callbacks add or remove pets, without
        // copying every saved record on each animation and behavior tick.
        if (snapshot == null) snapshot = List.copyOf(pets.values());
        return snapshot;
    }

    public Pet get(UUID id) {
        return pets.get(id);
    }

    public void add(Pet pet) {
        if (deleted.contains(pet.id())) throw new IllegalArgumentException("Cannot reuse a deleted pet ID");
        pets.put(pet.id(), pet);
        snapshot = null;
    }

    /** Persist a terminal transition before a caller removes its body. */
    public boolean remove(UUID id) {
        if (!loaded || !deletionLogHealthy) return false;
        if (!pets.containsKey(id)) return true;
        try {
            Files.createDirectories(deletions.toPath().getParent());
            try (FileChannel channel = FileChannel.open(deletions.toPath(), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                writeAndForce(channel, id + "\n");
            }
        } catch (IOException ex) {
            deletionLogHealthy = false;
            logger.log(Level.SEVERE, "Could not commit deletion for pet " + id
                    + "; body recovery and saving are disabled. The session guard requires operator reconciliation", ex);
            return false;
        }
        deleted.add(id);
        pets.remove(id);
        snapshot = null;
        return true;
    }

    public List<Pet> of(UUID ownerId) {
        List<Pet> owned = new ArrayList<>();
        for (Pet pet : pets.values()) {
            if (pet.ownerId().equals(ownerId)) {
                owned.add(pet);
            }
        }
        return owned;
    }

    public int countOut(UUID ownerId) {
        int count = 0;
        for (Pet pet : pets.values()) {
            if (pet.ownerId().equals(ownerId) && !pet.stored() && !pet.dead()) {
                count++;
            }
        }
        return count;
    }

    public int countStored(UUID ownerId) {
        int count = 0;
        for (Pet pet : pets.values()) {
            if (pet.ownerId().equals(ownerId) && pet.stored() && !pet.dead()) {
                count++;
            }
        }
        return count;
    }

    public Pet byEntity(UUID entityId) {
        if (entityId == null) {
            return null;
        }
        for (Pet pet : pets.values()) {
            if (entityId.equals(pet.entityId())) {
                return pet;
            }
        }
        return null;
    }

    public void kennel(String key, UUID ownerId) {
        kennels.put(key, ownerId);
    }

    public boolean removeKennel(String key) {
        return kennels.remove(key) != null;
    }

    public UUID kennelOwner(String key) {
        return kennels.get(key);
    }

    public static String kennelKey(String world, int x, int y, int z) {
        return world + "," + x + "," + y + "," + z;
    }
}
