package net.tfminecraft.companionpets.staff;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetSex;
import net.tfminecraft.companionpets.runtime.PetActions;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.visual.PetVisual;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Wolf;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PetDiagnosticsTest {
    private ServerMock server;
    private JavaPlugin plugin;
    private PlayerMock operator;
    private PetRuntime runtime;
    private PetActions actions;
    private StaffCommands commands;
    private PetDiagnostics diagnostics;
    // Blueprint names and clip sets are the external visual-provider boundary.
    private final Map<String, Set<String>> blueprints = new HashMap<>();

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.createMockPlugin();
        operator = server.addPlayer("Operator");
        operator.setOp(true);
        var visual = new PetVisual() {
            @Override public void apply(Entity entity, PetTypeDef type) { }
            @Override public boolean modelAvailable(PetTypeDef type) {
                return !type.appearance().modeled() || blueprints.containsKey(type.appearance().model());
            }
            @Override public Set<String> clips(PetTypeDef type) {
                return blueprints.getOrDefault(type.appearance().model(), Set.of());
            }
        };
        var key = new NamespacedKey(plugin, "pet");
        var store = new PetStore(plugin);
        assertTrue(store.load());
        runtime = new PetRuntime(plugin, config("pets: {wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG}}"),
                store, new Sessions(), new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        actions = new PetActions(runtime);
        commands = new StaffCommands(runtime, actions);
        diagnostics = commands.diagnostics();
    }

    @AfterEach void cleanup() {
        if (actions != null) actions.holograms().clear();
        if (runtime != null) assertTrue(runtime.store().close());
        MockBukkit.unmock();
    }

    @Test void validVanillaItemsAndKnownSavedPetsNeedNoWarningsOrChanges() {
        Pet pet = add("wolf");
        pet.stored(true);
        int entities = operator.getWorld().getEntities().size();
        assertEquals(List.of(), diagnostics.validate());
        assertEquals(List.of(pet), List.copyOf(runtime.store().all()));
        assertTrue(pet.stored());
        assertNull(pet.entityId());
        assertTrue(operator.getInventory().isEmpty());
        assertEquals(entities, operator.getWorld().getEntities().size());
    }

    @Test void everyUnavailableItemCategoryIsReportedOnceAcrossTypesAndAliases() throws Exception {
        runtime.config(config("""
                items:
                  kennel: 'ia.tfmc:house'
                  foods: [{item: 'mmoitems:MATERIAL:FOOD', hunger: 35}]
                  treats: ['ia.tfmc:treat']
                  medicines: ['ia.tfmc:medicine']
                  brushes: ['ia.tfmc:brush']
                  toys: ['ia.tfmc:toy', 'itemsadder:tfmc:house']
                moments:
                  dig-loot:
                    - {item: 'mmoitems:MATERIAL:GIFT', weight: 1}
                    - {item: 'itemsadder:tfmc:toy', weight: 2}
                pets:
                  wolf: {entity: WOLF, egg: 'ia.tfmc:egg'}
                  cat: {entity: CAT, egg: CAT_SPAWN_EGG}
                """));
        assertEquals(2, runtime.config().types().size());
        List<String> issues = diagnostics.validate();
        assertEquals(Set.of("Item unavailable: itemsadder:tfmc:house",
                "Item unavailable: mmoitems:MATERIAL:GIFT", "Item unavailable: itemsadder:tfmc:egg",
                "Item unavailable: mmoitems:MATERIAL:FOOD", "Item unavailable: itemsadder:tfmc:treat",
                "Item unavailable: itemsadder:tfmc:medicine", "Item unavailable: itemsadder:tfmc:brush",
                "Item unavailable: itemsadder:tfmc:toy"), Set.copyOf(issues));
        assertEquals(8, issues.size(), "Shared references and legacy aliases name the same item");
        assertThrows(UnsupportedOperationException.class, () -> issues.add("extra"));
        assertTrue(operator.getInventory().isEmpty());
    }

    @Test void configuredFurnitureReportsProviderApiFailureAndStillChecksOtherReferences() throws Exception {
        MockBukkit.createMockPlugin("ItemsAdder");
        runtime.config(config("""
                items: {kennel: 'itemsadder:tfmc:house', kennel-furniture: 'itemsadder:tfmc:house'}
                pets: {wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG}}
                """));
        assertEquals(List.of("Could not validate items.kennel-furniture against the ItemsAdder simple furniture API",
                "Item unavailable: itemsadder:tfmc:house"), diagnostics.validate());
        server.getPluginManager().disablePlugin(server.getPluginManager().getPlugin("ItemsAdder"));
        assertEquals(List.of("Item unavailable: itemsadder:tfmc:house"), diagnostics.validate());
    }

    @Test void anEnabledMythicProviderWithAnUnavailableApiProducesAnActionableWarning() throws Exception {
        MockBukkit.createMockPlugin("MythicMobs");
        runtime.config(config("pets: {wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG, mythic-mob: CompanionWolf}}"));
        assertNotNull(runtime.config().type("wolf"), "Enabled provider retains the configured type");
        assertEquals(List.of("wolf: MythicMob unavailable: CompanionWolf"), diagnostics.validate());
    }

    @Test void anUnavailableModelReportsTheModelInsteadOfEveryDependentClip() throws Exception {
        runtime.config(config("pets: {wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG, model: canine}}"));
        assertEquals(List.of("wolf: model unavailable: canine"), diagnostics.validate());
        blueprints.put("canine", Set.of("idle", "walk"));
        assertEquals(List.of(), diagnostics.validate(), "A later provider registration is visible on the next validation");
    }

    @Test void clipWarningsIncludeRequiredPosesAndOnlyAllowedCustomTricksWithoutFallbacks() throws Exception {
        runtime.config(config("""
                custom-tricks:
                  wave: {animation: wave_clip}
                  dance: {animation: dance_clip}
                  optional: {animation: missing_optional, fallback-text: Hello}
                  spoken: {fallback-text: Hello}
                  unused: {animation: missing_unused}
                pets:
                  wolf:
                    entity: WOLF
                    egg: WOLF_SPAWN_EGG
                    model: canine
                    animations: {idle: '', walk: stroll}
                    tricks: {add: [wave, dance, optional, spoken]}
                """));
        blueprints.put("canine", Set.of("dance_clip"));
        assertEquals(List.of("wolf: no configured IDLE clip", "wolf: no configured WALK clip",
                "wolf: unavailable custom animation wave_clip"), diagnostics.validate());
        blueprints.put("canine", Set.of("dance_clip", "stroll", "wave_clip"));
        assertEquals(List.of("wolf: no configured IDLE clip"), diagnostics.validate());
    }

    @Test void removedTypesRemainIdentifiableWithoutDiscardingSavedPets() throws Exception {
        Pet orphan = add("retired_wolf");
        orphan.stored(true);
        assertTrue(runtime.store().save());
        assertEquals(List.of("Pet " + orphan.id() + ": undefined type retired_wolf"), diagnostics.validate());
        assertSame(orphan, runtime.store().get(orphan.id()));
        var reloaded = new PetStore(plugin);
        assertTrue(reloaded.load());
        assertEquals("Toby", reloaded.get(orphan.id()).name());
        assertEquals("retired_wolf", reloaded.get(orphan.id()).typeId());
        assertTrue(reloaded.close());
    }

    @Test void malformedKennelConfigurationDoesNotPreventOtherDiagnostics() throws Exception {
        runtime.config(config("items: {kennel: NOT_A_REAL_ITEM}\npets: {wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG}}"));
        assertNull(runtime.config().kennel());
        Pet orphan = add("removed");
        assertEquals(List.of("Pet " + orphan.id() + ": undefined type removed"), diagnostics.validate());
    }

    @Test void explanationsTrackLoadedUnloadedAndStoredBodiesWithoutChangingPetState() {
        Pet pet = add("wolf");
        assertEquals(List.of("Body missing or its chunk is unloaded"), diagnostics.explain(pet));
        Wolf body = operator.getWorld().spawn(operator.getLocation(), Wolf.class);
        runtime.remember(pet, body);
        assertEquals(List.of(), diagnostics.explain(pet));
        body.remove();
        assertEquals(List.of("Body missing or its chunk is unloaded"), diagnostics.explain(pet));
        pet.stored(true);
        assertEquals(List.of("In Pet House"), diagnostics.explain(pet));
        assertEquals(body.getUniqueId(), pet.entityId(), "Diagnostics must not repair or discard body identity");
    }

    @Test void explanationsKeepAllHealthAndRestReasonsAndRespectTrainingThresholds() {
        Pet pet = add("wolf");
        pet.stored(true);
        pet.dead(true);
        pet.illness(Illness.SICK);
        pet.need(Need.HUNGER, 24.99);
        pet.need(Need.ENERGY, 24.99);
        runtime.sessions().rest(pet.id(), Long.MAX_VALUE);
        List<String> reasons = diagnostics.explain(pet);
        assertEquals(List.of("In Pet House", "Dead; cannot be recovered", "Illness: SICK",
                "Too hungry to train", "Too tired to train", "Resting between training sessions"), reasons);
        assertThrows(UnsupportedOperationException.class, () -> reasons.clear());
        pet.dead(false);
        pet.illness(Illness.NONE);
        pet.need(Need.HUNGER, 25);
        pet.need(Need.ENERGY, 25);
        runtime.sessions().rest(pet.id(), 1);
        assertEquals(List.of("In Pet House"), diagnostics.explain(pet));
        assertEquals(0, runtime.sessions().restUntil(pet.id()));
    }

    @Test void operatorInspectionShowsResolvedClipsAndCustomWarningsWithoutMutatingPets() throws Exception {
        runtime.config(config("""
                custom-tricks:
                  wave: {animation: wave_clip}
                  optional: {animation: optional_clip, fallback-text: Hello}
                pets:
                  wolf:
                    entity: WOLF
                    egg: WOLF_SPAWN_EGG
                    model: canine
                    animations: {idle: relax, walk: stroll, swim: ''}
                    tricks: {add: [wave, optional]}
                """));
        blueprints.put("canine", Set.of("relax", "stroll"));
        Pet pet = add("wolf");
        pet.stored(true);
        int entities = operator.getWorld().getEntities().size();
        assertTrue(commands.execute(operator, "inspect", "wolf"));
        String report = messages();
        assertTrue(report.contains("Model: canine | Scale: 1.0 | Available: true"), report);
        assertTrue(report.contains("Blueprint clips found: relax, stroll"), report);
        assertTrue(report.contains("Found idle -> relax"), report);
        assertTrue(report.contains("Found walk -> stroll"), report);
        assertTrue(report.contains("Unavailable trick wave: missing animation wave_clip"), report);
        assertFalse(report.contains("Unavailable trick optional"), report);
        assertTrue(report.contains("Disabled animation: swim"), report);
        assertEquals(List.of("wolf: unavailable custom animation wave_clip"), diagnostics.validate());
        assertTrue(pet.stored());
        assertNull(pet.entityId());
        assertEquals(entities, operator.getWorld().getEntities().size());
        assertEquals(1, runtime.store().all().size());
    }

    @Test void inspectionPermissionAndUsageFailuresDoNotExposeTypeDiagnostics() {
        operator.setOp(false);
        commands.execute(operator, "inspect", "wolf");
        assertEquals("You do not have permission to use inspect.", messages());
        operator.setOp(true);
        commands.execute(operator, "inspect");
        assertEquals("Usage: /companionpets inspect <configured-type>", messages());
        commands.execute(operator, "inspect", "missing");
        assertEquals("Unknown configured pet type: missing", messages());
        commands.execute(operator, "inspect", "wolf");
        assertTrue(messages().contains("Appearance: vanilla | Model animations: not applicable"));
    }

    private CompanionConfig config(String text) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return CompanionConfig.load(plugin, yaml);
    }

    private Pet add(String type) {
        var pet = new Pet(UUID.randomUUID(), operator.getUniqueId(), type, "Toby", PetSex.MALE);
        runtime.store().add(pet);
        return pet;
    }

    private String messages() {
        var messages = new ArrayList<String>();
        String next;
        while ((next = operator.nextMessage()) != null) messages.add(next);
        return String.join("\n", messages);
    }
}
