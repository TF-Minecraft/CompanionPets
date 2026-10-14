package net.tfminecraft.companionpets.ownership;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.configuration.file.YamlConfiguration;

class OwnershipMigrationTest {
    @TempDir Path folder;
    @Test void reviewedHeirPreservesUnknownsPetFriendshipsAndMissingCarersThroughReplay() throws Exception {
        UUID account = UUID.randomUUID(), heir = UUID.randomUUID(), pet = UUID.randomUUID(), friend = UUID.randomUUID(), missing = UUID.randomUUID();
        Path source = folder.resolve("pets.yml");
        String original = "future-root: {nested: [1, 2], explicit-null: null}\nversion: 1\npets:\n  " + pet + ":\n    owner: " + account
                + "\n    type: removed-type\n    future-field: {nested: secret-value}\n    carers:\n      " + account
                + ": {trust: 15, future-memory: keep, explicit-null: null}\n      " + missing + ": {trust: 21}\n    friends:\n      " + friend
                + ": {trust: 16, future-friend: keep}\nkennels: [{world: unloaded, x: 3, y: 4, z: 5, owner: " + account + ", unknown: keep}]\n";
        Files.writeString(source, original);
        var migration = new OwnershipMigration(source, id -> id.equals(account) ? Optional.of(heir) : Optional.empty());
        migration.run();
        var yaml = OwnershipMigration.read(source); String row = "pets." + pet;
        assertEquals(heir.toString(), yaml.getString(row + ".owner-character"));
        assertEquals(account.toString(), yaml.getString(row + ".owner"));
        var raw = OwnershipMigration.raw(Files.readString(source));
        assertTrue(((Map<?, ?>) raw.get("future-root")).containsKey("explicit-null"));

        assertEquals("secret-value", yaml.getString(row + ".future-field.nested"));
        assertEquals("keep", yaml.getString(row + ".carers." + heir + ".future-memory"));
        assertEquals(21, yaml.getInt(row + ".legacy-carers." + missing + ".trust"));
        assertEquals("keep", yaml.getString(row + ".friends." + friend + ".future-friend"));
        assertEquals(heir.toString(), yaml.getMapList("kennels").getFirst().get("owner-character"));
        assertEquals(original, Files.readString(folder.resolve("ownership-migrations/plan-0001.original.yml")));
        String once = Files.readString(source); migration.run(); assertEquals(once, Files.readString(source));
        var plan = OwnershipMigration.read(folder.resolve("ownership-migrations/plan-0001.yml"));
        plan.set("complete", false); DurableFiles.write(folder.resolve("ownership-migrations/plan-0001.yml"), plan.saveToString());
        migration.run(); assertEquals(once, Files.readString(source));
        assertTrue(OwnershipMigration.read(folder.resolve("ownership-migrations/plan-0001.yml")).getBoolean("complete"));
    }
    @Test void incompletePlanDoesNotOverwriteProgressOrChangeItsFrozenRecipient() throws Exception {
        Path source = folder.resolve("pets.yml");
        UUID account = UUID.randomUUID(), heir = UUID.randomUUID();
        String original = "pets: {one: {owner: " + account + ", type: wolf}}\nkennels: []\n";
        Files.writeString(source, original);
        var migration = new OwnershipMigration(source, id -> Optional.of(heir)); migration.run();
        Path file = folder.resolve("ownership-migrations/plan-0001.yml");
        var plan = OwnershipMigration.read(file); plan.set("complete", false); DurableFiles.write(file, plan.saveToString());
        String changed = Files.readString(source) + "later-progress: kept\n"; Files.writeString(source, changed);
        assertTrue(assertThrows(java.io.IOException.class, migration::run).getMessage().contains("BLOCKED"));
        assertEquals(changed, Files.readString(source));
        Files.writeString(source, original); new OwnershipMigration(source, id -> Optional.of(UUID.randomUUID())).run();
        assertEquals(heir.toString(), OwnershipMigration.read(source).getString("pets.one.owner-character"));
    }
    @Test void archiveAndPlanFailureCutsReplayWithoutChangingTheFrozenRecipient() throws Exception {
        UUID account=UUID.randomUUID(),heir=UUID.randomUUID();Path source=folder.resolve("pets.yml");
        String original="pets: {one: {owner: "+account+", type: wolf}}\nkennels: []\n";Files.writeString(source,original);
        var migration=new OwnershipMigration(source,id->Optional.of(heir));
        try(var writes=org.mockito.Mockito.mockStatic(DurableFiles.class,org.mockito.Mockito.CALLS_REAL_METHODS)) {
            writes.when(()->DurableFiles.write(org.mockito.ArgumentMatchers.eq(folder.resolve("ownership-migrations/plan-0001.yml")),org.mockito.ArgumentMatchers.anyString()))
                    .thenThrow(new java.io.IOException("plan fault"));
            assertThrows(java.io.IOException.class,migration::run);
        }
        assertEquals(original,Files.readString(source));assertTrue(Files.exists(folder.resolve("ownership-migrations/plan-0001.original.yml")));
        try(var writes=org.mockito.Mockito.mockStatic(DurableFiles.class,org.mockito.Mockito.CALLS_REAL_METHODS)) {
            writes.when(()->DurableFiles.write(org.mockito.ArgumentMatchers.eq(source),org.mockito.ArgumentMatchers.anyString())).thenThrow(new java.io.IOException("publication fault"));
            assertThrows(java.io.IOException.class,migration::run);
        }
        assertEquals(original,Files.readString(source));
        new OwnershipMigration(source,id->Optional.of(UUID.randomUUID())).run();
        assertEquals(heir.toString(),OwnershipMigration.read(source).getString("pets.one.owner-character"));
    }

    @Test void malformedAndFutureInputsStayIntactAndUnknownOwnersRemainHeld() throws Exception {
        Path source = folder.resolve("pets.yml");
        Files.writeString(source, "pets: {one: {owner: malformed, type: wolf}}\nkennels: [{owner: malformed}]\n");
        new OwnershipMigration(source, id -> Optional.empty()).run();
        assertNull(OwnershipMigration.read(source).get("pets.one.owner-character"));
        Files.writeString(source, "identity-version: 99\npets: {}\n");
        assertThrows(java.io.IOException.class, () -> new OwnershipMigration(source, id -> Optional.empty()).run());
        assertEquals("identity-version: 99\npets: {}\n", Files.readString(source));
        Files.writeString(source, "pets: [bad\n");
        assertThrows(java.io.IOException.class, () -> new OwnershipMigration(source, id -> Optional.empty()).run());
        assertNull(OwnershipMigration.uuid(null)); assertNull(OwnershipMigration.uuid("bad"));

    }
    @Test void ancestorDirectorySyncFailureCannotAcknowledgeANewJournalAndRetryPreservesIt() throws Exception {
        Path journal=folder.resolve("new-plugin-data/estates/intent.yml"),data=journal.getParent().getParent();
        try(var channels=org.mockito.Mockito.mockStatic(java.nio.channels.FileChannel.class,call->{
            if(call.getMethod().getName().equals("open")&&call.getArgument(0,Path.class).equals(data))
                throw new java.io.IOException("ancestor directory sync failure");
            return call.callRealMethod();
        })) {
            assertThrows(java.io.IOException.class,()->DurableFiles.write(journal,"exact intent\n"));
        }
        assertEquals("exact intent\n",Files.readString(journal));
        DurableFiles.write(journal,"exact intent\n");assertEquals("exact intent\n",Files.readString(journal));
    }
    @Test void malformedQuotedFractionalAndUnsupportedVersionsCannotBeNormalized() throws Exception {
        Path source=folder.resolve("pets.yml");
        for(String version:List.of("null","'2'","future","2.5","0","3","4294967298",".NaN")) {
            String original="identity-version: "+version+"\npets: {}\nkennels: []\n";Files.writeString(source,original);
            assertThrows(java.io.IOException.class,()->new OwnershipMigration(source,id->Optional.empty()).run(),version);
            assertEquals(original,Files.readString(source));
        }
        assertNotNull(OwnershipMigration.raw("identity-version: 1\npets: {}\n"));
        assertNotNull(OwnershipMigration.raw("identity-version: 2\npets: {}\n"));
    }
    @Test void duplicateOwnershipAndUnknownNestedKeysRemainUntouchedAndHeld() throws Exception {
        Path source=folder.resolve("pets.yml");
        for(String field:List.of("owner","unknown")) {
            String original="pets:\n  one:\n    type: wolf\n    "+field+": first\n    "+field+": second\nkennels: []\n";
            Files.writeString(source,original);
            assertThrows(java.io.IOException.class,()->new OwnershipMigration(source,id->Optional.of(UUID.randomUUID())).run());
            assertEquals(original,Files.readString(source));
            assertFalse(Files.exists(folder.resolve("ownership-migrations/plan-0001.yml")));
        }
    }
    @Test void strictRawLoaderAcceptsDocumentsAboveBukkitsFormerDefaultCodePointLimit() throws Exception {
        String opaque = "x".repeat(3_145_729);
        var raw = OwnershipMigration.raw("pets: {}\nfuture: " + opaque + "\nkennels: []\n");
        assertEquals(opaque, raw.get("future"));
    }

    @Test void orphanArchiveFromDifferentSourceMustBeReviewedBeforePlanning() throws Exception {
        Path source=folder.resolve("pets.yml");String current="pets: {}\n";Files.writeString(source,current);
        Path archive=folder.resolve("ownership-migrations/plan-0001.original.yml");Files.createDirectories(archive.getParent());Files.writeString(archive,"different source");
        assertTrue(assertThrows(java.io.IOException.class,()->new OwnershipMigration(source,id->Optional.empty()).run()).getMessage().contains("orphaned"));
        assertEquals(current,Files.readString(source));assertEquals("different source",Files.readString(archive));
    }
    @Test void corruptArchiveCannotPublishEvenWhenThePendingSourceMatchesItsPlan() throws Exception {
        Path source=folder.resolve("pets.yml");String original="pets: {}\n";Files.writeString(source,original);
        var migration=new OwnershipMigration(source,id->Optional.empty());migration.run();
        Path file=folder.resolve("ownership-migrations/plan-0001.yml");var plan=OwnershipMigration.read(file);
        plan.set("complete",false);DurableFiles.write(file,plan.saveToString());Files.writeString(source,original);
        Files.writeString(folder.resolve("ownership-migrations/plan-0001.original.yml"),"corrupt");
        assertTrue(assertThrows(java.io.IOException.class,migration::run).getMessage().contains("archive"));
        assertEquals(original,Files.readString(source));
    }
    @Test void corruptPlannedBytesMissingSourceAndUnavailableHashNeverPublish() throws Exception {
        UUID account=UUID.randomUUID();Path source=folder.resolve("pets.yml");Files.writeString(source,"pets: {one: {owner: "+account+", type: wolf}}\n");
        var migration=new OwnershipMigration(source,id->Optional.of(UUID.randomUUID()));migration.run();
        Path file=folder.resolve("ownership-migrations/plan-0001.yml");var plan=OwnershipMigration.read(file);plan.set("complete",false);plan.set("planned","corrupt");DurableFiles.write(file,plan.saveToString());
        assertTrue(assertThrows(java.io.IOException.class,migration::run).getMessage().contains("corrupt"));
        Files.delete(source);assertTrue(assertThrows(java.io.IOException.class,migration::run).getMessage().contains("missing"));
        try(var hashes=org.mockito.Mockito.mockStatic(java.security.MessageDigest.class)) {
            hashes.when(()->java.security.MessageDigest.getInstance("SHA-256")).thenThrow(new java.security.NoSuchAlgorithmException("unavailable"));
            assertThrows(IllegalStateException.class,()->OwnershipMigration.hash("bytes"));
        }
    }
}
