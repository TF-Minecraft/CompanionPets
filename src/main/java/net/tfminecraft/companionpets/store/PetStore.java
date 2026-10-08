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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private record CachedRow(long revision, Map<String, Object> values) { }
    private final Map<UUID, CachedRow> cachedRows = new HashMap<>();
    private Index index;
    private final Runnable invalidate = () -> index = null;
    private final Map<String, UUID> kennels = new LinkedHashMap<>();
    private boolean loaded;
    private boolean deletionLogHealthy;
    private boolean sessionStarted;
    private final AtomicBoolean dirty = new AtomicBoolean();
    private ExecutorService writer;

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
        cachedRows.clear();
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
            index = null;
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
            pets.values().forEach(pet -> pet.indexChanged(invalidate));
            index = null;
            kennels.clear();
            kennels.putAll(nextKennels);
            loaded = true;
            return true;
        } catch (IOException | InvalidConfigurationException | RuntimeException ex) {
            logger.log(Level.SEVERE, "Could not read " + source.getName(), ex);
            return false;
        }
    }

    /** Marks pending changes for the next {@link #flush()}, so routine actions never wait for the disk. */
    public void requestSave() {
        dirty.set(true);
    }

    /**
     * Writes pending changes on the writer thread. Call it on the main thread, which owns
     * the pet records; the writer only serializes a private copy and never touches Bukkit.
     */
    public void flush() {
        if (!dirty.getAndSet(false)) return;
        Snapshot snapshot = snapshot();
        if (snapshot == null) return;
        try {
            writer().execute(() -> {
                if (!write(snapshot)) dirty.set(true);
            });
        } catch (RejectedExecutionException ex) {
            dirty.set(true);
        }
    }

    /** Whether changes are waiting for the next flush. */
    public boolean pending() {
        return dirty.get();
    }

    /** Waits for background writes already queued; for tests. */
    void awaitWrites() throws InterruptedException, ExecutionException {
        writer().submit(() -> { }).get();
    }

    /** Saves now and waits, for callers that must know the record reached the disk. */
    public boolean save() {
        Snapshot snapshot = snapshot();
        if (snapshot == null) return false;
        dirty.set(false);
        try {
            // The single writer keeps this after any queued background write, so older data never wins.
            if (writer().submit(() -> write(snapshot)).get()) return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | RejectedExecutionException ex) {
            logger.log(Level.SEVERE, "Could not save pets.yml", ex);
        }
        dirty.set(true);
        return false;
    }

    private ExecutorService writer() {
        if (writer == null || writer.isShutdown()) {
            writer = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "CompanionPets pets.yml writer");
                thread.setDaemon(true);
                return thread;
            });
        }
        return writer;
    }

    record Snapshot(Map<String, Map<String, Object>> pets, List<Map<String, Object>> kennels) { }

    Snapshot snapshot() {
        if (!loaded || !deletionLogHealthy) {
            return null;
        }
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Pet pet : pets.values()) {
            CachedRow cached = cachedRows.get(pet.id());
            if (cached == null || cached.revision() != pet.revision()) {
                cached = new CachedRow(pet.revision(), row(pet));
                cachedRows.put(pet.id(), cached);
            }
            rows.put(pet.id().toString(), cached.values());
        }
        List<Map<String, Object>> kennelRows = new ArrayList<>();
        for (Map.Entry<String, UUID> entry : kennels.entrySet()) {
            String[] parts = entry.getKey().split(",", -1);
            int coordinates = parts.length - 3;
            if (coordinates < 1) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("world", String.join(",", Arrays.copyOf(parts, coordinates)));
            try {
                row.put("x", Integer.parseInt(parts[coordinates]));
                row.put("y", Integer.parseInt(parts[coordinates + 1]));
                row.put("z", Integer.parseInt(parts[coordinates + 2]));
            } catch (NumberFormatException ex) {
                logger.warning("Skipping kennel with invalid coordinates: " + entry.getKey());
                continue;
            }
            row.put("owner", entry.getValue().toString());
            kennelRows.add(row);
        }
        return new Snapshot(Collections.unmodifiableMap(rows), kennelRows);
    }

    private static Map<String, Object> row(Pet pet) {
        Map<String, Object> row = new LinkedHashMap<>();
        put(row, "owner", pet.ownerId().toString());
        put(row, "type", pet.typeId());
        put(row, "name", pet.name());
        put(row, "sex", pet.sex().name());
        put(row, "personality", pet.personality().name());
        put(row, "born-at", pet.bornAt());
        put(row, "last-owner-nearby-millis", pet.lastOwnerNearbyMillis());
        put(row, "last-greeting-millis", pet.lastGreetingMillis());
        put(row, "carers", memories(pet.carers()));
        put(row, "friends", memories(pet.friends()));
        put(row, "order", pet.order().name());
        put(row, "staying", pet.staying());
        put(row, "stored", pet.stored());
        put(row, "world", pet.worldName());
        put(row, "x", pet.x());
        put(row, "y", pet.y());
        put(row, "z", pet.z());
        put(row, "yaw", pet.yaw());
        put(row, "entity", pet.entityId() == null ? "" : pet.entityId().toString());
        for (Need need : Need.values()) {
            put(row, need.name().toLowerCase(Locale.ROOT), pet.need(need));
        }
        put(row, "bond", pet.bond());
        put(row, "critical-millis", pet.criticalMillis());
        put(row, "dirty-millis", pet.dirtyMillis());
        put(row, "illness", pet.illness().name());
        put(row, "treated", pet.treated());
        put(row, "favorite-toy", pet.favoriteToy());
        put(row, "carried-toy", pet.carriedToy());
        List<String> announced = new ArrayList<>();
        for (Need need : pet.announcedLowView()) {
            announced.add(need.name());
        }
        put(row, "announced", List.copyOf(announced));
        List<Map<String, Object>> wordRows = new ArrayList<>();
        for (Map.Entry<String, Trick> entry : pet.words().entrySet()) {
            Map<String, Object> word = new LinkedHashMap<>();
            word.put("word", entry.getKey());
            word.put("trick", entry.getValue().name());
            wordRows.add(Collections.unmodifiableMap(word));
        }
        put(row, "words", List.copyOf(wordRows));
        Map<String, Object> progress = new LinkedHashMap<>();
        for (Trick trick : pet.progressView().keySet()) {
            if (pet.progress(trick) > 0.0) {
                progress.put(trick.name(), pet.progress(trick));
            }
        }
        put(row, "progress", Collections.unmodifiableMap(progress));
        return Collections.unmodifiableMap(row);
    }

    /** Mirrors YamlConfiguration.set: no key for null values or empty sections. */
    private static void put(Map<String, Object> row, String key, Object value) {
        if (value == null || value instanceof Map<?, ?> map && map.isEmpty()) return;
        row.put(key, value);
    }

    boolean write(Snapshot snapshot) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", 1);
        snapshot.pets().forEach((id, row) -> yaml.createSection("pets." + id, row));
        yaml.set("kennels", snapshot.kennels());
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
        } catch (IOException | SecurityException ex) {
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
        boolean saved = save();
        if (writer != null) {
            writer.shutdown();
            try {
                writer.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        if (!saved) return false;
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

    static void writeAndForce(FileChannel channel, String value) throws IOException {
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

    private static Map<String, Object> memories(net.tfminecraft.companionpets.pet.RelationshipMemory memory) {
        Map<String, Object> rows = new LinkedHashMap<>();
        memory.entries().forEach((id, entry) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("trust", entry.trust()); row.put("reinforced-at", entry.reinforcedAt());
            row.put("nearby-at", entry.nearbyAt()); row.put("greeted-at", entry.greetedAt());
            rows.put(id.toString(), Collections.unmodifiableMap(row));
        });
        return Collections.unmodifiableMap(rows);
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
        return index().all();
    }

    /** Pets outside the Pet House and alive. Behavior ticks skip saved records entirely. */
    public Collection<Pet> active() {
        return index().active();
    }

    public Pet get(UUID id) {
        return pets.get(id);
    }

    public void add(Pet pet) {
        if (deleted.contains(pet.id())) throw new IllegalArgumentException("Cannot reuse a deleted pet ID");
        Pet previous = pets.put(pet.id(), pet);
        cachedRows.remove(pet.id());
        if (previous != null && previous != pet) previous.indexChanged(null);
        pet.indexChanged(invalidate);
        index = null;
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
        pets.remove(id).indexChanged(null);
        cachedRows.remove(id);
        index = null;
        return true;
    }

    public List<Pet> of(UUID ownerId) {
        return new ArrayList<>(index().owners().getOrDefault(ownerId, List.of()));
    }

    public int countOut(UUID ownerId) {
        int count = 0;
        for (Pet pet : index().owners().getOrDefault(ownerId, List.of())) {
            if (!pet.stored() && !pet.dead()) {
                count++;
            }
        }
        return count;
    }

    public int countPets(UUID ownerId) {
        int count = 0;
        for (Pet pet : index().owners().getOrDefault(ownerId, List.of())) {
            if (!pet.dead()) {
                count++;
            }
        }
        return count;
    }

    public Pet byEntity(UUID entityId) {
        return entityId == null ? null : index().bodies().get(entityId);
    }

    private Index index() {
        if (index != null) return index;
        List<Pet> all = List.copyOf(pets.values());
        List<Pet> active = new ArrayList<>();
        Map<UUID, List<Pet>> owners = new HashMap<>();
        Map<UUID, Pet> bodies = new HashMap<>();
        for (Pet pet : all) {
            if (!pet.stored() && !pet.dead()) active.add(pet);
            owners.computeIfAbsent(pet.ownerId(), id -> new ArrayList<>()).add(pet);
            if (pet.entityId() != null) bodies.putIfAbsent(pet.entityId(), pet);
        }
        index = new Index(all, List.copyOf(active), owners, bodies);
        return index;
    }

    /** Rebuilt only after a record joins, leaves, changes owner, body, storage or death. */
    private record Index(List<Pet> all, List<Pet> active, Map<UUID, List<Pet>> owners, Map<UUID, Pet> bodies) { }

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
