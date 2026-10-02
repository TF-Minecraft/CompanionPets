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

    public HatchPrompt hatch(UUID playerId) {
        return hatches.get(playerId);
    }

    public void hatch(UUID playerId, HatchPrompt prompt) {
        hatches.put(playerId, prompt);
    }

    public void clearHatch(UUID playerId) {
        hatches.remove(playerId);
    }

    public RenamePrompt rename(UUID playerId) {
        return renames.get(playerId);
    }

    public void rename(UUID playerId, RenamePrompt prompt) {
        renames.put(playerId, prompt);
    }

    public void clearRename(UUID playerId) {
        renames.remove(playerId);
    }

    public ReleasePrompt release(UUID playerId) { return releases.get(playerId); }
    public void release(UUID playerId, ReleasePrompt prompt) { releases.put(playerId, prompt); }
    public void clearRelease(UUID playerId) { releases.remove(playerId); }

    /** Thread-safe identity snapshot for async chat; prompt contents stay on the main thread. */
    public Object privatePrompt(UUID playerId) {
        Object prompt = releases.get(playerId);
        if (prompt == null) prompt = hatches.get(playerId);
        return prompt == null ? renames.get(playerId) : prompt;
    }

    public TrainingSession training(UUID playerId) {
        return training.get(playerId);
    }

    public void training(UUID playerId, TrainingSession session) {
        training.put(playerId, session);
    }

    public void clearTraining(UUID playerId) {
        training.remove(playerId);
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
        hatches.remove(playerId);
        renames.remove(playerId);
        releases.remove(playerId);
        training.remove(playerId);
    }

    public void clearPet(UUID petId) {
        training.values().removeIf(session -> session.petId().equals(petId));
        renames.values().removeIf(prompt -> prompt.petId().equals(petId));
        releases.values().removeIf(prompt -> prompt.petId().equals(petId));
    }

    public void clearForReload() {
        hatches.clear();
        renames.clear();
        releases.clear();
        training.clear();
        trainingRest.values().removeIf(until -> until <= System.currentTimeMillis());
    }
}
