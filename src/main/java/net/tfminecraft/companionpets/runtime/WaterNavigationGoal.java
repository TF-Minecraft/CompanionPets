package net.tfminecraft.companionpets.runtime;

import java.util.EnumSet;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mob;
import com.destroystokyo.paper.entity.ai.*;
import net.tfminecraft.companionpets.behavior.WaterEscape;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.PetOrder;

/** Floats land pets while preserving fetch and call destinations across water. */
final class WaterNavigationGoal implements Goal<Mob> {
    static final int PATH_SEARCH_BUDGET = 4;
    private static final long ROUTE_RETRY_MILLIS = 2_000L;
    private static final long MAX_SEARCH_DELAY_MILLIS = 16_000L;
    // After 25 seconds without an escape route, FOLLOW yields MOVE to native owner following.
    static final long STUCK_MILLIS = 25_000L;
    private final GoalKey<Mob> key;
    private final PetRuntime runtime;
    private final Pet pet;
    private final Mob body;
    private final PetActions actions;
    private Location exit;
    private long searchAt;
    private Location routeTarget;
    private long routeAt;
    private com.destroystokyo.paper.entity.Pathfinder.PathResult route;
    private boolean preferredExit;
    private Location searchFrom;
    private long retryAt;
    private long retryDelay = 1_000L;
    private int pathsRemaining;
    private double speed = 1.1;
    private long failedSince = -1L;
    private boolean stuck;

    private WaterNavigationGoal(GoalKey<Mob> key, PetRuntime runtime, Pet pet, Mob body, PetActions actions) {
        this.key = key; this.runtime = runtime; this.pet = pet; this.body = body; this.actions = actions;
    }

    static void ensure(PetRuntime runtime, Pet pet, Mob body, PetActions actions) {
        GoalKey<Mob> key = key(runtime);
        var existing = Bukkit.getMobGoals().getGoal(body, key);
        WaterNavigationGoal goal;
        if (existing instanceof WaterNavigationGoal registered) goal = registered;
        else {
            goal = new WaterNavigationGoal(key, runtime, pet, body, actions);
            Bukkit.getMobGoals().addGoal(body, 0, goal);
        }
        runtime.waterGoal(body, goal);
    }

    /** A held pet that just left the water keeps walking its shore route until the native path ends. */
    static boolean finishing(PetRuntime runtime, Mob body) {
        WaterNavigationGoal goal = runtime.waterGoal(body);
        return goal != null && goal.finishing();
    }

    private static GoalKey<Mob> key(PetRuntime runtime) {
        return GoalKey.of(Mob.class, new NamespacedKey(runtime.plugin(), "water_navigation"));
    }

    // Only the shore route itself counts; a fetch or call destination yields to a new posture order.
    private boolean finishing() {
        if (exit == null || preferredExit || WaterEscape.needed(body)) return false;
        var current = body.getPathfinder().getCurrentPath();
        return current != null && near(current.getFinalPoint(), exit);
    }

    @Override public boolean shouldActivate() { return shouldActivate(System.currentTimeMillis()); }
    boolean shouldActivate(long now) {
        if (!body.isValid() || body.isDead() || runtime.entity(pet) != body
                || pet.stored() || pet.dead() || !WaterEscape.needed(body)) {
            resetStuck();
            return false;
        }
        if (!mayYield() || exit != null) resetStuck();
        else if (!stuck && failedSince >= 0 && now - failedSince >= STUCK_MILLIS) {
            actions.releaseFetch(pet, Bukkit.getPlayer(pet.ownerId()), true);
            actions.clearInteractions(pet);
            pet.activity(Activity.NONE);
            pet.forcedSitUntilMillis(0);
            WaterEscape.swim(body, null);
            body.getPathfinder().stopPathfinding();
            stuck = true;
        }
        return !stuck;
    }

    private boolean mayYield() {
        return pet.order() == PetOrder.FOLLOW && !pet.staying()
                && runtime.followingAllowed(pet, Bukkit.getPlayer(pet.ownerId()));
    }

    void resetStuck() { failedSince = -1L; stuck = false; }
    @Override public boolean shouldStayActive() { return shouldActivate(); }
    @Override public void start() { searchAt = 0; tick(); }
    @Override public void tick() {
        tick(System.currentTimeMillis());
    }

    void tick(long now) {
        if (!shouldActivate(now)) return;
        if (pet.fetch() != null && body instanceof org.bukkit.entity.Wolf wolf)
            net.tfminecraft.companionpets.integration.WolfShake.defer(wolf);
        if (now >= searchAt) {
            if (pet.fetch() != null) actions.fetchActions().step(pet, body);
            else if (pet.activity() == net.tfminecraft.companionpets.pet.Activity.ATTENDING)
                actions.roaming().tickAttention(pet, body, now);
            var owner = Bukkit.getPlayer(pet.ownerId());
            Location preferred = pet.fetch() != null ? actions.fetchActions().destination(pet)
                    : pet.activity() == net.tfminecraft.companionpets.pet.Activity.ATTENDING ? actions.roaming().destination(pet)
                    : pet.order() == net.tfminecraft.companionpets.pet.PetOrder.FOLLOW && !pet.staying()
                    && runtime.followingAllowed(pet, owner) ? owner.getLocation() : null;
            speed = pet.fetch() != null ? actions.fetchActions().movementSpeed(pet)
                    : actions.roaming().returningFromFetch(pet) ? actions.roaming().movementSpeed(pet) : 1.1;
            navigate(preferred, now);
            if (exit != null || !mayYield()) resetStuck();
            else if (failedSince < 0) failedSince = now;
            searchAt = now + 500L;
        }
        WaterEscape.swim(body, exit, speed);
    }
    private void navigate(Location preferred, long now) {
        Location from = body.getLocation();
        pathsRemaining = PATH_SEARCH_BUDGET;
        if (exit != null) {
            if (!exit.getWorld().equals(from.getWorld()) || exit.distanceSquared(from) <= 1
                    || (preferredExit ? !near(routeTarget, preferred) : !WaterEscape.safe(exit))) exit = null;
            else {
                if (body.getPathfinder().hasPath() || now < routeAt + ROUTE_RETRY_MILLIS) return;
                if (reachable(exit)) { follow(now); return; }
                exit = null;
            }
            if (exit == null) body.getPathfinder().stopPathfinding();
        }
        if (now < retryAt && near(searchFrom, from)) return;
        if (!near(searchFrom, from)) retryDelay = 1_000L;
        searchFrom = from.clone();
        Location ground = runtime.lastGround(pet);
        preferredExit = false;
        // A remembered bank must advance an active destination rather than send the pet back.
        if (reachable(preferred)) { exit = preferred.clone(); preferredExit = true; }
        else if (ground != null && (preferred == null || ground.distanceSquared(preferred) < from.distanceSquared(preferred))
                && WaterEscape.safe(ground) && reachable(ground)) exit = ground;
        else exit = WaterEscape.exit(from, preferred, candidate -> !candidate.equals(preferred) && reachable(candidate));
        if (exit != null) {
            follow(now);
            retryAt = 0;
            retryDelay = 1_000L;
        } else {
            retryAt = now + retryDelay;
            retryDelay = Math.min(MAX_SEARCH_DELAY_MILLIS, retryDelay * 2);
        }
    }

    private boolean reachable(Location target) {
        route = null;
        return WaterEscape.reachable(body, target, at -> {
            if (pathsRemaining == 0) return false;
            pathsRemaining--;
            var path = body.getPathfinder().findPath(at);
            if (path == null || !path.canReachFinalPoint()) return false;
            route = path;
            return true;
        });
    }

    private void follow(long now) {
        routeTarget = exit.clone();
        routeAt = now;
        if (route != null) body.getPathfinder().moveTo(route, speed);
        else body.getPathfinder().stopPathfinding();
    }

    private static boolean near(Location first, Location second) {
        return first != null && second != null && first.getWorld().equals(second.getWorld())
                && first.distanceSquared(second) < 4;
    }

    // Let the shore route finish after leaving the water, and preserve it across goal restarts.
    @Override public void stop() {
        searchAt = 0;
        if (pet.stored() || pet.dead()) { exit = null; body.getPathfinder().stopPathfinding(); }
    }
    @Override public GoalKey<Mob> getKey() { return key; }
    @Override public EnumSet<GoalType> getTypes() { return EnumSet.of(GoalType.MOVE); }
}
