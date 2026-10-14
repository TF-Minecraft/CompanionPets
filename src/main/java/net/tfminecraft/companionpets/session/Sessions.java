package net.tfminecraft.companionpets.session;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class Sessions {
    private final Map<UUID, HatchPrompt> hatches = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, RenamePrompt> renames = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, ReleasePrompt> releases = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, TrainingSession> training = new HashMap<>();
    private final Map<UUID, Long> trainingRest = new HashMap<>();
    private final Map<UUID, net.tfminecraft.companionpets.ownership.OwnerSession> hatchTokens = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, net.tfminecraft.companionpets.ownership.OwnerSession> renameTokens = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, net.tfminecraft.companionpets.ownership.OwnerSession> releaseTokens = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, net.tfminecraft.companionpets.ownership.OwnerSession> trainingTokens = new HashMap<>();
    private final Map<UUID, net.tfminecraft.companionpets.ownership.OwnerSession> published = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean characters;
    public void enforceCharacters() {
        characters = true;
        org.bukkit.Bukkit.getOnlinePlayers().forEach(player -> refresh(player.getUniqueId()));
    }
    public void refresh(UUID account) {
        var session = net.tfminecraft.companionpets.ownership.CharacterAccess.capture(org.bukkit.Bukkit.getPlayer(account));
        if (session == null) published.remove(account); else published.put(account, session);
    }
    public net.tfminecraft.companionpets.ownership.OwnerSession token(UUID account) { return published.get(account); }
    private boolean remember(UUID account, Map<UUID, net.tfminecraft.companionpets.ownership.OwnerSession> tokens) {
        if (!characters) return true;
        refresh(account);
        var token = published.get(account);
        if (token == null) return false;
        tokens.put(account, token);
        return true;
    }
    private boolean valid(UUID account, Map<UUID, net.tfminecraft.companionpets.ownership.OwnerSession> tokens) {
        return !characters || net.tfminecraft.companionpets.ownership.CharacterAccess.current(tokens.get(account));
    }


    public HatchPrompt hatch(UUID playerId) {
        return valid(playerId, hatchTokens) ? hatches.get(playerId) : null;
    }

    public void hatch(UUID playerId, HatchPrompt prompt) {
        if (remember(playerId, hatchTokens)) hatches.put(playerId, prompt);
    }

    public void clearHatch(UUID playerId) {
        hatches.remove(playerId); hatchTokens.remove(playerId);
    }

    public RenamePrompt rename(UUID playerId) {
        return valid(playerId, renameTokens) ? renames.get(playerId) : null;
    }

    public void rename(UUID playerId, RenamePrompt prompt) {
        if (remember(playerId, renameTokens)) renames.put(playerId, prompt);
    }

    public void clearRename(UUID playerId) {
        renames.remove(playerId); renameTokens.remove(playerId);
    }

    public ReleasePrompt release(UUID playerId) { return valid(playerId, releaseTokens) ? releases.get(playerId) : null; }
    public void release(UUID playerId, ReleasePrompt prompt) { if (remember(playerId, releaseTokens)) releases.put(playerId, prompt); }
    public void clearRelease(UUID playerId) { releases.remove(playerId); releaseTokens.remove(playerId); }

    /** Thread-safe identity snapshot for async chat; prompt contents stay on the main thread. */
    public Object privatePrompt(UUID playerId) {
        Object prompt = samePublished(playerId, releaseTokens) ? releases.get(playerId) : null;
        if (prompt == null && samePublished(playerId, hatchTokens)) prompt = hatches.get(playerId);
        return prompt == null && samePublished(playerId, renameTokens) ? renames.get(playerId) : prompt;
    }
    private boolean samePublished(UUID account, Map<UUID, net.tfminecraft.companionpets.ownership.OwnerSession> tokens) {
        return !characters || tokens.get(account) != null && tokens.get(account).equals(published.get(account));
    }

    public TrainingSession training(UUID playerId) {
        return valid(playerId, trainingTokens) ? training.get(playerId) : null;
    }

    public void training(UUID playerId, TrainingSession session) {
        if (remember(playerId, trainingTokens)) training.put(playerId, session);
    }

    public void clearTraining(UUID playerId) {
        training.remove(playerId); trainingTokens.remove(playerId);
    }

    public boolean resting(UUID petId, long now) {
        Long until = trainingRest.get(petId);
        if (until == null) {
            return false;
        }
        if (now >= until) {
            trainingRest.remove(petId);
            return false;
        }
        return true;
    }

    public long restUntil(UUID petId) {
        return trainingRest.getOrDefault(petId, 0L);
    }

    public void rest(UUID petId, long until) {
        trainingRest.put(petId, until);
    }

    public void clearPlayer(UUID playerId) {
        published.remove(playerId);
        hatches.remove(playerId); hatchTokens.remove(playerId);
        renames.remove(playerId); renameTokens.remove(playerId);
        releases.remove(playerId); releaseTokens.remove(playerId);
        training.remove(playerId); trainingTokens.remove(playerId);
    }

    public void clearPet(UUID petId) {
        training.values().removeIf(session -> session.petId().equals(petId));
        renames.values().removeIf(prompt -> prompt.petId().equals(petId));
        releases.values().removeIf(prompt -> prompt.petId().equals(petId));
    }

    public void clearForReload() {
        hatchTokens.clear(); renameTokens.clear(); releaseTokens.clear(); trainingTokens.clear();
        hatches.clear();
        renames.clear();
        releases.clear();
        training.clear();
        trainingRest.values().removeIf(until -> until <= System.currentTimeMillis());
    }
}
