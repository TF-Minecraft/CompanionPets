package net.tfminecraft.companionpets.ownership;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import net.tfminecraft.companionpets.pet.Pet;

/** Keeps older TLibs jars outside classes that use the new API. */
public final class CharacterAccess {
    private CharacterAccess() { }
    private static Boolean apiPresent;
    public static boolean available() {
        if (apiPresent != null) return apiPresent;
        try {
            Class<?> characters = Class.forName("net.tfminecraft.tlibs.character.Characters");
            characters.getMethod("legacyHeirsReady");
            characters.getMethod("legacyHeir", UUID.class);
            Class.forName("net.tfminecraft.tlibs.character.CharacterSessionStartEvent");
            apiPresent = true;
        } catch (ReflectiveOperationException | LinkageError ex) { apiPresent = false; }
        return apiPresent;
    }
    public static OwnerSession capture(Player player) {
        return player == null || !available() ? null : TLibsCharacters.capture(player);
    }
    public static boolean current(OwnerSession session) {
        return session != null && available() && session.equals(capture(Bukkit.getPlayer(session.account())));
    }
    public static boolean owns(Pet pet, Player player) {
        OwnerSession session = capture(player);
        return session != null && !pet.ownershipHeld() && pet.ownerId().equals(session.account())
                && session.character().equals(pet.ownerCharacterId());
    }
    public static boolean heirReady() { return available() && TLibsCharacters.heirReady(); }
    public static Optional<UUID> heir(UUID account) {
        return available() ? TLibsCharacters.heir(account) : Optional.empty();
    }
    public static Player person(UUID character) {
        return available() ? TLibsCharacters.person(character) : null;
    }
    public static String name(UUID character) {
        return character == null || !available() ? "Unassigned character" : TLibsCharacters.name(character);
    }
    public static UUID resolve(String token) {
        return available() ? TLibsCharacters.resolve(token) : null;
    }
    public static boolean alive(UUID character) {
        return character != null && available() && TLibsCharacters.alive(character);
    }
    public static UUID account(UUID character) {
        return available() ? TLibsCharacters.account(character) : null;
    }
}
