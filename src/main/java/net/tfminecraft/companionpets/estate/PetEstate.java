package net.tfminecraft.companionpets.estate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import net.tfminecraft.companionpets.ownership.DurableFiles;
import net.tfminecraft.companionpets.ownership.OwnershipMigration;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.runtime.PetActions;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.estate.*;

/** Transfers only owner envelopes; the pet and all of its learning and friendships survive. */
public final class PetEstate implements EstateParticipant {
    private final PetRuntime runtime;
    private final PetActions actions;
    private final Path journals;
    Predicate<EstateDecision> guard = Estates::guardHolds;
    Supplier<List<EstateDecision>> decided = Estates::decided;

    public PetEstate(PetRuntime runtime, PetActions actions, Path journals) {
        this.runtime = runtime; this.actions = actions; this.journals = journals;
    }
    public static void start(Plugin plugin, PetRuntime runtime, PetActions actions) {
        PetEstate estate = new PetEstate(runtime, actions, plugin.getDataFolder().toPath().resolve("estates"));
        estate.recover();
        Bukkit.getServicesManager().register(EstateParticipant.class, estate, plugin, ServicePriority.Normal);
        Bukkit.getScheduler().runTaskTimer(plugin, estate::reconcile, 1L, 72000L);
    }
    @Override public String id() { return "companionpets"; }
    @Override public Holdings describe(UUID character) {
        List<String> labels = new ArrayList<>();
        runtime.store().ofCharacter(character).forEach(pet -> labels.add(pet.name()));
        runtime.store().kennelCharacters().forEach((key, owner) -> { if (character.equals(owner)) labels.add(houseLabel(key, mainWorld.get())); });
        return labels.isEmpty() ? Holdings.NONE : new Holdings(Map.of("pets-and-houses", labels.size()), labels);
    }
    /** The first world's name, or null; a house elsewhere names its world. */
    static Supplier<String> mainWorld = () -> Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst().getName();
    /** {@code world,x,y,z} as "Pet house at x y z", adding the world when it is not {@code main}. */
    static String houseLabel(String key, String main) {
        String[] part = key.split(",");
        if (part.length < 4) return "Pet house " + key;
        // A world name may hold commas; the last three fields are the coordinates.
        int n = part.length;
        String world = String.join(",", java.util.Arrays.copyOf(part, n - 3));
        return "Pet house at " + part[n - 3] + " " + part[n - 2] + " " + part[n - 1] + (world.equals(main) ? "" : " (" + world + ")");
    }
    @Override public Outcome settle(EstateDecision decision) {
        if (runtime.store().persistenceConflict()) return Outcome.blocked("Pet source changed outside its current writer; review is required");
        if (!net.tfminecraft.companionpets.ownership.CharacterAccess.heirReady() || !runtime.store().ownershipReady())
            return Outcome.retry("Reviewed pet ownership and persistence are not ready");
        if (!guard.test(decision)) return Outcome.retry("The exact estate decision is not current");
        if (decision.property().character().isPresent() && runtime.store().hasUnassignedOwner(decision.testator().ownerId()))
            return Outcome.blocked("Legacy pet/house ownership for this account still needs its reviewed heir");
        try {
            Files.createDirectories(journals);
            List<String> moved = new ArrayList<>();
            Path file = journals.resolve(decision.deathId() + "-" + decision.revision() + ".yml");
            Map<Path, YamlConfiguration> history = new LinkedHashMap<>();
            Set<String> otherPets = new java.util.HashSet<>(), otherKennels = new java.util.HashSet<>();
            try (var files = Files.list(journals)) {
                for (Path older : files.filter(path -> path.toString().endsWith(".yml")).toList()) {
                    YamlConfiguration receipt = readPlan(older);
                    history.put(older, receipt);
                    if (!decision.deathId().toString().equals(receipt.getString("death")) && !receipt.getBoolean("complete")) {
                        hold(receipt);
                        otherPets.addAll(receipt.getStringList("pets"));
                        otherKennels.addAll(receipt.getStringList("kennels"));
                    }
                }
            }
            YamlConfiguration current = history.get(file);
            List<String> candidatePets = current == null ? runtime.store().ofCharacter(decision.testator().characterId()).stream()
                    .filter(pet -> pet.ownerId().equals(decision.testator().ownerId())).map(pet -> pet.id().toString()).toList()
                    : current.getStringList("pets");
            List<String> candidateKennels = current == null ? runtime.store().kennelCharacters().entrySet().stream()
                    .filter(entry -> decision.testator().characterId().equals(entry.getValue())).map(Map.Entry::getKey).toList()
                    : current.getStringList("kennels");
            if ((current == null || !current.getBoolean("complete")) && decision.property().character().isPresent()
                    && (!java.util.Collections.disjoint(candidatePets, otherPets)
                    || !java.util.Collections.disjoint(candidateKennels, otherKennels)))
                return Outcome.blocked("Another death's incomplete pet estate intent needs review before these holdings can move");
            for (var historical : history.entrySet()) {
                Path older = historical.getKey();
                YamlConfiguration receipt = historical.getValue();
                if (!decision.deathId().toString().equals(receipt.getString("death"))) continue;
                if (!decision.testator().ownerId().toString().equals(receipt.getString("from-account"))
                        || !decision.testator().characterId().toString().equals(receipt.getString("from-character")))
                    throw new InvalidIntent("Historical receipt testator conflicts with this death: " + older.getFileName());
                if (receipt.getBoolean("complete")) moved.addAll(receipt.getStringList("moved"));
                else if (!older.equals(file)) {
                    if (changed(receipt)) { hold(receipt); return Outcome.blocked("An earlier partial estate intent needs review: " + older.getFileName()); }
                    receipt.set("complete", true); receipt.set("obsolete", true);
                    DurableFiles.write(older, receipt.saveToString());
                }
            }
            if (decision.property().character().isEmpty()) return done(moved);
            if (completedConflict(decision.testator(), moved))
                return Outcome.blocked("A completed holding appears testator-owned again; review the source and receipts");
            YamlConfiguration plan;
            if (Files.exists(file)) {
                plan = readPlan(file);
                if (!matchesDecision(plan, decision)) {
                    hold(plan); return Outcome.blocked("Estate intent does not match the exact recipient/decision envelope");
                }
                if (plan.getBoolean("complete")) {
                    if (remaining(decision.testator())) return Outcome.blocked("Completed estate receipt conflicts with remaining testator property; review a new revision");
                    return done(moved);
                }
            } else {
                CharacterRef to = decision.property().character().orElseThrow();
                plan = new YamlConfiguration();
                plan.set("death", decision.deathId().toString()); plan.set("revision", decision.revision());
                plan.set("decision", decision.toString()); plan.set("complete", false);
                plan.set("from-account", decision.testator().ownerId().toString());
                plan.set("from-character", decision.testator().characterId().toString());
                plan.set("to-account", to.ownerId().toString()); plan.set("to-character", to.characterId().toString());
                plan.set("pets", candidatePets);
                plan.set("kennels", candidateKennels);
                DurableFiles.write(file, plan.saveToString());
            }
            if (!guard.test(decision)) return Outcome.retry("Estate changed before publication");
            UUID fromAccount = UUID.fromString(plan.getString("from-account"));
            UUID fromCharacter = UUID.fromString(plan.getString("from-character"));
            UUID toAccount = UUID.fromString(plan.getString("to-account"));
            UUID toCharacter = UUID.fromString(plan.getString("to-character"));
            Map<Pet, CharacterRef> before = new LinkedHashMap<>();
            Map<String, CharacterRef> housesBefore = new LinkedHashMap<>();
            List<String> thisMove = new ArrayList<>();
            for (String id : plan.getStringList("pets")) {
                Pet pet = runtime.store().get(UUID.fromString(id));
                if (pet == null || !(matches(pet, fromAccount, fromCharacter) || matches(pet, toAccount, toCharacter))) {
                    hold(plan); return Outcome.blocked("Pet changed or disappeared after estate planning: " + id);
                }
                before.put(pet, new CharacterRef(pet.ownerId(), pet.ownerCharacterId()));
                thisMove.add("pet:" + id);
            }
            for (String key : plan.getStringList("kennels")) {
                UUID account = runtime.store().kennelOwner(key), character = runtime.store().kennelCharacter(key);
                if (!((fromAccount.equals(account) && fromCharacter.equals(character)) || (toAccount.equals(account) && toCharacter.equals(character)))) {
                    hold(plan); return Outcome.blocked("Pet House changed after estate planning: " + key);
                }
                housesBefore.put(key, new CharacterRef(account, character)); thisMove.add("kennel:" + key);
            }
            before.keySet().forEach(pet -> {
                pet.ownershipHeld(true); actions.clearInteractions(pet);
                actions.releaseFetch(pet, null, false); runtime.sessions().clearPet(pet.id());
                pet.ownership(toAccount, toCharacter);
            });
            housesBefore.keySet().forEach(key -> { runtime.store().holdKennel(key, true); runtime.store().kennel(key, toAccount, toCharacter); });
            if (!guard.test(decision) || !runtime.store().saveOwnership()) {
                before.forEach((pet, owner) -> { pet.ownership(owner.ownerId(), owner.characterId()); pet.ownershipHeld(true); });
                housesBefore.forEach((key, owner) -> { runtime.store().kennel(key, owner.ownerId(), owner.characterId()); runtime.store().holdKennel(key, true); });
                return runtime.store().persistenceConflict() ? Outcome.blocked("Pet save changed outside the writer; preserve the intent and current file for review")
                        : Outcome.retry("Pet ownership could not be durably published");
            }
            plan.set("moved", thisMove); plan.set("complete", true);
            DurableFiles.write(file, plan.saveToString());
            before.keySet().forEach(pet -> {
                pet.ownershipHeld(false);
                var body = runtime.entity(pet); var type = runtime.config().type(pet.typeId());
                if (body != null) {
                    runtime.bodies().bindOwner(body, pet);
                    if (type != null) runtime.bodies().reattach(body, pet, type);
                }
            });
            housesBefore.keySet().forEach(key -> runtime.store().holdKennel(key, false));
            moved.addAll(thisMove);
            return done(moved);
        } catch (InvalidIntent | RuntimeException ex) {
            holdAll();
            runtime.plugin().getLogger().severe("Pet estate intent needs review: " + ex.getMessage());
            return Outcome.blocked("Pet estate intent needs review: " + ex.getMessage());
        } catch (IOException ex) {
            runtime.plugin().getLogger().warning("Pet estate held for retry: " + ex.getMessage());
            return Outcome.retry("Pet estate persistence failed: " + ex.getMessage());
        }
    }
    private static final class InvalidIntent extends IOException {
        InvalidIntent(String message) { super(message); }
    }
    private static YamlConfiguration readPlan(Path file) throws IOException {
        YamlConfiguration plan;
        try { plan = OwnershipMigration.read(file); }
        catch (IOException ex) {
            if (ex.getCause() instanceof org.bukkit.configuration.InvalidConfigurationException)
                throw new InvalidIntent("Unreadable journal: " + file.getFileName());
            throw ex;
        }
        for (String key : List.of("death", "from-account", "from-character", "to-account", "to-character")) {
            if (OwnershipMigration.uuid(plan.getString(key)) == null)
                throw new InvalidIntent("Invalid " + key + " in " + file.getFileName());
        }
        if (!(plan.get("revision") instanceof Integer || plan.get("revision") instanceof Long) || plan.getLong("revision") < 0
                || !(plan.get("complete") instanceof Boolean) || plan.getString("decision", "").isBlank())
            throw new InvalidIntent("Invalid decision envelope in " + file.getFileName());
        CharacterRef source = new CharacterRef(OwnershipMigration.uuid(plan.getString("from-account")), OwnershipMigration.uuid(plan.getString("from-character")));
        CharacterRef recipient = new CharacterRef(OwnershipMigration.uuid(plan.getString("to-account")), OwnershipMigration.uuid(plan.getString("to-character")));
        String captured = plan.getString("decision");
        if (!captured.startsWith("EstateDecision[deathId=" + plan.getString("death") + ", testator=" + source + ", diedAt=")
                || !captured.contains(", property=" + Recipient.character(recipient) + ", guildLocks=")
                || !captured.endsWith(", revision=" + plan.getLong("revision") + "]"))
            throw new InvalidIntent("Journal envelope conflicts with its captured decision: " + file.getFileName());
        if (!file.getFileName().toString().equals(plan.getString("death") + "-" + plan.getLong("revision") + ".yml"))
            throw new InvalidIntent("Journal path does not match its death/revision: " + file.getFileName());
        for (String key : List.of("pets", "kennels")) {
            if (!(plan.get(key) instanceof List<?> values) || values.stream().anyMatch(value -> !(value instanceof String))
                    || values.stream().distinct().count() != values.size())
                throw new InvalidIntent("Invalid " + key + " list in " + file.getFileName());
        }
        if (plan.getStringList("pets").stream().anyMatch(id -> OwnershipMigration.uuid(id) == null))
            throw new InvalidIntent("Invalid pet ID in " + file.getFileName());
        if (plan.getBoolean("complete")) {
            Object raw = plan.get("moved");
            List<String> moved = plan.getStringList("moved");
            List<String> expected = new ArrayList<>();
            plan.getStringList("pets").forEach(id -> expected.add("pet:" + id));
            plan.getStringList("kennels").forEach(key -> expected.add("kennel:" + key));
            if ((raw != null && (!(raw instanceof List<?> values) || values.stream().anyMatch(value -> !(value instanceof String))))
                    || moved.stream().distinct().count() != moved.size()
                    || (plan.getBoolean("obsolete") ? !moved.isEmpty() : raw == null || !Set.copyOf(moved).equals(Set.copyOf(expected))))
                throw new InvalidIntent("Completed receipt IDs conflict with its planned holdings: " + file.getFileName());
        }
        return plan;
    }
    private boolean completedConflict(CharacterRef owner, List<String> moved) {
        boolean conflict = false;
        for (Pet pet : runtime.store().ofCharacter(owner.characterId())) {
            if (owner.ownerId().equals(pet.ownerId()) && moved.contains("pet:" + pet.id())) {
                pet.ownershipHeld(true); conflict = true;
            }
        }
        for (var entry : runtime.store().kennelCharacters().entrySet()) {
            if (owner.characterId().equals(entry.getValue()) && owner.ownerId().equals(runtime.store().kennelOwner(entry.getKey()))
                    && moved.contains("kennel:" + entry.getKey())) {
                runtime.store().holdKennel(entry.getKey(), true); conflict = true;
            }
        }
        return conflict;
    }
    private boolean remaining(CharacterRef owner) {
        return runtime.store().ofCharacter(owner.characterId()).stream().anyMatch(pet -> owner.ownerId().equals(pet.ownerId()))
                || runtime.store().kennelCharacters().entrySet().stream().anyMatch(entry -> owner.characterId().equals(entry.getValue())
                        && owner.ownerId().equals(runtime.store().kennelOwner(entry.getKey())));
    }
    private static boolean matchesDecision(YamlConfiguration plan, EstateDecision decision) {
        CharacterRef recipient = decision.property().character().orElseThrow();
        return decision.toString().equals(plan.getString("decision"))
                && decision.deathId().toString().equals(plan.getString("death"))
                && decision.revision() == plan.getLong("revision")
                && decision.testator().ownerId().toString().equals(plan.getString("from-account"))
                && decision.testator().characterId().toString().equals(plan.getString("from-character"))
                && recipient.ownerId().toString().equals(plan.getString("to-account"))
                && recipient.characterId().toString().equals(plan.getString("to-character"));
    }
    private static boolean matches(Pet pet, UUID account, UUID character) {
        return account.equals(pet.ownerId()) && character.equals(pet.ownerCharacterId());
    }
    private boolean changed(YamlConfiguration plan) {
        UUID account = OwnershipMigration.uuid(plan.getString("from-account"));
        UUID character = OwnershipMigration.uuid(plan.getString("from-character"));
        return plan.getStringList("pets").stream().anyMatch(id -> {
            Pet pet = runtime.store().get(OwnershipMigration.uuid(id));
            return pet == null || !matches(pet, account, character);
        }) || plan.getStringList("kennels").stream().anyMatch(key -> !(account.equals(runtime.store().kennelOwner(key)) && character.equals(runtime.store().kennelCharacter(key))));
    }
    private void hold(YamlConfiguration plan) {
        plan.getStringList("kennels").forEach(key -> runtime.store().holdKennel(key, true));
        plan.getStringList("pets").forEach(id -> { Pet pet = runtime.store().get(OwnershipMigration.uuid(id)); if (pet != null) pet.ownershipHeld(true); });
    }
    private void holdAll() {
        runtime.store().all().forEach(pet -> pet.ownershipHeld(true));
        runtime.store().kennelCharacters().keySet().forEach(key -> runtime.store().holdKennel(key, true));
    }
    private static Outcome done(List<String> moved) {
        List<String> ids = moved.stream().distinct().sorted().toList();
        return Outcome.done(Map.of("pets-and-houses", ids.size()), ids);
    }
    private void recover() {
        if (!Files.isDirectory(journals)) return;
        try (var files = Files.list(journals)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".yml")).toList()) {
                YamlConfiguration plan = readPlan(file);
                if (!plan.getBoolean("complete")) hold(plan);
            }
        } catch (IOException | RuntimeException ex) {
            holdAll();
            runtime.plugin().getLogger().severe("Pet estate journals unreadable; ownership stays held: " + ex.getMessage());
        }
    }
    void reconcile() {
        for (EstateDecision decision : decided.get()) {
            if (guard.test(decision)) Estates.report(decision.deathId(), id(), decision.revision(), settle(decision));
        }
    }
}
