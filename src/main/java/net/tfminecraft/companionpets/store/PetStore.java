package net.tfminecraft.companionpets.store;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final Map<UUID, Pet> pets = new LinkedHashMap<>();
    private final Map<String, UUID> kennels = new LinkedHashMap<>();
    private boolean loaded;

    public PetStore(JavaPlugin plugin) {
        this(new File(plugin.getDataFolder(), "pets.yml"), plugin.getLogger());
    }

    PetStore(File file, Logger logger) {
        this.file = file;
        this.backup = new File(file.getParentFile(), file.getName() + ".bak");
        this.logger = logger;
    }

    public boolean load() {
        loaded = false;
        if (!file.exists() && !backup.exists()) {
            pets.clear();
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
            kennels.clear();
            kennels.putAll(nextKennels);
            loaded = true;
            return true;
        } catch (IOException | InvalidConfigurationException | RuntimeException ex) {
            logger.log(Level.SEVERE, "Could not read " + source.getName(), ex);
            return false;
        }
    }

    public void save() {
        if (!loaded) {
            return;
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
        }
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
                pet.bindWord(String.valueOf(word), Trick.valueOf(String.valueOf(trick)));
            } catch (IllegalArgumentException ignored) {
                continue;
            }
        }
        ConfigurationSection progress = section.getConfigurationSection("progress");
        if (progress != null) {
            for (String trickId : progress.getKeys(false)) {
                try {
                    pet.progress(Trick.valueOf(trickId), progress.getDouble(trickId));
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
            }
        }
        return pet;
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
        return List.copyOf(pets.values());
    }

    public Pet get(UUID id) {
        return pets.get(id);
    }

    public void add(Pet pet) {
        pets.put(pet.id(), pet);
    }

    public void remove(UUID id) {
        pets.remove(id);
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
