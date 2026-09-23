package net.tfminecraft.companionpets.play;

import java.util.UUID;

public final class FetchJob {
    private final String toy;
    private final boolean favorite;
    private FetchPhase phase;
    private UUID projectileId;
    private UUID itemId;
    private long missingSince;

    public FetchJob(String toy, boolean favorite) {
        this.toy = toy;
        this.favorite = favorite;
        this.phase = FetchPhase.AIR;
    }

    public String toy() {
        return toy;
    }

    public boolean favorite() {
        return favorite;
    }

    public FetchPhase phase() {
        return phase;
    }

    public void phase(FetchPhase phase) {
        this.phase = phase;
    }

    public UUID projectileId() {
        return projectileId;
    }

    public void projectileId(UUID projectileId) {
        this.projectileId = projectileId;
    }

    public UUID itemId() {
        return itemId;
    }

    public void itemId(UUID itemId) {
        this.itemId = itemId;
    }

    public long missingSince() {
        return missingSince;
    }

    public void missingSince(long missingSince) {
        this.missingSince = missingSince;
    }
}
