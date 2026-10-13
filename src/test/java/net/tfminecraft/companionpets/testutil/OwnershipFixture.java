package net.tfminecraft.companionpets.testutil;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.tlibs.character.*;

/** Existing behaviour fixtures use one explicit character per account; isolation tests supply distinct IDs. */
public final class OwnershipFixture {
    private OwnershipFixture() { }
    public static PluginMock plugin() { return plugin("MockPlugin"); }
    public static PluginMock plugin(String name) {
        PluginMock plugin = MockBukkit.createMockPlugin(name);
        register(plugin);
        return plugin;
    }
    public static void register(org.bukkit.plugin.Plugin plugin) {
        if (Bukkit.getServicesManager().load(CharacterIdentity.class) != null) return;
        Bukkit.getServicesManager().register(CharacterIdentity.class, new CharacterIdentity() {
            public Optional<CharacterSession> currentSession(Player player) {
                return player == null || !player.isOnline() ? Optional.empty() : Optional.of(new CharacterSession(new CharacterRef(player.getUniqueId(), player.getUniqueId()), 1));
            }
            public boolean isCurrent(CharacterSession session) {
                return session != null && currentSession(Bukkit.getPlayer(session.ref().ownerId())).filter(session::equals).isPresent();
            }
            public Optional<CharacterInfo> info(UUID id) {
                if (id == null) return Optional.empty();
                String name = Bukkit.getOfflinePlayer(id).getName();
                return Optional.of(new CharacterInfo(new CharacterRef(id, id), name == null ? "Unknown player" : name, CharacterStatus.ALIVE, null, Set.of(), 0, null));
            }
            public Optional<List<CharacterInfo>> characters(UUID owner) { return Optional.of(List.of(info(owner).orElseThrow())); }
            public Optional<CharacterInfo> lastPlayed(UUID owner) { return info(owner); }
            public Optional<CharacterRef> legacyHeir(UUID owner) { return Optional.of(new CharacterRef(owner, owner)); }
            public boolean legacyHeirsReady() { return true; }
            public List<CharacterInfo> findByName(String name) {
                var player = Bukkit.getOfflinePlayerIfCached(name);
                return player == null ? List.of() : List.of(info(player.getUniqueId()).orElseThrow());
            }
            public String displayName(Player player) { return player.getName(); }
        }, plugin, ServicePriority.Normal);
    }
    public static <T extends org.bukkit.plugin.Plugin> T load(Class<T> type) {
        if (Bukkit.getPluginManager().getPlugin("TLibs") == null) plugin("TLibs");
        return MockBukkit.load(type);
    }
    public static Pet pet(UUID id, UUID account, String type, String name, PetSex sex) {
        Pet pet = new Pet(id, account, type, name, sex);
        pet.ownership(account, account);
        return pet;
    }
}
