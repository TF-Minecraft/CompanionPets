package net.tfminecraft.companionpets.ownership;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import net.tfminecraft.tlibs.character.Characters;

final class TLibsCharacters {
    private TLibsCharacters() { }
    static OwnerSession capture(Player player) {
        if (!player.isOnline()) return null;
        return Characters.currentSession(player)
                .filter(session -> session.ref().ownerId().equals(player.getUniqueId()))
                .filter(session -> Characters.info(session.ref().characterId())
                        .filter(info -> info.alive() && info.ref().equals(session.ref())).isPresent())
                .filter(Characters::isCurrent)
                .map(session -> new OwnerSession(session.ref().ownerId(), session.ref().characterId(), session.sessionId()))
                .orElse(null);
    }
    static boolean heirReady() { return Characters.legacyHeirsReady(); }
    static Optional<UUID> heir(UUID account) {
        return Characters.legacyHeir(account).filter(ref -> ref.ownerId().equals(account))
                .map(ref -> ref.characterId());
    }
    static Player person(UUID character) {
        UUID account = account(character);
        Player player = account == null ? null : Bukkit.getPlayer(account);
        OwnerSession session = CharacterAccess.capture(player);
        return session != null && session.character().equals(character) ? player : null;
    }
    static String name(UUID character) {
        return Characters.info(character).map(info -> info.name()).orElse("Unknown character");
    }
    static UUID resolve(String token) {
        UUID id = OwnershipMigration.uuid(token);
        if (id != null) return Characters.info(id).map(info -> info.ref().characterId()).orElse(null);
        var named = Characters.findByName(token);
        if (named.size() == 1) return named.getFirst().ref().characterId();
        var online = CharacterAccess.capture(Bukkit.getPlayerExact(token));
        return online == null ? null : online.character();
    }
    static boolean alive(UUID character) {
        return Characters.info(character).filter(info -> info.alive()).isPresent();
    }
    static UUID account(UUID character) {
        return character == null ? null : Characters.info(character).map(info -> info.ref().ownerId()).orElse(null);
    }
}
