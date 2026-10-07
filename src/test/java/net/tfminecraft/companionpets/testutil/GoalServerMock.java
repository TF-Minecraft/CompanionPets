package net.tfminecraft.companionpets.testutil;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.entity.Mob;
import org.mockbukkit.mockbukkit.ServerMock;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.MobGoals;

/** MockBukkit lacks mob goals; keep registered goals so workflows actually run. */
public class GoalServerMock extends ServerMock {
    private final Map<String, Goal<?>> goals = new HashMap<>();
    private final MobGoals registry = (MobGoals) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{MobGoals.class}, (proxy, method, args) -> {
                String prefix = ((Mob) args[0]).getUniqueId() + ":";
                return switch (method.getName()) {
                    case "getGoal" -> goals.get(prefix + args[1]);
                    case "hasGoal" -> goals.containsKey(prefix + args[1]);
                    case "addGoal" -> { var goal = (Goal<?>) args[2]; goals.put(prefix + goal.getKey(), goal); yield null; }
                    case "removeGoal" -> { goals.remove(prefix + (args[1] instanceof Goal<?> g ? g.getKey() : args[1])); yield null; }
                    case "removeAllGoals" -> { goals.keySet().removeIf(k -> k.startsWith(prefix)); yield null; }
                    case "getAllGoals" -> goals.entrySet().stream().filter(e -> e.getKey().startsWith(prefix)).map(Map.Entry::getValue).toList();
                    default -> throw new AssertionError("Unexpected mob goals call: " + method.getName());
                };
            });
    @Override public MobGoals getMobGoals() { return registry; }
}
