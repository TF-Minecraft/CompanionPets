package net.tfminecraft.companionpets.integration;

import java.lang.reflect.Method;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Mob;
import com.destroystokyo.paper.entity.Pathfinder.PathResult;

/** stopPathfinding/setAware leave the native travel inputs of MoveControl intact. */
public final class PetMotion {
    private PetMotion() { }
    private record Access(Method handle, Method control, Method wanted, Method tick,
                          Method speed, Method sideways, Method upward, Method forward) { }
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override protected Access computeValue(Class<?> type) {
            try {
                Method handle = type.getMethod("getHandle");
                Class<?> nativeType = handle.getReturnType();
                Method control = nativeType.getMethod("getMoveControl");
                return new Access(handle, control,
                        control.getReturnType().getMethod("setWantedPosition", double.class, double.class, double.class, double.class),
                        control.getReturnType().getMethod("tick"),
                        nativeType.getMethod("setSpeed", float.class), nativeType.getMethod("setXxa", float.class),
                        nativeType.getMethod("setYya", float.class), nativeType.getMethod("setZza", float.class));
            } catch (ReflectiveOperationException ex) { return null; }
        }
    };

    private record WaterAccess(Method handle, Method get, Method set, Object water) { }
    private static final ClassValue<WaterAccess> WATER_ACCESS = new ClassValue<>() {
        @Override protected WaterAccess computeValue(Class<?> type) {
            try {
                Method handle = type.getMethod("getHandle");
                Class<?> nativeType = handle.getReturnType();
                for (Method get : nativeType.getMethods()) {
                    if (!get.getName().equals("getPathfindingMalus") || get.getParameterCount() != 1) continue;
                    Class<?> pathType = get.getParameterTypes()[0];
                    if (!pathType.getName().equals("net.minecraft.world.level.pathfinder.PathType")) continue;
                    Object water = pathType.getField("WATER").get(null);
                    return new WaterAccess(handle, get,
                            nativeType.getMethod("setPathfindingMalus", pathType, float.class), water);
                }
                return null;
            } catch (ReflectiveOperationException ex) { return null; }
        }
    };

    public static boolean moveTo(Mob body, Location target, double speed) {
        return withWater(body, () -> body.getPathfinder().moveTo(target, speed));
    }

    public static PathResult findPath(Mob body, Location target) {
        return withWater(body, () -> body.getPathfinder().findPath(target));
    }

    public static boolean moveTo(Mob body, PathResult route, double speed) {
        return withWater(body, () -> body.getPathfinder().moveTo(route, speed));
    }

    /** Like FollowOwnerGoal, custom routes treat water as ordinary terrain during path calculation. */
    private static <T> T withWater(Mob body, Supplier<T> navigation) {
        WaterAccess access = WATER_ACCESS.get(body.getClass());
        if (access == null) return navigation.get();
        Object handle;
        float previous;
        try {
            handle = access.handle.invoke(body);
            previous = (float) access.get.invoke(handle, access.water);
            access.set.invoke(handle, access.water, 0F);
        } catch (ReflectiveOperationException ex) { return navigation.get(); }
        try { return navigation.get(); }
        finally {
            try { access.set.invoke(handle, access.water, previous); }
            catch (ReflectiveOperationException ex) { /* Native access is optional. */ }
        }
    }

    public static void stop(Mob body) {
        if (net.tfminecraft.companionpets.behavior.WaterEscape.needed(body)) {
            net.tfminecraft.companionpets.behavior.WaterEscape.swim(body);
            return;
        }
        body.getPathfinder().stopPathfinding();
        resetNative(body);
        var velocity = body.getVelocity();
        velocity.setX(0); velocity.setZ(0);
        body.setVelocity(velocity);
    }

    /** Below this squared horizontal speed, vanilla friction has already brought the body to rest. */
    static final double SETTLED = 1.0E-6;

    /**
     * For callers that hold a body still every tick. A velocity change is sent to every nearby
     * player, so a body that is already still is left alone; a route or a slide is stopped.
     */
    public static void settle(Mob body) {
        if (net.tfminecraft.companionpets.behavior.WaterEscape.needed(body)) {
            net.tfminecraft.companionpets.behavior.WaterEscape.swim(body);
            return;
        }
        var velocity = body.getVelocity();
        if (body.getPathfinder().hasPath()
                || velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ() > SETTLED) stop(body);
    }

    public static void hold(Mob body) {
        stop(body);
        if (!net.tfminecraft.companionpets.behavior.WaterEscape.needed(body)) body.setAware(false);
    }

    public static boolean resetNative(Mob body) {
        Access access = ACCESS.get(body.getClass());
        if (access == null) return false;
        try {
            Object handle = access.handle.invoke(body);
            Object control = access.control.invoke(handle);
            var at = body.getLocation();
            access.wanted.invoke(control, at.getX(), at.getY(), at.getZ(), 0D);
            access.tick.invoke(control);
            access.speed.invoke(handle, 0F);
            access.sideways.invoke(handle, 0F);
            access.upward.invoke(handle, 0F);
            access.forward.invoke(handle, 0F);
            return true;
        } catch (ReflectiveOperationException ex) { return false; }
    }
}
