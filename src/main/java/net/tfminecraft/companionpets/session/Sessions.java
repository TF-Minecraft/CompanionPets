package net.tfminecraft.companionpets.session;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class Sessions {
    private final Map<UUID, HatchPrompt> hatches = new HashMap<>();
    private final Map<UUID, RenamePrompt> renames = new HashMap<>();
    private final Map<UUID, TrainingSession> training = new HashMap<>();

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

    public TrainingSession training(UUID playerId) {
        return training.get(playerId);
    }

    public void training(UUID playerId, TrainingSession session) {
        training.put(playerId, session);
    }

    public void clearTraining(UUID playerId) {
        training.remove(playerId);
    }

    public void clearPlayer(UUID playerId) {
        hatches.remove(playerId);
        renames.remove(playerId);
        training.remove(playerId);
    }
}
