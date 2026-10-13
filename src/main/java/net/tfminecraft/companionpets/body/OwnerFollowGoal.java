package net.tfminecraft.companionpets.body;

import java.util.EnumSet;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Tameable;
import org.bukkit.plugin.Plugin;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.destroystokyo.paper.entity.ai.VanillaGoal;
import net.tfminecraft.companionpets.ownership.CharacterAccess;
import net.tfminecraft.companionpets.ownership.OwnerSession;
import net.tfminecraft.companionpets.pet.Pet;

/** Gates native owner-follow and owner-assist goals; swimming and self-defense remain independent. */
public final class OwnerFollowGoal implements Goal<Tameable> {
    private final GoalKey<Tameable> key;
    private final Goal<Tameable> nativeGoal;
    private Pet pet;
    private OwnerSession activeSession;
    private OwnerFollowGoal(GoalKey<Tameable> key, Goal<Tameable> nativeGoal, Pet pet) {
        this.key = key; this.nativeGoal = nativeGoal; this.pet = pet;
    }
    public static void install(Plugin plugin, Tameable body, Pet pet) {
        wrap(plugin, body, pet, VanillaGoal.FOLLOW_OWNER, "owner_follow", 6);
        wrap(plugin, body, pet, VanillaGoal.OWNER_HURT_BY, "owner_hurt_by", 1);
        wrap(plugin, body, pet, VanillaGoal.OWNER_HURT, "owner_hurt", 2);
    }
    private static void wrap(Plugin plugin, Tameable body, Pet pet, GoalKey<Tameable> nativeKey, String label, int priority) {
        var goals = Bukkit.getMobGoals();
        GoalKey<Tameable> key = GoalKey.of(Tameable.class, new NamespacedKey(plugin, label));
        Goal<Tameable> previous = goals.getGoal(body, key);
        if (previous instanceof OwnerFollowGoal wrapped) { wrapped.pet = pet; return; }
        Goal<Tameable> nativeGoal = goals.getGoal(body, nativeKey);
        if (nativeGoal == null) return;
        goals.removeGoal(body, nativeGoal);
        goals.addGoal(body, priority, new OwnerFollowGoal(key, nativeGoal, pet));
    }
    private boolean allowed() { return !pet.stored() && !pet.dead() && CharacterAccess.owns(pet, Bukkit.getPlayer(pet.ownerId())); }
    @Override public boolean shouldActivate() { return allowed() && nativeGoal.shouldActivate(); }
    private boolean activeAllowed() {
        return allowed() && (activeSession == null || activeSession.account().equals(pet.ownerId())
                && activeSession.character().equals(pet.ownerCharacterId()) && CharacterAccess.current(activeSession));
    }
    @Override public boolean shouldStayActive() { return activeAllowed() && nativeGoal.shouldStayActive(); }
    @Override public void start() {
        if (!allowed()) return;
        activeSession = CharacterAccess.capture(Bukkit.getPlayer(pet.ownerId()));
        if (activeSession != null) nativeGoal.start();
    }
    @Override public void stop() { nativeGoal.stop(); activeSession = null; }
    @Override public void tick() { if (activeAllowed()) nativeGoal.tick(); else nativeGoal.stop(); }
    @Override public GoalKey<Tameable> getKey() { return key; }
    @Override public EnumSet<GoalType> getTypes() { return nativeGoal.getTypes(); }
}
