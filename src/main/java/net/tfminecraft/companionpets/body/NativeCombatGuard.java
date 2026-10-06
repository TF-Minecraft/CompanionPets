package net.tfminecraft.companionpets.body;

import java.util.EnumSet;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mob;
import org.bukkit.plugin.java.JavaPlugin;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;

/** Suppresses targeting without removing native goals or mutating a running goal selector. */
final class NativeCombatGuard implements Goal<Mob> {
    private final Mob body;
    private final GoalKey<Mob> key;
    private boolean enabled;

    private NativeCombatGuard(Mob body, GoalKey<Mob> key) { this.body = body; this.key = key; }

    static void configure(JavaPlugin plugin, Mob body, boolean combat) {
        var key = GoalKey.of(Mob.class, new NamespacedKey(plugin, "native_combat_guard"));
        var goals = Bukkit.getMobGoals();
        var registered = goals.getGoal(body, key);
        NativeCombatGuard guard;
        if (registered instanceof NativeCombatGuard existing) guard = existing;
        else { guard = new NativeCombatGuard(body, key); goals.addGoal(body, 0, guard); }
        guard.enabled = !combat;
        if (!combat && body.getTarget() != null) body.setTarget(null);
    }

    @Override public boolean shouldActivate() { return enabled; }
    @Override public boolean shouldStayActive() { return enabled; }
    @Override public void start() { body.setTarget(null); }
    @Override public GoalKey<Mob> getKey() { return key; }
    @Override public EnumSet<GoalType> getTypes() { return EnumSet.of(GoalType.TARGET); }
}
