package net.tfminecraft.companionpets.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.bukkit.configuration.file.YamlConfiguration;
import org.mockito.invocation.InvocationOnMock;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetOrder;
import net.tfminecraft.companionpets.pet.PetPersonality;
import net.tfminecraft.companionpets.pet.PetSex;
import net.tfminecraft.companionpets.pet.Trick;

class PetStoreTest {
    @TempDir Path directory;

    private PetStore store() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        return new PetStore(new File(directory.toFile(), "pets.yml"), logger);
    }

    @Test void iterationSnapshotsStaySafeAcrossAddReplaceRemoveAndReload() {
        var store = store(); assertTrue(store.load());
        var first = new Pet(UUID.randomUUID(), UUID.randomUUID(), "cat", "Luna", PetSex.FEMALE);
        store.add(first); var before = store.all();
        assertThrows(UnsupportedOperationException.class, () -> before.clear());
        var second = new Pet(UUID.randomUUID(), first.ownerId(), "cat", "Sol", PetSex.MALE);
        for (Pet pet : before) store.add(second);
        assertEquals(java.util.List.of(first), before); assertEquals(2, store.all().size());
        var replacement = new Pet(first.id(), first.ownerId(), "cat", "Luna II", PetSex.FEMALE);
        store.add(replacement);
        assertTrue(store.all().contains(replacement)); assertFalse(store.all().contains(first));
        var deleting = store.all();
        for (Pet pet : deleting) assertTrue(store.remove(pet.id()));
        assertEquals(2, deleting.size()); assertTrue(store.all().isEmpty());
        assertTrue(store.save()); assertTrue(store.load()); assertTrue(store.all().isEmpty());
        store.add(new Pet(UUID.randomUUID(), first.ownerId(), "cat", "New", PetSex.MALE));
        assertEquals(1, store.all().size()); assertTrue(store.load()); assertTrue(store.all().isEmpty());
    }

    @Test void pendingChangesAreWrittenInTheBackgroundLikeADirectSave() throws Exception {
        var store = store(); assertTrue(store.load());
        var pet = new Pet(UUID.randomUUID(), UUID.randomUUID(), "cat", "Luna", PetSex.FEMALE);
        pet.bindWord("sit", Trick.SIT); pet.progress(Trick.SIT, 40); pet.favoriteToy("STICK");
        pet.carers().restore(UUID.randomUUID(), new net.tfminecraft.companionpets.pet.RelationshipMemory.Memory(12, 1, 2, 3));
        store.add(pet);
        File file = new File(directory.toFile(), "pets.yml");
        store.flush(); store.awaitWrites();
        assertFalse(file.exists(), "Nothing is written until a save is requested");
        store.requestSave(); assertTrue(store.pending());
        store.flush(); assertFalse(store.pending()); store.awaitWrites();
        String background = Files.readString(file.toPath());
        assertTrue(store.save());
        assertEquals(background, Files.readString(file.toPath()), "Both paths write the same file");
        var reloaded = store(); assertTrue(reloaded.load());
        assertEquals(Trick.SIT, reloaded.get(pet.id()).trickFor("sit"));
        assertEquals(40, reloaded.get(pet.id()).progress(Trick.SIT));
        assertEquals("STICK", reloaded.get(pet.id()).favoriteToy());
        assertEquals(1, reloaded.get(pet.id()).carers().entries().size());
        assertTrue(store.close());
    }

    @Test void activeOwnerAndBodyLookupsFollowRecordChanges() {
        var store = store(); assertTrue(store.load());
        var owner = UUID.randomUUID(); var body = UUID.randomUUID();
        var out = new Pet(UUID.randomUUID(), owner, "cat", "Luna", PetSex.FEMALE);
        var saved = new Pet(UUID.randomUUID(), owner, "cat", "Sol", PetSex.MALE); saved.stored(true);
        store.add(out); store.add(saved);
        assertEquals(java.util.List.of(out), java.util.List.copyOf(store.active()));
        assertEquals(1, store.countOut(owner)); assertEquals(1, store.countStored(owner));
        out.entityId(body); assertSame(out, store.byEntity(body));
        out.entityId(null); assertNull(store.byEntity(body));
        out.stored(true); saved.stored(false);
        assertEquals(java.util.List.of(saved), java.util.List.copyOf(store.active()));
        var heir = UUID.randomUUID(); saved.ownerId(heir);
        assertEquals(java.util.List.of(out), store.of(owner)); assertEquals(java.util.List.of(saved), store.of(heir));
        saved.dead(true); assertTrue(store.active().isEmpty());
        saved.dead(false); saved.entityId(body); assertTrue(store.remove(saved.id()));
        assertNull(store.byEntity(body)); assertTrue(store.of(heir).isEmpty());
        saved.stored(true); assertTrue(store.active().isEmpty(), "Removed records no longer refresh the index");
    }

    @Test void carersAndPetFriendsSurviveRestartWithoutBecomingOwners() throws Exception {
        var first = store(); assertTrue(first.load());
        var pet = new Pet(UUID.randomUUID(), UUID.randomUUID(), "cat", "Luna", PetSex.FEMALE);
        var carer = UUID.randomUUID(); var friend = UUID.randomUUID();
        pet.carers().reinforce(carer, 20, 1000, 0); pet.carers().greeted(carer, 2000);
        pet.friends().reinforce(friend, 12, 3000, 0); first.add(pet); assertTrue(first.save());
        var restored = store(); assertTrue(restored.load()); var loaded = restored.get(pet.id());
        assertEquals(pet.ownerId(), loaded.ownerId()); assertTrue(loaded.carers().familiar(carer));
        assertEquals(pet.carers().entries(), loaded.carers().entries()); assertEquals(pet.friends().entries(), loaded.friends().entries());
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(directory.resolve("pets.yml").toFile());
        yaml.set("pets." + pet.id() + ".carers.bad-id.trust", 100);
        yaml.set("pets." + pet.id() + ".friends." + UUID.randomUUID(), "invalid"); yaml.save(directory.resolve("pets.yml").toFile());
        var invalid = store(); assertTrue(invalid.load()); assertEquals(1, invalid.get(pet.id()).carers().entries().size());
        assertEquals(1, invalid.get(pet.id()).friends().entries().size());
    }

    @Test void explicitStayAndLayPersistAsOrdersAcrossRestart() {
        var first = store(); assertTrue(first.load());
        for (PetOrder order : new PetOrder[]{PetOrder.STAY, PetOrder.LAY}) {
            var pet = new Pet(UUID.randomUUID(), UUID.randomUUID(), "wolf", order.name(), PetSex.MALE);
            pet.order(order); first.add(pet);
        }
        assertTrue(first.save()); var restored = store(); assertTrue(restored.load());
        assertEquals(java.util.Set.of(PetOrder.STAY, PetOrder.LAY), restored.all().stream().map(Pet::order).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void savesPetsAndKennelsAcrossRestart() {
        UUID owner = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        PetStore first = store();
        assertTrue(first.load());
        Pet pet = new Pet(id, owner, "beagle", "Luna", PetSex.FEMALE);
        pet.personality(PetPersonality.PLAYFUL);
        pet.bornAt(123456L);
        pet.lastOwnerNearbyMillis(123000L);
        pet.lastGreetingMillis(120000L);
        pet.order(PetOrder.SIT);
        pet.staying(true);
        pet.stored(true);
        pet.place("world", 1.5, 70, -3.5, 90);
        pet.entityId(UUID.randomUUID());
        pet.need(Need.HUNGER, 42);
        pet.bond(71);
        pet.criticalMillis(900);
        pet.dirtyMillis(800);
        pet.illness(Illness.SICK);
        pet.treated(true);
        pet.favoriteToy("STICK");
        pet.carriedToy("FEATHER");
        pet.announcedLow(EnumSet.of(Need.HUNGER));
        pet.bindWord("sit down", Trick.SIT);
        pet.progress(Trick.JUMP, 45);
        first.add(pet);
        String kennel = PetStore.kennelKey("world", 1, 70, -3);
        first.kennel(kennel, owner);
        first.save();

        PetStore second = store();
        assertTrue(second.load());
        Pet restored = second.get(id);
        assertNotNull(restored);
        assertEquals(owner, restored.ownerId());
        assertEquals("beagle", restored.typeId());
        assertEquals("Luna", restored.name());
        assertEquals(PetPersonality.PLAYFUL, restored.personality());
        assertEquals(123456L, restored.bornAt());
        assertEquals(123000L, restored.lastOwnerNearbyMillis());
        assertEquals(120000L, restored.lastGreetingMillis());
        assertEquals(PetOrder.SIT, restored.order());
        assertTrue(restored.staying());
        assertTrue(restored.stored());
        assertEquals("world", restored.worldName());
        assertEquals(1.5, restored.x());
        assertEquals(pet.entityId(), restored.entityId());
        assertEquals(42, restored.need(Need.HUNGER));
        assertEquals(71, restored.bond());
        assertEquals(900, restored.criticalMillis());
        assertEquals(800, restored.dirtyMillis());
        assertEquals(Illness.SICK, restored.illness());
        assertTrue(restored.treated());
        assertEquals("STICK", restored.favoriteToy());
        assertEquals("FEATHER", restored.carriedToy());
        assertTrue(restored.announcedLow(Need.HUNGER));
        assertEquals(Trick.SIT, restored.trickFor("sit down"));
        assertEquals(45, restored.progress(Trick.JUMP));
        assertEquals(owner, second.kennelOwner(kennel));
    }

    @Test
    void outsidePetKeepsItsIdentityAndLastPositionAcrossRepeatedRestarts() {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID entity = UUID.randomUUID();
        PetStore current = store();
        assertTrue(current.load());
        Pet pet = new Pet(id, owner, "wolf", "Outside", PetSex.MALE);
        pet.place("world", -17.25, 81, 35.5, 120);
        pet.entityId(entity);
        pet.bindWord("sit", Trick.SIT);
        pet.progress(Trick.SIT, 100);
        current.add(pet);
        for (int restart = 0; restart < 3; restart++) {
            current.save();
            current = store();
            assertTrue(current.load());
            Pet restored = current.get(id);
            assertNotNull(restored);
            assertFalse(restored.stored());
            assertEquals(owner, restored.ownerId());
            assertEquals(entity, restored.entityId());
            assertEquals("world", restored.worldName());
            assertEquals(-17.25, restored.x());
            assertEquals(81, restored.y());
            assertEquals(35.5, restored.z());
            assertEquals(120, restored.yaw());
            assertEquals(Trick.SIT, restored.trickFor("sit"));
            assertEquals(100, restored.progress(Trick.SIT));
        }
    }

    @Test
    void requiresOperatorRestoreAndPreservesBothFilesOnLoadFailure() throws Exception {
        UUID id = UUID.randomUUID();
        PetStore first = store();
        assertTrue(first.load());
        first.add(new Pet(id, UUID.randomUUID(), "wolf", "Rex", PetSex.MALE));
        first.save();
        Path primary = directory.resolve("pets.yml");
        Path backup = directory.resolve("pets.yml.bak");
        assertTrue(Files.exists(backup));
        String corrupt = "pets: [broken";
        Files.writeString(primary, corrupt);
        String savedBackup = Files.readString(backup);

        PetStore recovered = store();
        assertFalse(recovered.load());
        assertNull(recovered.get(id));
        recovered.save();
        assertEquals(corrupt, Files.readString(primary));
        assertEquals(savedBackup, Files.readString(backup));

        // An operator restores this snapshot after checking transfers and deletions.
        Files.copy(backup, primary, StandardCopyOption.REPLACE_EXISTING);
        assertNotNull(storePet(id));
        assertTrue(Files.readString(backup).contains("Rex"));
    }

    @Test
    void damagedPrimaryDoesNotRevertOwnershipFromAnOlderBackup() throws Exception {
        UUID id = UUID.randomUUID();
        UUID previousOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        PetStore current = store();
        assertTrue(current.load());
        Pet pet = new Pet(id, previousOwner, "wolf", "Rex", PetSex.MALE);
        current.add(pet);
        current.save();
        pet.ownerId(newOwner);
        current.save();
        assertTrue(Files.readString(directory.resolve("pets.yml.bak")).contains(previousOwner.toString()));
        Files.writeString(directory.resolve("pets.yml"), "pets: [broken");

        PetStore restarted = store();
        assertFalse(restarted.load());
        assertTrue(restarted.of(previousOwner).isEmpty());
        assertNull(restarted.get(id));
    }

    @Test
    void missingPrimaryDoesNotResurrectDeletedPetsOrStartWithEmptyData() throws Exception {
        UUID id = UUID.randomUUID();
        PetStore current = store();
        assertTrue(current.load());
        current.add(new Pet(id, UUID.randomUUID(), "wolf", "Rex", PetSex.MALE));
        current.save();
        current.remove(id);
        current.save();
        Path primary = directory.resolve("pets.yml");
        Path backup = directory.resolve("pets.yml.bak");
        String savedBackup = Files.readString(backup);
        assertTrue(savedBackup.contains(id.toString()));
        Files.delete(primary);

        PetStore restarted = store();
        assertFalse(restarted.load());
        assertNull(restarted.get(id));
        restarted.save();
        assertFalse(Files.exists(primary));
        assertEquals(savedBackup, Files.readString(backup));
    }

    @Test
    void backupKeepsPreviousCommittedSave() throws Exception {
        UUID id = UUID.randomUUID();
        PetStore current = store();
        assertTrue(current.load());
        Pet pet = new Pet(id, UUID.randomUUID(), "wolf", "Old name", PetSex.MALE);
        current.add(pet);
        current.save();
        pet.name("New name");
        current.save();

        assertTrue(Files.readString(directory.resolve("pets.yml")).contains("New name"));
        assertTrue(Files.readString(directory.resolve("pets.yml.bak")).contains("Old name"));
    }

    @Test
    void deletionSurvivesInterruptionBeforeSnapshotSaveAndOlderBackupRestore() throws Exception {
        UUID id = UUID.randomUUID();
        PetStore current = store();
        assertTrue(current.load());
        current.add(new Pet(id, UUID.randomUUID(), "wolf", "Rex", PetSex.MALE));
        assertTrue(current.save());
        String stalePrimary = Files.readString(directory.resolve("pets.yml"));

        assertTrue(current.remove(id));
        // Simulate interruption before the snapshot can commit the deletion.
        assertEquals(stalePrimary, Files.readString(directory.resolve("pets.yml")));
        PetStore restarted = store();
        assertTrue(restarted.load());
        assertNull(restarted.get(id));
        assertTrue(restarted.isDeleted(id));
        Files.copy(directory.resolve("pets.yml.bak"), directory.resolve("pets.yml"), StandardCopyOption.REPLACE_EXISTING);
        assertNull(storePet(id));
        assertThrows(IllegalArgumentException.class,
                () -> restarted.add(new Pet(id, UUID.randomUUID(), "wolf", "Rex", PetSex.MALE)));
    }

    @Test
    void snapshotBackupFailureKeepsDurableDeletionAndShutdownGuard() throws Exception {
        UUID id = UUID.randomUUID();
        PetStore current = store();
        assertTrue(current.load());
        assertTrue(current.beginSession());
        current.add(new Pet(id, UUID.randomUUID(), "wolf", "Rex", PetSex.MALE));
        assertTrue(current.save());
        String stalePrimary = Files.readString(directory.resolve("pets.yml"));
        Path blockedBackup = directory.resolve("pets.yml.bak.tmp");
        Files.createDirectory(blockedBackup);
        Files.writeString(blockedBackup.resolve("block"), "prevent replacement");

        assertTrue(current.remove(id));
        assertFalse(current.save());
        assertFalse(current.close());
        assertEquals(stalePrimary, Files.readString(directory.resolve("pets.yml")));
        assertTrue(Files.exists(directory.resolve("pets-recovery-required")));
        assertFalse(store().load());
        // After reconciliation, even the stale primary respects the durable log.
        Files.delete(directory.resolve("pets-recovery-required"));
        assertNull(storePet(id));
    }

    @Test
    void deletionLogFailureRefusesReleaseAndBlocksRecoveryAfterRestart() throws Exception {
        UUID id = UUID.randomUUID();
        PetStore current = store();
        assertTrue(current.load());
        assertTrue(current.beginSession());
        current.add(new Pet(id, UUID.randomUUID(), "wolf", "Rex", PetSex.MALE));
        assertTrue(current.save());
        Files.createDirectory(directory.resolve("pet-deletions.log"));

        assertFalse(current.remove(id));
        assertNotNull(current.get(id));
        assertFalse(current.canRestoreBodies());
        assertFalse(current.save());
        assertFalse(current.close());
        PetStore restarted = store();
        assertFalse(restarted.load());
        assertNull(restarted.get(id));
        assertTrue(Files.exists(directory.resolve("pets-recovery-required")));
    }

    @Test
    void interruptedSessionRequiresReconciliationEvenIfSaveDataIsValid() throws Exception {
        PetStore current = store();
        assertTrue(current.load());
        assertTrue(current.beginSession());
        assertTrue(current.save());
        String primary = Files.readString(directory.resolve("pets.yml"));

        PetStore restarted = store();
        assertFalse(restarted.load());
        assertFalse(restarted.save());
        assertFalse(restarted.close());
        assertEquals(primary, Files.readString(directory.resolve("pets.yml")));
        assertTrue(Files.exists(directory.resolve("pets-recovery-required")));
    }

    @Test
    void cleanSessionShutdownAllowsRepeatedRestarts() {
        UUID id = UUID.randomUUID();
        PetStore current = store();
        assertTrue(current.load());
        current.add(new Pet(id, UUID.randomUUID(), "wolf", "Rex", PetSex.MALE));
        for (int restart = 0; restart < 3; restart++) {
            assertTrue(current.beginSession());
            assertTrue(current.close());
            assertFalse(Files.exists(directory.resolve("pets-recovery-required")));
            current = store();
            assertTrue(current.load());
            assertNotNull(current.get(id));
        }
    }

    @Test
    void partialDeletionRecordFailsClosedWithoutOverwritingFiles() throws Exception {
        PetStore current = store();
        assertTrue(current.load());
        assertTrue(current.save());
        Path log = directory.resolve("pet-deletions.log");
        Files.writeString(log, UUID.randomUUID() + "\npartial-uuid");
        String primary = Files.readString(directory.resolve("pets.yml"));
        PetStore restarted = store();
        assertFalse(restarted.load());
        assertFalse(restarted.save());
        assertEquals(primary, Files.readString(directory.resolve("pets.yml")));
        assertTrue(Files.readString(log).endsWith("partial-uuid"));
    }

    @Test
    void cannotEnableRuntimeUnlessSessionGuardIsDurable() throws Exception {
        PetStore current = store();
        assertTrue(current.load());
        Files.createDirectory(directory.resolve("pets-recovery-required"));
        assertFalse(current.beginSession());
        assertFalse(current.save());
        assertFalse(current.canRestoreBodies());
        assertFalse(current.close());
        assertTrue(Files.exists(directory.resolve("pets-recovery-required")));
    }

    @Test
    void deletionWithoutFinalNewlineIsNotAcceptedForFurtherAppends() throws Exception {
        PetStore current = store();
        assertTrue(current.load());
        assertTrue(current.save());
        Files.writeString(directory.resolve("pet-deletions.log"), UUID.randomUUID().toString());
        assertFalse(store().load());
    }

    private Pet storePet(UUID id) {
        PetStore fresh = store();
        assertTrue(fresh.load());
        return fresh.get(id);
    }

    @Test
    void refusesToOverwriteMalformedRecordsWithoutValidBackup() throws Exception {
        Path primary = directory.resolve("pets.yml");
        String malformed = "pets:\n  invalid-id:\n    owner: invalid\n    type: wolf\n";
        Files.writeString(primary, malformed);
        PetStore store = store();
        assertFalse(store.load());
        store.save();
        assertEquals(malformed, Files.readString(primary));
    }

    @Test void kennelWorldNamesContainingCommasRoundTripWithoutBlockingPetSaves() {
        var current = store();
        assertTrue(current.load());
        var pet = new Pet(UUID.randomUUID(), UUID.randomUUID(), "cat", "Luna", PetSex.FEMALE);
        current.add(pet);
        String key = PetStore.kennelKey("world,chapter,two", -3, 65, 7);
        current.kennel(key, pet.ownerId());
        try {
            assertTrue(current.save(), "valid world names must not break every pet save");
            var restored = store();
            assertTrue(restored.load());
            assertEquals(pet.ownerId(), restored.kennelOwner(key));
            assertEquals("Luna", restored.get(pet.id()).name());
            assertTrue(restored.close());
        } finally {
            current.removeKennel(key);
            current.close();
        }
    }


    @Test void backgroundProviderFailureKeepsChangesPendingForRetry() throws Exception {
        var current = store();
        assertTrue(current.load());
        var pet = new Pet(UUID.randomUUID(), UUID.randomUUID(), "cat", "Pending", PetSex.FEMALE);
        current.add(pet);
        AtomicBoolean unavailable = new AtomicBoolean(true);
        writerWithMoves(current, call -> {
            if (unavailable.get()) throw new SecurityException("filesystem provider denied replacement");
            return call.callRealMethod();
        });
        try {
            current.requestSave();
            current.flush();
            current.awaitWrites();
            assertTrue(current.pending(), "a failed asynchronous write must remain queued for retry");
            assertFalse(Files.exists(directory.resolve("pets.yml")));
            unavailable.set(false);
            current.flush();
            current.awaitWrites();
            assertFalse(current.pending());
            assertEquals("Pending", storePet(pet.id()).name());
        } finally {
            unavailable.set(false);
            current.close();
        }
    }

    @Test void atomicMoveFallbackCommitsSnapshotsAndKeepsThePreviousBackup() throws Exception {
        var current = store();
        assertTrue(current.load());
        var pet = new Pet(UUID.randomUUID(), UUID.randomUUID(), "cat", "First", PetSex.FEMALE);
        current.add(pet);
        AtomicInteger fallbacks = new AtomicInteger();
        writerWithMoves(current, call -> {
            CopyOption[] options = (CopyOption[]) call.getRawArguments()[2];
            if (java.util.Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                fallbacks.incrementAndGet();
                throw new AtomicMoveNotSupportedException(call.getArgument(0).toString(), call.getArgument(1).toString(), "provider has no atomic replace");
            }
            return call.callRealMethod();
        });
        try {
            assertTrue(current.save());
            String first = Files.readString(directory.resolve("pets.yml"));
            assertEquals(first, Files.readString(directory.resolve("pets.yml.bak")));
            pet.name("Second");
            assertTrue(current.save());
            assertEquals(4, fallbacks.get());
            assertEquals(first, Files.readString(directory.resolve("pets.yml.bak")));
            assertEquals("Second", storePet(pet.id()).name());
        } finally { current.close(); }
    }

    @Test void interruptedShutdownPreservesTheRecoveryGuardUntilAConfirmedSave() throws Exception {
        var current = store();
        assertTrue(current.load());
        assertTrue(current.beginSession());
        var pet = new Pet(UUID.randomUUID(), UUID.randomUUID(), "wolf", "Interrupted", PetSex.MALE);
        current.add(pet);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch allowWrite = new CountDownLatch(1);
        ExecutorService worker = writerWithMoves(current, call -> {
            writing.countDown();
            if (!allowWrite.await(10, TimeUnit.SECONDS)) throw new AssertionError("write was never released");
            return call.callRealMethod();
        });
        AtomicBoolean closed = new AtomicBoolean(true);
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            closed.set(current.close());
            interrupted.set(Thread.currentThread().isInterrupted());
        }, "interrupted pet shutdown caller");
        try {
            caller.start();
            assertTrue(writing.await(10, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(5_000);
            assertFalse(caller.isAlive());
            assertFalse(closed.get());
            assertTrue(interrupted.get(), "shutdown must preserve the interruption for its caller");
            assertTrue(current.pending());
            assertTrue(Files.isRegularFile(directory.resolve("pets-recovery-required")));
            assertFalse(store().load());
        } finally {
            allowWrite.countDown();
            caller.join(5_000);
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(current.close());
        }
        assertFalse(Files.exists(directory.resolve("pets-recovery-required")));
        assertEquals("Interrupted", storePet(pet.id()).name());
    }

    @Test void recoveryMarkerDeletionFailureKeepsRestartBlockedDespiteSavedPets() throws Exception {
        var current = store();
        assertTrue(current.load());
        assertTrue(current.beginSession());
        var pet = new Pet(UUID.randomUUID(), UUID.randomUUID(), "wolf", "Saved", PetSex.MALE);
        current.add(pet);
        Path marker = directory.resolve("pets-recovery-required");
        Files.delete(marker);
        Files.createDirectory(marker);
        Files.writeString(marker.resolve("blocked"), "prevent directory deletion");
        assertFalse(current.close());
        assertTrue(Files.readString(directory.resolve("pets.yml")).contains("Saved"));
        assertTrue(Files.isDirectory(marker));
        assertFalse(store().load());
        Files.delete(marker.resolve("blocked"));
        Files.delete(marker);
        Files.writeString(marker, "restored guard after filesystem repair");
        assertTrue(current.close());
        assertFalse(Files.exists(marker));
        assertEquals("Saved", storePet(pet.id()).name());
    }

    @Test void rejectedExecutorSubmissionKeepsBothSavePathsRetryable() throws Exception {
        for (boolean background : new boolean[]{false, true}) {
            Path folder = directory.resolve(background ? "background" : "immediate");
            var current = new PetStore(folder.resolve("pets.yml").toFile(), quietLogger());
            assertTrue(current.load());
            var pet = new Pet(UUID.randomUUID(), UUID.randomUUID(), "cat", "Retry", PetSex.FEMALE);
            current.add(pet);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            ExecutorService boundary = mock(ExecutorService.class, delegatesTo(executor));
            AtomicBoolean stopAfterCheck = new AtomicBoolean(true);
            // A valid executor race: shutdown occurs after isShutdown reports false,
            // before the caller submits. Both resource operations still use a real executor.
            doAnswer(call -> {
                boolean stopped = executor.isShutdown();
                if (stopAfterCheck.getAndSet(false)) executor.shutdown();
                return stopped;
            }).when(boundary).isShutdown();
            setWriter(current, boundary);
            try {
                if (background) {
                    current.requestSave();
                    current.flush();
                } else assertFalse(current.save());
                assertTrue(current.pending());
                assertFalse(Files.exists(folder.resolve("pets.yml")));
                current.flush();
                current.awaitWrites();
                assertFalse(current.pending());
                assertTrue(Files.readString(folder.resolve("pets.yml")).contains("Retry"));
            } finally { current.close(); executor.shutdownNow(); }
        }
    }

    @Test void unexpectedWriterFailureIsReportedAndLeavesTheSnapshotRetryable() throws Exception {
        List<String> warnings = new ArrayList<>();
        Logger logger = quietLogger();
        AtomicBoolean appenderFails = new AtomicBoolean(true);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) {
                if (appenderFails.getAndSet(false)) throw new IllegalStateException("logging appender failed");
                warnings.add(record.getMessage());
            }
            @Override public void flush() { }
            @Override public void close() { }
        });
        var current = new PetStore(directory.resolve("pets.yml").toFile(), logger);
        assertTrue(current.load());
        var pet = new Pet(UUID.randomUUID(), UUID.randomUUID(), "cat", "Retained", PetSex.FEMALE);
        current.add(pet);
        Path temp = directory.resolve("pets.yml.tmp");
        Files.createDirectory(temp);
        Files.writeString(temp.resolve("blocked"), "force the real write to fail");
        try {
            assertFalse(current.save());
            assertTrue(current.pending());
            assertEquals(List.of("Could not save pets.yml"), warnings);
            assertFalse(Files.exists(directory.resolve("pets.yml")));
            Files.delete(temp.resolve("blocked"));
            Files.delete(temp);
            assertTrue(current.save());
            assertEquals("Retained", storePet(pet.id()).name());
        } finally { current.close(); }
    }

    @Test void unreadableRecordRootsAndMissingRequiredFieldsNeverOverwriteFiles() throws Exception {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        for (String malformed : List.of("version: 1\n", "pets: {}\nkennels: wrong\n",
                "pets:\n  " + id + ": missing-section\n",
                "pets:\n  " + id + ": {type: wolf}\n",
                "pets:\n  " + id + ": {owner: '" + owner + "'}\n")) {
            Path primary = directory.resolve("pets.yml");
            Files.writeString(primary, malformed);
            var current = store();
            assertFalse(current.load());
            assertFalse(current.canRestoreBodies());
            assertFalse(current.save());
            assertFalse(current.beginSession());
            assertEquals(malformed, Files.readString(primary));
            assertFalse(Files.exists(directory.resolve("pets.yml.bak")));
            assertFalse(current.close());
        }
    }

    @Test void invalidOptionalFieldsRecoverDefaultsWithoutDiscardingKnownLearning() throws Exception {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        YamlConfiguration yaml = new YamlConfiguration();
        String at = "pets." + id;
        yaml.set(at + ".owner", owner.toString());
        yaml.set(at + ".type", "wolf");
        yaml.set(at + ".entity", "invalid-entity-id");
        yaml.set(at + ".sex", "invalid-sex");
        yaml.set(at + ".personality", "invalid-personality");
        yaml.set(at + ".order", "invalid-order");
        yaml.set(at + ".illness", "invalid-illness");
        yaml.set(at + ".announced", List.of("HUNGER", "unknown-need"));
        yaml.set(at + ".words", List.of(Map.of("word", "missing-trick"), Map.of("trick", "SIT"),
                Map.of("word", "bad", "trick", "invalid trick"), Map.of("word", "wave", "trick", "WAVE"),
                Map.of("word", "sit down", "trick", "SIT")));
        yaml.set(at + ".progress.invalid trick", 100);
        yaml.set(at + ".progress.WAVE", 25);
        yaml.set(at + ".progress.SIT", 40);
        yaml.save(directory.resolve("pets.yml").toFile());
        var current = store();
        assertTrue(current.load());
        var pet = current.get(id);
        assertEquals(owner, pet.ownerId());
        assertNull(pet.entityId());
        assertEquals(PetSex.FEMALE, pet.sex());
        assertEquals(PetPersonality.forId(id), pet.personality());
        assertEquals(PetOrder.FOLLOW, pet.order());
        assertEquals(Illness.NONE, pet.illness());
        assertTrue(pet.announcedLow(Need.HUNGER));
        assertEquals(Trick.SIT, pet.trickFor("sit down"));
        assertNull(pet.trickFor("bad"));
        assertEquals(Trick.valueOf("WAVE"), pet.trickFor("wave"));
        assertEquals(25, pet.progress(Trick.valueOf("WAVE")));
        assertEquals(40, pet.progress(Trick.SIT));
        assertTrue(current.close());
        assertEquals(40, storePet(id).progress(Trick.SIT));
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        return logger;
    }

    @FunctionalInterface private interface MoveOperation { Object move(InvocationOnMock call) throws Throwable; }

    private static ExecutorService writerWithMoves(PetStore store, MoveOperation move) throws Exception {
        // Mockito static mocks are thread-local. Keep production save/flush ordering intact by
        // running the scoped filesystem fault on an actual single-thread writer executor.
        ExecutorService executor = Executors.newSingleThreadExecutor(work -> {
            Thread thread = new Thread(() -> {
                try (var ignored = mockStatic(Files.class, call -> call.getMethod().getName().equals("move")
                        ? move.move(call) : call.callRealMethod())) {
                    work.run();
                }
            }, "PetStore file-provider fixture");
            thread.setDaemon(true);
            return thread;
        });
        setWriter(store, executor);
        return executor;
    }

    private static void setWriter(PetStore store, ExecutorService executor) throws Exception {
        var field = PetStore.class.getDeclaredField("writer");
        field.setAccessible(true);
        assertNull(field.get(store), "install the external executor boundary before any save");
        field.set(store, executor);
    }


    @Test void malformedPublicKennelKeyDoesNotPreventValidDataFromPersisting() {
        var current = store();
        assertTrue(current.load());
        var pet = new Pet(UUID.randomUUID(), UUID.randomUUID(), "cat", "Preserved", PetSex.FEMALE);
        current.add(pet);
        String valid = PetStore.kennelKey("houses", -3, 65, 7);
        current.kennel(valid, pet.ownerId());
        current.kennel("missing-coordinates", UUID.randomUUID());
        try {
            assertTrue(current.save());
            var restored = store();
            assertTrue(restored.load());
            assertEquals(pet.ownerId(), restored.kennelOwner(valid));
            assertEquals("Preserved", restored.get(pet.id()).name());
            assertNull(restored.kennelOwner("missing-coordinates"));
            assertTrue(restored.close());
        } finally { current.close(); }
    }

}
