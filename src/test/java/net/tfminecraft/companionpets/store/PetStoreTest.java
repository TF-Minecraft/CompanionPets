package net.tfminecraft.companionpets.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test
    void savesPetsAndKennelsAcrossRestart() {
        UUID owner = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        PetStore first = store();
        assertTrue(first.load());
        Pet pet = new Pet(id, owner, "beagle", "Luna", PetSex.FEMALE);
        pet.personality(PetPersonality.PLAYFUL);
        pet.bornAt(123456L);
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
        pet.progress(Trick.SPIN, 45);
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
        assertEquals(45, restored.progress(Trick.SPIN));
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
    void recoversBackupAndDoesNotReplaceItWithCorruptPrimary() throws Exception {
        UUID id = UUID.randomUUID();
        PetStore first = store();
        assertTrue(first.load());
        first.add(new Pet(id, UUID.randomUUID(), "wolf", "Rex", PetSex.MALE));
        first.save();
        Path primary = directory.resolve("pets.yml");
        Path backup = directory.resolve("pets.yml.bak");
        assertTrue(Files.exists(backup));
        Files.writeString(primary, "pets: [broken");

        PetStore recovered = store();
        assertTrue(recovered.load());
        assertNotNull(recovered.get(id));
        recovered.save();
        assertNotNull(storePet(id));
        assertTrue(Files.readString(backup).contains("Rex"));
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
}
