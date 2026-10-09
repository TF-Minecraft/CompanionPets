package net.tfminecraft.companionpets.store;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;
import net.tfminecraft.companionpets.pet.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SnapshotReuseTest {
    private static final UUID ID = new UUID(0, 42), OWNER = new UUID(1, 1), NEXT_OWNER = new UUID(1, 2);
    private static final UUID CARER = new UUID(2, 1), FRIEND = new UUID(3, 1), BODY = new UUID(4, 1);
    @TempDir Path directory;
    private PetStore store;
    private Pet pet;

    @BeforeEach void setup() {
        Logger logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        store = new PetStore(directory.resolve("pets.yml").toFile(), logger); assertTrue(store.load());
        pet = new Pet(ID, OWNER, "wolf", "Toby", PetSex.MALE);
        pet.personality(PetPersonality.FRIENDLY); pet.place("world", 1, 64, 3, 4); pet.entityId(BODY);
        pet.bornAt(10); pet.bond(20); pet.favoriteToy("STICK"); pet.carriedToy("BONE");
        pet.bindWord("sit", Trick.SIT); pet.progress(Trick.SIT, 40); pet.markAnnouncedLow(Need.HUNGER);
        pet.carers().restore(CARER, new RelationshipMemory.Memory(20, 1, 2, 3));
        pet.friends().restore(FRIEND, new RelationshipMemory.Memory(20, 1, 2, 3));
        store.add(pet);
    }
    @AfterEach void close() { if (store != null) assertTrue(store.close()); }
    private Map<String, Object> row() { return store.snapshot().pets().get(ID.toString()); }
    private static Arguments change(String path, Consumer<Pet> mutation, Object expected) { return Arguments.of(path, mutation, expected); }

    private static Stream<Arguments> persistedChanges() {
        var cases = new ArrayList<>(List.of(
            change("owner", p -> p.ownerId(NEXT_OWNER), NEXT_OWNER.toString()),
            change("name", p -> p.name("Rex"), "Rex"),
            change("sex", p -> p.sex(PetSex.FEMALE), "FEMALE"),
            change("personality", p -> p.personality(PetPersonality.SHY), "SHY"),
            change("personality", p -> p.personality(null), PetPersonality.forId(ID).name()),
            change("born-at", p -> p.bornAt(123), 123L),
            change("last-owner-nearby-millis", p -> p.lastOwnerNearbyMillis(20), 20L),
            change("last-greeting-millis", p -> p.lastGreetingMillis(30), 30L),
            change("order", p -> p.order(PetOrder.SIT), "SIT"),
            change("staying", p -> p.staying(true), true),
            change("stored", p -> p.stored(true), true),
            change("world", p -> p.place("other", p.x(), p.y(), p.z(), p.yaw()), "other"),
            change("world", p -> p.place(null, p.x(), p.y(), p.z(), p.yaw()), ""),
            change("x", p -> p.place(p.worldName(), 2, p.y(), p.z(), p.yaw()), 2.0),
            change("y", p -> p.place(p.worldName(), p.x(), 70, p.z(), p.yaw()), 70.0),
            change("z", p -> p.place(p.worldName(), p.x(), p.y(), -2, p.yaw()), -2.0),
            change("yaw", p -> p.place(p.worldName(), p.x(), p.y(), p.z(), 90), 90f),
            change("entity", p -> p.entityId(FRIEND), FRIEND.toString()),
            change("entity", p -> p.entityId(null), ""),
            change("bond", p -> p.bond(41), 41.0),
            change("critical-millis", p -> p.criticalMillis(10), 10L),
            change("critical-millis", p -> p.addCriticalMillis(11), 11L),
            change("dirty-millis", p -> p.dirtyMillis(10), 10L),
            change("dirty-millis", p -> p.addDirtyMillis(11), 11L),
            change("illness", p -> p.illness(Illness.SICK), "SICK"),
            change("treated", p -> p.treated(true), true),
            change("favorite-toy", p -> p.favoriteToy("BONE"), "BONE"),
            change("favorite-toy", p -> p.favoriteToy(null), null),
            change("carried-toy", p -> p.carriedToy("STICK"), "STICK"),
            change("carried-toy", p -> p.carriedToy(" "), null),
            change("announced", p -> p.announcedLow(EnumSet.of(Need.MOOD)), List.of("MOOD")),
            change("announced", p -> p.announcedLow((EnumSet<Need>) null), List.of()),
            change("announced", p -> p.markAnnouncedLow(Need.HEALTH), List.of("HUNGER", "HEALTH")),
            change("announced", p -> p.clearAnnouncedLow(Need.HUNGER), List.of()),
            change("words", p -> p.bindWord("sit", Trick.STAY), List.of(Map.of("word", "sit", "trick", "STAY"))),
            change("words", p -> p.bindWord("come", Trick.COME), List.of(Map.of("word", "sit", "trick", "SIT"), Map.of("word", "come", "trick", "COME"))),
            change("progress.SIT", p -> p.progress(Trick.SIT, 55), 55.0),
            change("progress", p -> p.progress(Trick.SIT, 0), null)
        ));
        for (Need need : Need.values()) cases.add(change(need.name().toLowerCase(Locale.ROOT), p -> p.need(need, 41), 41.0));
        for (boolean carers : new boolean[]{true, false}) {
            String key = carers ? "carers" : "friends"; UUID id = carers ? CARER : FRIEND;
            java.util.function.Function<Pet, RelationshipMemory> memory = carers ? Pet::carers : Pet::friends;
            cases.add(change(key + "." + id + ".trust", p -> memory.apply(p).reinforce(id, 5, 10, 0), 25.0));
            cases.add(change(key + "." + id + ".reinforced-at", p -> memory.apply(p).restore(id, new RelationshipMemory.Memory(20, 10, 2, 3)), 10L));
            cases.add(change(key + "." + id + ".nearby-at", p -> memory.apply(p).nearby(id, 10), 10L));
            cases.add(change(key + "." + id + ".greeted-at", p -> memory.apply(p).greeted(id, 10), 10L));
            cases.add(change(key, p -> memory.apply(p).clear(), null));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "persisted change {index}: {0}") @MethodSource("persistedChanges")
    void everyPersistedMutationRebuildsItsRow(String path, Consumer<Pet> mutation, Object expected) {
        var before = row(); long revision = pet.revision();
        mutation.accept(pet);
        var after = row();
        assertTrue(pet.revision() > revision);
        assertNotSame(before, after);
        assertEquals(expected, new YamlConfiguration().createSection("pet", after).get(path));
        assertSame(after, row());
    }

    @Test void persistedColumnInventoryIsCoveredAndIdentityAndTypeStayFixed() {
        var covered = new HashSet<String>();
        persistedChanges().forEach(change -> covered.add(((String) change.get()[0]).split("\\.")[0]));
        covered.add("type");
        assertEquals(covered, row().keySet());
        assertEquals("wolf", row().get("type"));
        assertEquals(Set.of(ID.toString()), store.snapshot().pets().keySet());
    }

    @Test void sameValuesAndRuntimeOnlyChangesReuseTheRow() {
        var before = row(); long revision = pet.revision();
        pet.ownerId(pet.ownerId()); pet.name(pet.name()); pet.sex(pet.sex()); pet.personality(pet.personality());
        pet.bornAt(pet.bornAt()); pet.lastOwnerNearbyMillis(-1); pet.lastGreetingMillis(-1);
        pet.order(pet.order()); pet.staying(pet.staying()); pet.stored(pet.stored()); pet.entityId(pet.entityId());
        pet.place(pet.worldName(), pet.x(), pet.y(), pet.z(), pet.yaw());
        for (Need need : Need.values()) pet.need(need, 101);
        pet.bond(pet.bond()); pet.criticalMillis(-1); pet.addCriticalMillis(0); pet.dirtyMillis(-1); pet.addDirtyMillis(0);
        pet.illness(null); pet.treated(pet.treated()); pet.favoriteToy(pet.favoriteToy()); pet.carriedToy(pet.carriedToy());
        pet.announcedLow(pet.announcedLowView()); pet.markAnnouncedLow(Need.HUNGER); pet.clearAnnouncedLow(Need.HEALTH);
        pet.announcedLowView().clear(); pet.bindWord(" SIT ", Trick.SIT); pet.progress(Trick.SIT, 40);
        pet.carers().restore(CARER, pet.carers().get(CARER)); pet.carers().nearby(CARER, 1); pet.carers().greeted(CARER, 1);
        pet.friends().restore(FRIEND, pet.friends().get(FRIEND)); pet.friends().nearby(FRIEND, 1); pet.friends().greeted(FRIEND, 1);
        pet.activity(Activity.ATTENDING); pet.listeningUntilMillis(10); pet.toyExcitedUntilMillis(10);
        pet.playUntilMillis(10); pet.forcedSitUntilMillis(10); pet.nextCryAtMillis(10); pet.nextCriticalSoundAtMillis(10);
        pet.pauseUntilMillis(10); pet.refuseRestUntilMillis(10); pet.socialTailHz(1); pet.clearRuntimeMotion();
        assertEquals(revision, pet.revision()); assertSame(before, row());
    }

    @Test void cachedRowsAndAllTheirCollectionsAreImmutable() {
        String kennel = PetStore.kennelKey("world", 1, 64, 3);
        store.kennel(kennel, OWNER);
        var snapshot = store.snapshot(); var row = snapshot.pets().get(ID.toString());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.pets().clear());
        assertThrows(UnsupportedOperationException.class, () -> row.put("name", "Changed"));
        for (String field : List.of("progress", "carers", "friends")) {
            var values = (Map<?, ?>) row.get(field);
            assertThrows(UnsupportedOperationException.class, values::clear);
            if (!field.equals("progress")) assertThrows(UnsupportedOperationException.class, ((Map<?, ?>) values.values().iterator().next())::clear);
        }
        for (String field : List.of("words", "announced")) assertThrows(UnsupportedOperationException.class, ((List<?>) row.get(field))::clear);
        assertThrows(UnsupportedOperationException.class, ((Map<?, ?>) ((List<?>) row.get("words")).getFirst())::clear);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.kennels().clear());
        var kennelRow = snapshot.kennels().getFirst();
        assertThrows(UnsupportedOperationException.class, () -> kennelRow.put("owner", NEXT_OWNER.toString()));
        assertThrows(UnsupportedOperationException.class, () -> kennelRow.put("x", 20));
        store.kennel(kennel, NEXT_OWNER);
        assertEquals(OWNER.toString(), kennelRow.get("owner"));
        assertEquals(1, kennelRow.get("x"));
        assertEquals(NEXT_OWNER.toString(), store.snapshot().kennels().getFirst().get("owner"));
    }

    @Test void replacingRemovingAndReloadingPetsForgetCachedRows() {
        var before = row();
        var replacement = new Pet(ID, NEXT_OWNER, "cat", "Replacement", PetSex.FEMALE);
        store.add(replacement); assertNotSame(before, row()); assertEquals("Replacement", row().get("name"));
        var replaced = row(); assertTrue(store.save()); assertTrue(store.load());
        assertNotSame(replaced, row()); assertEquals("Replacement", row().get("name"));
        assertTrue(store.remove(ID)); assertFalse(store.snapshot().pets().containsKey(ID.toString()));
    }

    @Test void changedRowsDoNotReplaceTheRowsOfOtherPets() {
        var other = new Pet(UUID.randomUUID(), OWNER, "cat", "Other", PetSex.FEMALE); store.add(other);
        var before = store.snapshot(); pet.name("Rex"); var after = store.snapshot();
        assertNotSame(before.pets().get(ID.toString()), after.pets().get(ID.toString()));
        assertSame(before.pets().get(other.id().toString()), after.pets().get(other.id().toString()));
        assertEquals("Toby", before.pets().get(ID.toString()).get("name"));
    }
}
