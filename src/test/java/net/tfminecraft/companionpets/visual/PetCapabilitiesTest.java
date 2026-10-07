package net.tfminecraft.companionpets.visual;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.*;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;

class PetCapabilitiesTest {
    private org.bukkit.plugin.java.JavaPlugin plugin;
    private CompanionConfig config;
    private boolean available = true, tail;
    private Set<String> clips = Set.of("idle", "walk", "sleep", "lie_back", "belly_up");
    private final PetVisual visual = new PetVisual() {
        public void apply(Entity entity, PetTypeDef type) { }
        public boolean modelAvailable(PetTypeDef type) { return available; }
        public boolean hasTail(PetTypeDef type) { return tail; }
        public Set<String> clips(PetTypeDef type) { return clips; }
    };

    @BeforeEach void setup() throws Exception {
        MockBukkit.mock(); plugin = MockBukkit.createMockPlugin();
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
                pets:
                  dog: {species: dog, model: beagle, egg: EGG}
                  remapped:
                    species: dog
                    model: beagle
                    egg: STICK
                    animations: {lie_back: roll, belly_up: belly, get_up: rise}
                  disabled:
                    species: dog
                    model: beagle
                    egg: BONE
                    animations: {get_up: ''}
                  vanilla: {species: dog, egg: WOLF_SPAWN_EGG}
                """);
        config = CompanionConfig.load(plugin, yaml);
    }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void missingTailAndBellyClipsDisableOnlyActionsThatRequireThem() {
        var dog = config.type("dog");
        var report = PetCapabilities.inspect(dog, visual);
        for (var behavior : Set.of(PetBehavior.GREETING_TAIL_WAG, PetBehavior.TOY_TAIL_WAG, PetBehavior.SOCIAL_TAIL_WAG))
            assertEquals("no tail bone", report.disabledBehaviors().get(behavior));
        assertTrue(report.disabledBehaviors().get(PetBehavior.BELLY_RUB).contains("get_up"));
        assertTrue(report.behaviors(dog).contains(PetBehavior.TOY_JUMPS), "A jump can use body movement without a model clip");
        assertTrue(report.behaviors(dog).contains(PetBehavior.TOY_WIGGLE), "Body shuffling does not need a tail bone");
        assertTrue(report.behaviors(dog).contains(PetBehavior.FETCH));
        assertEquals("sleep", report.animations().get(PetAnimation.LIE).name());
        assertFalse(report.missingAnimations(dog).containsKey("lie"));
        assertEquals("jump", report.missingAnimations(dog).get("jump"));
    }

    @Test void completeModelsEnableMappedBellyButRespectExplicitDisabledClips() {
        tail = true; clips = Set.of("roll", "belly", "rise", "lie_back", "belly_up", "get_up");
        var remapped = config.type("remapped");
        assertTrue(PetCapabilities.inspect(remapped, visual).disabledBehaviors().isEmpty());
        assertTrue(PetCapabilities.inspect(config.type("disabled"), visual).disabledBehaviors().containsKey(PetBehavior.BELLY_RUB));
        available = false;
        var missing = PetCapabilities.inspect(remapped, visual);
        assertTrue(missing.animations().isEmpty());
        assertEquals("model unavailable", missing.disabledBehaviors().get(PetBehavior.TOY_TAIL_WAG));
        assertTrue(missing.behaviors(remapped).contains(PetBehavior.FETCH));
    }

    @Test void runtimeRetriesUnavailableModelsWithoutReload() {
        available = false; tail = true;
        clips = Set.of("lie_back", "belly_up", "get_up");
        var key = new NamespacedKey(plugin, "pet");
        var runtime = new PetRuntime(plugin, config, new PetStore(plugin), new Sessions(),
                new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        var pet = new Pet(UUID.randomUUID(), UUID.randomUUID(), "dog", "Toby", PetSex.MALE);
        var type = config.type("dog");
        assertFalse(runtime.capabilities(type).modelAvailable());
        assertFalse(runtime.behaves(pet, PetBehavior.GREETING_TAIL_WAG));
        assertFalse(runtime.behaves(pet, PetBehavior.BELLY_RUB));
        available = true;
        assertTrue(runtime.behaves(pet, PetBehavior.GREETING_TAIL_WAG));
        assertTrue(runtime.behaves(pet, PetBehavior.TOY_TAIL_WAG));
        assertTrue(runtime.behaves(pet, PetBehavior.SOCIAL_TAIL_WAG));
        assertTrue(runtime.behaves(pet, PetBehavior.BELLY_RUB));
        assertTrue(runtime.capabilities(type).modelAvailable());
        assertSame(runtime.capabilities(type), runtime.capabilities(type), "Available models remain cached");
    }

    @Test void runtimeUsesInspectionCapabilitiesAndRefreshesThemOnReload() {
        var key = new NamespacedKey(plugin, "pet");
        var runtime = new PetRuntime(plugin, config, new PetStore(plugin), new Sessions(),
                new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        var pet = new Pet(UUID.randomUUID(), UUID.randomUUID(), "dog", "Toby", PetSex.MALE);
        assertFalse(runtime.behaves(pet, PetBehavior.GREETING_TAIL_WAG));
        assertFalse(runtime.behaves(pet, PetBehavior.BELLY_RUB));
        assertTrue(runtime.behaves(pet, PetBehavior.FETCH));
        tail = true; clips = Set.of("lie_back", "belly_up", "get_up");
        runtime.config(config);
        assertTrue(runtime.behaves(pet, PetBehavior.GREETING_TAIL_WAG));
        assertTrue(runtime.behaves(pet, PetBehavior.BELLY_RUB));
        var vanilla = PetCapabilities.inspect(config.type("vanilla"), new IdleVisual());
        assertFalse(vanilla.disabledBehaviors().containsKey(PetBehavior.TOY_TAIL_WAG));
        assertTrue(vanilla.disabledBehaviors().containsKey(PetBehavior.BELLY_RUB));
    }
}
