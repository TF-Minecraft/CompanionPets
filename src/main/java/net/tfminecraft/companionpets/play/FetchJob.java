package net.tfminecraft.companionpets.play;

import java.util.UUID;
import java.util.Set;
import java.util.HashSet;

public final class FetchJob {
    private final String toy;
    private final UUID id = UUID.randomUUID();
    private final UUID throwerId;
    private final Set<UUID> favorites = new HashSet<>();
    private final Set<UUID> chasers = new java.util.LinkedHashSet<>();
    private final java.util.Map<UUID, Double> speeds = new java.util.HashMap<>();
    private final java.util.Map<UUID, Stalk> stalks = new java.util.HashMap<>();
    public static final class Stalk {
        public final long until;
        public long pounceAt = -1;
        public boolean finished;
        private Stalk(long now) { until = now + 1500; }
    }
    public Stalk stalk(UUID petId, long now) { return stalks.computeIfAbsent(petId, ignored -> new Stalk(now)); }
    public boolean stalkFinished(UUID petId) { return stalks.containsKey(petId) && stalks.get(petId).finished; }
    public boolean stalking(UUID petId) { return stalks.containsKey(petId) && !stalks.get(petId).finished && stalks.get(petId).pounceAt < 0; }
    public boolean pouncing(UUID petId) { return stalks.containsKey(petId) && !stalks.get(petId).finished && stalks.get(petId).pounceAt >= 0; }
    public void stopStalk(UUID petId, long now) { stalk(petId, now).finished = true; }
    private UUID carrierId;
    private FetchPhase phase;
    private UUID projectileId;
    private UUID itemId;
    private long missingSince;

    public FetchJob(String toy, UUID throwerId) {
        this.toy = toy;
        this.throwerId = throwerId;
        this.phase = FetchPhase.AIR;
    }

    public String toy() {
        return toy;
    }

    public UUID id() { return id; }
    public UUID throwerId() { return throwerId; }
    public UUID carrierId() { return carrierId; }
    /** Pets that joined this race, so participants are found without scanning every pet. */
    public void join(UUID petId) { chasers.add(petId); }
    public Set<UUID> chasers() { return java.util.Collections.unmodifiableSet(chasers); }
    public void favorite(UUID petId, boolean favorite) {
        if (favorite) favorites.add(petId); else favorites.remove(petId);
    }
    public boolean favorite(UUID petId) { return favorites.contains(petId); }

    /** Cache normal locomotion pace; the shared play multiplier is applied by the caller. */
    public double speed(UUID petId, double initialSpeed) {
        return speeds.computeIfAbsent(petId, ignored -> initialSpeed);
    }

    public boolean claim(UUID petId) {
        if (phase != FetchPhase.GROUND || carrierId != null) return false;
        carrierId = petId;
        phase = FetchPhase.CARRY;
        return true;
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
