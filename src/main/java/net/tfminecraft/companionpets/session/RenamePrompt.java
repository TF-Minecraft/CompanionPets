package net.tfminecraft.companionpets.session;

import java.util.UUID;

public final class RenamePrompt {
    private final UUID petId;
    private final long expiresAt;
    private String name;
    private boolean confirming;

    public RenamePrompt(UUID petId, long expiresAt) {
        this.petId = petId;
        this.expiresAt = expiresAt;
    }

    public UUID petId() {
        return petId;
    }

    public long expiresAt() {
        return expiresAt;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    public boolean confirming() {
        return confirming;
    }

    public void confirming(boolean confirming) {
        this.confirming = confirming;
    }
}
