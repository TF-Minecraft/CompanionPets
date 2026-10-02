package net.tfminecraft.companionpets.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetSex;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.session.TrainingSession;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.visual.IdleVisual;

class TrainingLifecycleTest {
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    @Test void missingPetTypeEndsTrainingWithoutThrowing() throws Exception {
        var server = MockBukkit.mock();
        var plugin = MockBukkit.createMockPlugin();
        var player = server.addPlayer();
        var visual = new IdleVisual();
        var key = new NamespacedKey(plugin, "pet");
        var store = new PetStore(plugin);
        assertTrue(store.load());
        var runtime = new PetRuntime(plugin, CompanionConfig.load(plugin, new YamlConfiguration()),
                store, new Sessions(), new Bodies(plugin, key, visual), visual, key, new NamespacedKey(plugin, "toy"));
        var pet = new Pet(UUID.randomUUID(), player.getUniqueId(), "removed_type", "Toby", PetSex.MALE);
        pet.stored(false);
        store.add(pet);
        runtime.sessions().training(player.getUniqueId(), new TrainingSession(pet.id()));
        var ticker = new PetTicker(runtime, new PetActions(runtime));
        var watch = PetTicker.class.getDeclaredMethod("watchTraining", long.class);
        watch.setAccessible(true);
        assertDoesNotThrow(() -> watch.invoke(ticker, System.currentTimeMillis()));
        assertNull(runtime.sessions().training(player.getUniqueId()));
        assertTrue(player.nextMessage().contains("no longer configured"));
    }
}
