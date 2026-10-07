package net.tfminecraft.companionpets.runtime;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;

import net.tfminecraft.companionpets.config.PetSounds;
import net.tfminecraft.companionpets.config.PetSounds.Event;
import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.Pet;

/** Main-thread sound scheduling for configured overrides and plugin interactions. */
public final class PetVoice {
    private final PetRuntime runtime;
    private final Map<UUID, Map<Event, Long>> lastPlayed = new HashMap<>();
    private final Map<UUID, Long> nextAmbient = new HashMap<>();

    PetVoice(PetRuntime runtime) { this.runtime = runtime; }

    public boolean play(Entity body, Event event) { return play(body, event, 1, 1); }

    public boolean play(Entity body, Event event, float volumeScale, float pitchScale) {
        return play(body, event, volumeScale, pitchScale, System.currentTimeMillis());
    }

    boolean play(Entity body, Event event, float volumeScale, float pitchScale, long now) {
        Pet pet = runtime.byEntity(body);
        var type = pet == null ? null : runtime.config().type(pet.typeId());
        PetSounds.Cue cue = type == null ? null : type.sounds().cue(event);
        if (cue == null || cue.volume() == 0 || cue.sounds().isEmpty()) return false;
        Map<Event, Long> played = lastPlayed.computeIfAbsent(pet.id(), id -> new EnumMap<>(Event.class));
        if (now - played.getOrDefault(event, Long.MIN_VALUE / 2) < cue.minIntervalSeconds() * 1000) return false;
        played.put(event, now);
        String key = cue.sounds().get(runtime.random().nextInt(cue.sounds().size()));
        Sound sound = Registry.SOUNDS.get(NamespacedKey.fromString(key));
        float volume = Math.min(4, cue.volume() * volumeScale);
        float pitch = Math.max(.1f, Math.min(2, cue.pitch() * pitchScale));
        if (sound != null) body.getWorld().playSound(body.getLocation(), sound, volume, pitch);
        else body.getWorld().playSound(body.getLocation(), key, volume, pitch);
        return true;
    }

    public void ambient(Entity body) { play(body, Event.AMBIENT); }
    public void happy(Entity body, boolean loud) { play(body, loud ? Event.HAPPY : Event.HAPPY_QUIET); }
    public void sad(Entity body) { play(body, Event.SAD); }
    public void hurt(Entity body) { play(body, Event.HURT); }
    public void eat(Entity body) { play(body, Event.EAT); }

    void tick(long now) {
        for (Pet pet : runtime.store().active()) {
            var type = runtime.config().type(pet.typeId());
            if (type == null || type.sounds().ambientIntervalSeconds() == 0
                    || type.sounds().cue(Event.AMBIENT) == null) {
                nextAmbient.remove(pet.id()); continue;
            }
            Entity body = runtime.entity(pet);
            if (!(body instanceof Mob mob) || !mob.isAware() || pet.activity() != Activity.NONE) continue;
            Long due = nextAmbient.get(pet.id());
            if (due == null || now >= due) {
                nextAmbient.put(pet.id(), now + Math.round(type.sounds().ambientIntervalSeconds()
                        * (0.8 + runtime.random().nextDouble() * .4) * 1000));
                if (due != null) ambient(body);
            }
        }
        nextAmbient.keySet().removeIf(id -> {
            Pet pet = runtime.store().get(id);
            return pet == null || pet.stored() || pet.dead();
        });
        lastPlayed.keySet().removeIf(id -> runtime.store().get(id) == null);
    }

    void clear() { lastPlayed.clear(); nextAmbient.clear(); }
}
