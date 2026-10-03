package net.tfminecraft.companionpets.integration;

import java.lang.reflect.Method;
import org.bukkit.entity.Mob;

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

    public static void stop(Mob body) {
        if (net.tfminecraft.companionpets.behavior.WaterEscape.needed(body)) {
            net.tfminecraft.companionpets.behavior.WaterEscape.swim(body, null);
            return;
        }
        body.getPathfinder().stopPathfinding();
        resetNative(body);
        var velocity = body.getVelocity();
        velocity.setX(0); velocity.setZ(0);
        body.setVelocity(velocity);
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
