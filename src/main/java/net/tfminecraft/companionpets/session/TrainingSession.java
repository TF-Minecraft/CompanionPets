package net.tfminecraft.companionpets.session;

import java.util.UUID;

import net.tfminecraft.companionpets.pet.Trick;

public final class TrainingSession {
    private final UUID petId;
    private int attempts;
    private long rewardUntil;
    private Boolean rewardSuccess;
    private String pendingWord;
    private Trick rewardTrick;

    public TrainingSession(UUID petId) {
        this.petId = petId;
    }

    public UUID petId() {
        return petId;
    }

    public int attempts() {
        return attempts;
    }

    public int addAttempt() {
        attempts++;
        return attempts;
    }

    public long rewardUntil() {
        return rewardUntil;
    }

    public Boolean rewardSuccess() {
        return rewardSuccess;
    }

    public void clearReward() {
        this.rewardUntil = 0L;
        this.rewardSuccess = null;
        this.rewardTrick = null;
    }

    public Trick rewardTrick() {
        return rewardTrick;
    }

    public void reward(long until, boolean success, Trick trick) {
        this.rewardUntil = until;
        this.rewardSuccess = success;
        this.rewardTrick = trick;
    }

    public String pendingWord() {
        return pendingWord;
    }

    public void pendingWord(String pendingWord) {
        this.pendingWord = pendingWord;
    }
}
