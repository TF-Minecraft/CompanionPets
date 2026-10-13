package net.tfminecraft.companionpets.estate;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.ownership.*;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.runtime.*;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.testutil.OwnershipFixture;
import net.tfminecraft.companionpets.visual.IdleVisual;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.estate.*;

class PetEstateTest {
    @TempDir Path folder;
    PetStore store; PetRuntime runtime; PetActions actions; PetEstate estate; Pet pet;
    CharacterRef from = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
    CharacterRef to = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
    UUID death = UUID.randomUUID();
    @BeforeEach void prepare() throws Exception {
        MockBukkit.mock(new net.tfminecraft.companionpets.testutil.GoalServerMock()); var plugin = OwnershipFixture.plugin();
        store = new PetStore(plugin); assertTrue(store.load());
        var config = new YamlConfiguration(); config.loadFromString("pets: {wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG}}\nlimits: {max-pets: 1}\n");
        var visual = new IdleVisual(); var key = new NamespacedKey(plugin, "pet");
        runtime = new PetRuntime(plugin, CompanionConfig.load(plugin, config), store, new Sessions(), new Bodies(plugin,key,visual),visual,key,new NamespacedKey(plugin,"toy"));
        actions = new PetActions(runtime); estate = new PetEstate(runtime, actions, folder); estate.guard = decision -> true;
        pet = new Pet(UUID.randomUUID(), from.ownerId(), "removed-type", "Toby", PetSex.MALE); pet.ownership(from.ownerId(),from.characterId()); pet.stored(true);
        pet.bond(88); pet.bindWord("sit", Trick.SIT); pet.progress(Trick.SIT, 99); pet.need(Need.HUNGER, 42);
        pet.friends().restore(UUID.randomUUID(), new RelationshipMemory.Memory(15,1,2,3));
        pet.carers().restore(from.characterId(), new RelationshipMemory.Memory(17,1,2,3));
        store.add(pet); store.kennel("unloaded,1,2,3",from.ownerId(),from.characterId()); assertTrue(store.save());assertTrue(store.migrate());
        pet=store.get(pet.id());
    }
    @AfterEach void cleanup() { if(store!=null)store.close(); MockBukkit.unmock(); }
    EstateDecision decision(long revision, Recipient recipient) {
        return new EstateDecision(death,from,Optional.of(Instant.EPOCH),Instant.EPOCH,Optional.of(to),Optional.empty(),Optional.empty(),
                Recipient.FROZEN,recipient,Recipient.FROZEN,Recipient.SENDER,revision);
    }
    @Test void estateCannotSealAnEmptyIntentBeforeReviewedMigrationHasCaughtUpWithProviderReadiness() throws Exception {
        Path file=runtime.plugin().getDataFolder().toPath().resolve("pets.yml");
        var legacy=OwnershipMigration.read(file);legacy.set("identity-version",null);legacy.set("pets."+pet.id()+".owner-character",null);DurableFiles.write(file,legacy.saveToString());
        assertTrue(store.load());pet=store.get(pet.id());assertFalse(store.ownershipReady());assertNull(pet.ownerCharacterId());
        var identity=org.mockito.Mockito.mock(net.tfminecraft.tlibs.character.CharacterIdentity.class);var ready=new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.when(identity.legacyHeirsReady()).thenAnswer(call->ready.get());
        org.mockito.Mockito.when(identity.legacyHeir(from.ownerId())).thenReturn(Optional.of(from));
        org.bukkit.Bukkit.getServicesManager().register(net.tfminecraft.tlibs.character.CharacterIdentity.class,identity,runtime.plugin(),org.bukkit.plugin.ServicePriority.Highest);
        var decision=decision(1,Recipient.character(to));assertInstanceOf(Outcome.Retry.class,estate.settle(decision));assertFalse(Files.exists(folder.resolve(death+"-1.yml")));
        ready.set(true);assertInstanceOf(Outcome.Retry.class,estate.settle(decision));assertFalse(Files.exists(folder.resolve(death+"-1.yml")));
        assertTrue(store.migrate());pet=store.get(pet.id());assertTrue(store.ownershipReady());assertEquals(from.characterId(),pet.ownerCharacterId());
        assertInstanceOf(Outcome.Done.class,estate.settle(decision));assertEquals(to.characterId(),pet.ownerCharacterId());
        Files.writeString(file,Files.readString(file)+"external-progress: kept\n");assertFalse(store.save());
        assertInstanceOf(Outcome.Blocked.class,estate.settle(decision(2,Recipient.character(to))));
    }
    @Test void unavailableSpeciesStillBindsNativeRoutingAndFollowerGateBeforeAndAfterEstate() {
        var world=org.bukkit.Bukkit.getWorlds().isEmpty()?org.mockbukkit.mockbukkit.MockBukkit.getMock().addSimpleWorld("world"):org.bukkit.Bukkit.getWorlds().getFirst();
        var pathfinder=org.mockito.Mockito.mock(com.destroystokyo.paper.entity.Pathfinder.class);
        var wolf=new org.mockbukkit.mockbukkit.entity.WolfMock(org.mockbukkit.mockbukkit.MockBukkit.getMock(),UUID.randomUUID()) {
            @Override public com.destroystokyo.paper.entity.Pathfinder getPathfinder() { return pathfinder; }
            @Override public boolean isInWater() { return false; }
        };
        wolf.setLocation(new org.bukkit.Location(world,0,64,0));org.mockbukkit.mockbukkit.MockBukkit.getMock().registerEntity(wolf);
        pet.stored(false);pet.entityId(wolf.getUniqueId());
        var key=new NamespacedKey(runtime.plugin(),"pet");wolf.getPersistentDataContainer().set(key,org.bukkit.persistence.PersistentDataType.STRING,pet.id().toString());
        @SuppressWarnings("unchecked") var nativeGoal=(com.destroystokyo.paper.entity.ai.Goal<org.bukkit.entity.Tameable>)org.mockito.Mockito.mock(com.destroystokyo.paper.entity.ai.Goal.class);
        org.mockito.Mockito.when(nativeGoal.getKey()).thenReturn(com.destroystokyo.paper.entity.ai.VanillaGoal.FOLLOW_OWNER);
        org.mockito.Mockito.when(nativeGoal.getTypes()).thenReturn(java.util.EnumSet.of(com.destroystokyo.paper.entity.ai.GoalType.MOVE));
        org.bukkit.Bukkit.getMobGoals().addGoal(wolf,6,nativeGoal);
        actions.reattach(wolf);assertEquals(from.ownerId(),wolf.getOwner().getUniqueId());
        var followKey=com.destroystokyo.paper.entity.ai.GoalKey.of(org.bukkit.entity.Tameable.class,new NamespacedKey(runtime.plugin(),"owner_follow"));
        assertNotNull(org.bukkit.Bukkit.getMobGoals().getGoal(wolf,followKey));
        assertInstanceOf(Outcome.Done.class,estate.settle(decision(1,Recipient.character(to))));
        assertEquals(to.ownerId(),wolf.getOwner().getUniqueId());assertNotNull(org.bukkit.Bukkit.getMobGoals().getGoal(wolf,followKey));
    }
    @Test void propertyAndKennelsMoveOnceWhileStatsMemoriesAndReceiptCountsSurviveRestart() throws Exception {
        var decision = decision(1,Recipient.character(to)); var friends=pet.friends().entries(); var carers=pet.carers().entries();
        assertEquals("companionpets",estate.id()); assertFalse(estate.describe(from.characterId()).equals(Holdings.NONE));
        assertInstanceOf(Outcome.Done.class, estate.settle(decision(1,Recipient.WAITING)));
        var outcome=estate.settle(decision); assertInstanceOf(Outcome.Done.class,outcome);
        assertEquals(to.ownerId(),pet.ownerId()); assertEquals(to.characterId(),pet.ownerCharacterId());
        assertEquals(88,pet.bond()); assertEquals(99,pet.progress(Trick.SIT)); assertEquals(42,pet.need(Need.HUNGER));
        assertEquals(friends,pet.friends().entries()); assertEquals(carers,pet.carers().entries());
        assertEquals(to.characterId(),store.kennelCharacter("unloaded,1,2,3"));
        assertEquals(outcome,estate.settle(decision)); assertEquals(outcome,estate.settle(decision(2,Recipient.character(to))));
        assertEquals(Holdings.NONE,estate.describe(from.characterId()));
        assertTrue(store.load());assertTrue(store.migrate()); assertEquals(to.characterId(),store.get(pet.id()).ownerCharacterId());
        assertEquals(outcome,estate.settle(decision));
    }
    @Test void incompleteCurrentIntentRepairsTheReceiptButStalePublishedIntentBlocksAnotherRecipient() throws Exception {
        var decision = decision(1,Recipient.character(to)); var outcome=estate.settle(decision);
        Path file=folder.resolve(death+"-1.yml"); var plan=OwnershipMigration.read(file); plan.set("complete",false); DurableFiles.write(file,plan.saveToString());
        assertEquals(outcome,estate.settle(decision));
        plan.set("complete",false); DurableFiles.write(file,plan.saveToString());
        assertInstanceOf(Outcome.Blocked.class,estate.settle(decision(2,Recipient.character(new CharacterRef(UUID.randomUUID(),UUID.randomUUID())))));
        assertTrue(pet.ownershipHeld()); assertEquals(to.characterId(),pet.ownerCharacterId());
    }
    @Test void changedOwnershipAndUnavailablePersistenceCannotBeOverwritten() throws Exception {
        var decision=decision(1,Recipient.character(to)); assertInstanceOf(Outcome.Done.class,estate.settle(decision));
        Path file=folder.resolve(death+"-1.yml"); var plan=OwnershipMigration.read(file); plan.set("complete",false); DurableFiles.write(file,plan.saveToString());
        pet.ownership(UUID.randomUUID(),UUID.randomUUID()); assertInstanceOf(Outcome.Blocked.class,estate.settle(decision));
        plan.set("decision","wrong-recipient"); DurableFiles.write(file,plan.saveToString()); assertInstanceOf(Outcome.Blocked.class,estate.settle(decision));
        estate.guard=ignored->false; assertInstanceOf(Outcome.Retry.class,estate.settle(decision));
        estate.guard=ignored->true; Files.writeString(folder.resolve("broken.yml"),"[broken"); assertInstanceOf(Outcome.Blocked.class,estate.settle(decision));
    }
    @Test void unstartedOldIntentCanBeReplannedButIoFailureHoldsAuthorityAndConflictingHouseBlocks() throws Exception {
        var first=decision(1,Recipient.character(to));
        java.util.concurrent.atomic.AtomicInteger guardCalls=new java.util.concurrent.atomic.AtomicInteger();
        estate.guard=ignored->guardCalls.incrementAndGet()<3;
        assertInstanceOf(Outcome.Retry.class,estate.settle(first));assertTrue(pet.ownershipHeld());assertTrue(store.kennelHeld("unloaded,1,2,3"));
        estate.guard=ignored->true;
        assertInstanceOf(Outcome.Done.class,estate.settle(decision(2,Recipient.character(to))));
        var second=OwnershipMigration.read(folder.resolve(death+"-2.yml"));second.set("complete",false);DurableFiles.write(folder.resolve(death+"-2.yml"),second.saveToString());
        store.kennel("unloaded,1,2,3",UUID.randomUUID(),UUID.randomUUID());
        assertInstanceOf(Outcome.Blocked.class,estate.settle(decision(2,Recipient.character(to))));
    }
    @Test void startupRecoveryHoldsIncompleteOrUnreadableJournalsAndHourlyReportsEachEstate() throws Exception {
        var decision=decision(1,Recipient.character(to));assertInstanceOf(Outcome.Done.class,estate.settle(decision));
        var plan=OwnershipMigration.read(folder.resolve(death+"-1.yml"));plan.set("complete",false);
        Path boot=runtime.plugin().getDataFolder().toPath().resolve("estates");Files.createDirectories(boot);DurableFiles.write(boot.resolve(death+"-1.yml"),plan.saveToString());
        PetEstate.start(runtime.plugin(),runtime,actions);assertTrue(pet.ownershipHeld());assertTrue(store.kennelHeld("unloaded,1,2,3"));
        pet.ownershipHeld(false);store.holdKennel("unloaded,1,2,3",false);
        Files.writeString(boot.resolve("broken.yml"),"[broken");PetEstate.start(runtime.plugin(),runtime,actions);
        assertTrue(pet.ownershipHeld());assertTrue(store.kennelHeld("unloaded,1,2,3"));
        var service=org.mockito.Mockito.mock(EstateService.class);
        org.bukkit.Bukkit.getServicesManager().register(EstateService.class,service,runtime.plugin(),org.bukkit.plugin.ServicePriority.Highest);
        estate.decided=()->List.of(decision);estate.guard=ignored->false;estate.reconcile();org.mockito.Mockito.verifyNoInteractions(service);
        estate.guard=ignored->true;estate.reconcile();org.mockito.Mockito.verify(service).report(org.mockito.ArgumentMatchers.eq(death),org.mockito.ArgumentMatchers.eq("companionpets"),org.mockito.ArgumentMatchers.eq(1L),org.mockito.ArgumentMatchers.any(Outcome.class));
    }
    @Test void malformedJournalBlocksButFilesystemFailureRemainsRetryable() throws Exception {
        var decision=decision(1,Recipient.character(to));assertInstanceOf(Outcome.Done.class,estate.settle(decision));
        Path file=folder.resolve(death+"-1.yml");String original=Files.readString(file);
        for(var defect:List.of("from-account","revision","complete","decision","pets","kennels","pet-id")) {
            var plan=OwnershipMigration.parse(original);
            switch(defect) {
                case "from-account" -> plan.set(defect,"broken");
                case "revision" -> plan.set(defect,-1);
                case "complete" -> plan.set(defect,"unknown");
                case "decision" -> plan.set(defect,"");
                case "pet-id" -> plan.set("pets",List.of("bad-id"));
                default -> plan.set(defect,List.of(7));
            }
            DurableFiles.write(file,plan.saveToString());
            assertInstanceOf(Outcome.Blocked.class,estate.settle(decision),defect);
            assertTrue(pet.ownershipHeld());assertTrue(store.kennelHeld("unloaded,1,2,3"));
        }
        Path inaccessible=folder.resolve("not-a-directory");Files.writeString(inaccessible,"preserved");
        var unavailable=new PetEstate(runtime,actions,inaccessible);unavailable.guard=ignored->true;
        assertInstanceOf(Outcome.Retry.class,unavailable.settle(decision));assertEquals("preserved",Files.readString(inaccessible));
    }
    @Test void intactDecisionStringCannotAuthorizeCorruptRecipientSourceDeathOrRevisionEnvelope() throws Exception {
        var decision=decision(1,Recipient.character(to));
        var guardCalls=new java.util.concurrent.atomic.AtomicInteger();estate.guard=ignored->guardCalls.incrementAndGet()<2;
        assertInstanceOf(Outcome.Retry.class,estate.settle(decision));estate.guard=ignored->true;
        Path file=folder.resolve(death+"-1.yml");String original=Files.readString(file);
        for(String field:List.of("from-account","from-character","to-account","to-character","death","revision")) {
            var plan=OwnershipMigration.parse(original);plan.set(field,field.equals("revision")?2L:UUID.randomUUID().toString());
            assertEquals(decision.toString(),plan.getString("decision"));DurableFiles.write(file,plan.saveToString());
            assertInstanceOf(Outcome.Blocked.class,estate.settle(decision),field);
            assertEquals(from.ownerId(),pet.ownerId());assertEquals(from.characterId(),pet.ownerCharacterId());
            assertTrue(pet.ownershipHeld());assertTrue(store.kennelHeld("unloaded,1,2,3"));
        }
        DurableFiles.write(file,original);assertInstanceOf(Outcome.Done.class,estate.settle(decision));
    }
    @Test void temporaryJournalReadFailureRetainsReceiptAndRetriesWithoutRepublishingProperty() throws Exception {
        var decision=decision(1,Recipient.character(to));assertInstanceOf(Outcome.Done.class,estate.settle(decision));
        Path file=folder.resolve(death+"-1.yml");String original=Files.readString(file);
        try(var reader=org.mockito.Mockito.mockStatic(OwnershipMigration.class,org.mockito.Mockito.CALLS_REAL_METHODS)) {
            reader.when(()->OwnershipMigration.read(file)).thenThrow(new java.io.IOException("transient filesystem read fault"));
            assertInstanceOf(Outcome.Retry.class,estate.settle(decision));
        }
        assertEquals(original,Files.readString(file));assertEquals(to.characterId(),pet.ownerCharacterId());
        assertInstanceOf(Outcome.Done.class,estate.settle(decision));
    }
    @Test void conflictingExternalWriterBlocksEstateAndPreservesItsCurrentBytes() throws Exception {
        Path file=runtime.plugin().getDataFolder().toPath().resolve("pets.yml");String changed=Files.readString(file)+"outside-progress: preserved\n";Files.writeString(file,changed);
        assertInstanceOf(Outcome.Blocked.class,estate.settle(decision(1,Recipient.character(to))));
        assertEquals(changed,Files.readString(file));assertEquals(from.characterId(),pet.ownerCharacterId());assertTrue(pet.ownershipHeld());
    }
}
