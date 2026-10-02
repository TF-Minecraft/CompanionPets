package net.tfminecraft.companionpets.training;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.store.PetStore;

class DefaultTricksTest {
    private JavaPlugin plugin;
    private YamlConfiguration yaml;
    @BeforeEach void setup() throws Exception {
        MockBukkit.mock(); plugin = MockBukkit.createMockPlugin();
        yaml = new YamlConfiguration();
        yaml.loadFromString("""
                pets:
                  wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG}
                  frog: {entity: FROG, egg: FROG_SPAWN_EGG, tricks: [tongue], default-tricks: [follow, tongue]}
                  cat: {entity: CAT, egg: CAT_SPAWN_EGG, default-tricks: []}
                custom-tricks:
                  tongue: {fallback-text: Tongue}
                """);
    }
    @AfterEach void teardown() { MockBukkit.unmock(); }
    private Pet pet(String type) { return new Pet(UUID.randomUUID(), UUID.randomUUID(), type, "Toby", PetSex.MALE); }

    @Test void everyOrdinaryPetStartsWithVisibleLearnedFollow() {
        var config = CompanionConfig.load(plugin, yaml); var pet = pet("wolf");
        assertTrue(DefaultTricks.apply(config, pet));
        assertEquals(List.of(Trick.FOLLOW), config.type("wolf").defaultTricks());
        assertEquals(100, pet.progress(Trick.FOLLOW)); assertEquals(Trick.FOLLOW, pet.trickFor("follow"));
        assertFalse(DefaultTricks.apply(config, pet), "Repeated application is idempotent");
    }

    @Test void overridesReplaceGlobalsAndCanIncludeCustomTricksOrBeEmpty() {
        yaml.set("training.default-tricks", List.of("follow", "sit"));
        var config = CompanionConfig.load(plugin, yaml);
        var wolf = pet("wolf"); var frog = pet("frog"); var cat = pet("cat");
        DefaultTricks.apply(config, wolf); DefaultTricks.apply(config, frog); DefaultTricks.apply(config, cat);
        assertEquals(100, wolf.progress(Trick.SIT)); assertEquals(0, frog.progress(Trick.SIT));
        assertEquals(100, frog.progress(Trick.valueOf("tongue"))); assertEquals(Trick.valueOf("tongue"), frog.trickFor("tongue"));
        assertEquals(0, cat.progress(Trick.FOLLOW)); assertTrue(cat.words().isEmpty());
        assertTrue(config.type("frog").allowsTrick(Trick.FOLLOW), "Default tricks are enabled alongside the explicit tricks list");
    }

    @Test void invalidDefaultListsAreRejectedInsteadOfSilentlyLosingBasicControl() {
        for (Object bad : List.of("follow", List.of("unknown"), List.of("spin"), List.of(42))) {
            yaml.set("training.default-tricks", bad);
            assertThrows(IllegalArgumentException.class, () -> CompanionConfig.load(plugin, yaml));
        }
        yaml.set("training.default-tricks", List.of("follow")); yaml.set("pets.frog.default-tricks", List.of("unknown"));
        assertThrows(IllegalArgumentException.class, () -> CompanionConfig.load(plugin, yaml));
    }

    @Test void grantingDefaultsPreservesOtherLearningAndWordBindings() {
        var config = CompanionConfig.load(plugin, yaml); var pet = pet("wolf");
        pet.bindWord("follow", Trick.SPEAK); pet.bindWord("here", Trick.FOLLOW); pet.progress(Trick.SIT, 53);
        DefaultTricks.apply(config, pet);
        assertEquals(Trick.SPEAK, pet.trickFor("follow")); assertEquals(Trick.FOLLOW, pet.trickFor("here"));
        assertEquals(53, pet.progress(Trick.SIT)); assertEquals(100, pet.progress(Trick.FOLLOW));
        yaml.set("training.default-tricks", List.of()); DefaultTricks.apply(CompanionConfig.load(plugin, yaml), pet);
        assertEquals(100, pet.progress(Trick.FOLLOW), "Removing a default never unlearns a trick");
    }

    @Test void comeAndFollowKeepIndependentWordsAndProgress() throws Exception {
        var pet = pet("wolf");
        var data = new YamlConfiguration();
        data.set("pets." + pet.id() + ".owner", pet.ownerId().toString());
        data.set("pets." + pet.id() + ".type", "wolf"); data.set("pets." + pet.id() + ".name", "Toby");
        data.set("pets." + pet.id() + ".words", List.of(java.util.Map.of("word", "here", "trick", "COME")));
        data.set("pets." + pet.id() + ".progress.COME", 53);
        data.set("pets." + pet.id() + ".progress.FOLLOW", 71);
        data.save(new java.io.File(plugin.getDataFolder(), "pets.yml"));
        var store = new PetStore(plugin); assertTrue(store.load()); var migrated = store.get(pet.id());
        assertNotNull(migrated); assertEquals(Trick.COME, migrated.trickFor("here")); assertEquals(71, migrated.progress(Trick.FOLLOW));
        assertEquals(Trick.COME, Trick.valueOf("come")); assertEquals(53, migrated.progress(Trick.COME));
        DefaultTricks.apply(CompanionConfig.load(plugin, yaml), migrated);
        assertEquals(100, migrated.progress(Trick.FOLLOW)); assertEquals(Trick.FOLLOW, migrated.trickFor("follow"));
        store.save(); var saved = YamlConfiguration.loadConfiguration(new java.io.File(plugin.getDataFolder(), "pets.yml"));
        assertEquals(53, saved.getDouble("pets." + pet.id() + ".progress.COME")); assertEquals(100, saved.getDouble("pets." + pet.id() + ".progress.FOLLOW"));
    }

    @Test void previousComeWordFoldedIntoFollowIsRestoredWithoutChangingOtherFollowWords() throws Exception {
        var pet = pet("wolf"); pet.bindWord("come", Trick.FOLLOW); pet.bindWord("follow", Trick.FOLLOW); pet.bindWord("with me", Trick.FOLLOW);
        pet.progress(Trick.FOLLOW, 82);
        var store = new PetStore(plugin); assertTrue(store.load()); store.add(pet); assertTrue(store.save()); assertTrue(store.load());
        var loaded = store.get(pet.id());
        assertEquals(Trick.COME, loaded.trickFor("come")); assertEquals(82, loaded.progress(Trick.COME));
        assertEquals(Trick.FOLLOW, loaded.trickFor("follow")); assertEquals(Trick.FOLLOW, loaded.trickFor("with me"));
    }

    @Test void startupAndConfigReloadGrantDefaultsToStoredPetsAndPersistThem() {
        var store = new PetStore(plugin); assertTrue(store.load()); var pet = pet("wolf"); pet.stored(true);
        pet.progress(Trick.JUMP, 53); store.add(pet);
        var visual = new net.tfminecraft.companionpets.visual.IdleVisual();
        var key = new org.bukkit.NamespacedKey(plugin, "pet");
        var runtime = new net.tfminecraft.companionpets.runtime.PetRuntime(plugin, CompanionConfig.load(plugin, yaml), store,
                new net.tfminecraft.companionpets.session.Sessions(), new net.tfminecraft.companionpets.body.Bodies(plugin, key, visual),
                visual, key, new org.bukkit.NamespacedKey(plugin, "toy"));
        assertEquals(100, pet.progress(Trick.FOLLOW)); assertEquals(Trick.FOLLOW, pet.trickFor("follow"));
        yaml.set("training.default-tricks", List.of("follow", "lay")); runtime.config(CompanionConfig.load(plugin, yaml));
        assertEquals(100, pet.progress(Trick.LAY)); assertEquals(53, pet.progress(Trick.JUMP));
        var reloaded = new PetStore(plugin); assertTrue(reloaded.load());
        assertEquals(Trick.LAY, reloaded.get(pet.id()).trickFor("lay")); assertEquals(100, reloaded.get(pet.id()).progress(Trick.LAY));
    }
}
