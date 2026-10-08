package net.tfminecraft.companionpets.pet;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded positive memories. Recognition never grants ownership or command rights. */
public final class RelationshipMemory {
    public static final int LIMIT = 32;
    public static final double FAMILIAR_AT = 12;
    public record Memory(double trust, long reinforcedAt, long nearbyAt, long greetedAt) {
        public Memory {
            trust = Double.isFinite(trust) ? Math.max(0, Math.min(100, trust)) : 0;
            reinforcedAt = Math.max(0, reinforcedAt); nearbyAt = Math.max(0, nearbyAt);
            greetedAt = Math.max(0, greetedAt);
        }
    }
    private final Map<UUID, Memory> entries = new LinkedHashMap<>();
    private final Runnable changed;
    public RelationshipMemory() { this(() -> { }); }
    RelationshipMemory(Runnable changed) { this.changed = changed; }
    public Map<UUID, Memory> entries() { return Collections.unmodifiableMap(entries); }
    public Memory get(UUID id) { return entries.get(id); }
    public double trust(UUID id) { var memory = get(id); return memory == null ? 0 : memory.trust(); }
    public boolean familiar(UUID id) { return trust(id) >= FAMILIAR_AT; }
    public void clear() { if (!entries.isEmpty()) { entries.clear(); changed.run(); } }
    public void restore(UUID id, Memory memory) {
        if (memory.trust() <= 0) return;
        if (!memory.equals(entries.put(id, memory))) changed.run();
        while (entries.size() > LIMIT) {
            UUID oldest = entries.entrySet().stream().min(Comparator.comparingLong(e -> e.getValue().reinforcedAt())).orElseThrow().getKey();
            entries.remove(oldest);
        }
    }
    public boolean reinforce(UUID id, double gain, long now, long cooldown) {
        var previous = get(id);
        if (!Double.isFinite(gain) || gain <= 0 || now <= 0
                || previous != null && now - previous.reinforcedAt() < cooldown) return false;
        restore(id, new Memory(trust(id) + gain, now, now, previous == null ? 0 : previous.greetedAt()));
        return true;
    }
    public void nearby(UUID id, long now) {
        var memory = get(id);
        if (memory != null) restore(id, new Memory(memory.trust(), memory.reinforcedAt(), Math.max(memory.nearbyAt(), now), memory.greetedAt()));
    }
    public void greeted(UUID id, long now) {
        var memory = get(id);
        if (memory != null) restore(id, new Memory(memory.trust(), memory.reinforcedAt(), Math.max(memory.nearbyAt(), now), Math.max(memory.greetedAt(), now)));
    }
}
