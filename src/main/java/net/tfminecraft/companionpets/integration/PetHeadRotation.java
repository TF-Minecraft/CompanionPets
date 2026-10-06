package net.tfminecraft.companionpets.integration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.bukkit.entity.Mob;

/** Paper has a body-yaw API but no independent head-yaw setter. */
public final class PetHeadRotation {
    private PetHeadRotation() { }
    private record Access(Method handle, Method head, Method yaw, Method pitch, Field control) { }
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override protected Access computeValue(Class<?> type) {
            try {
                Method handle = type.getMethod("getHandle");
                return new Access(handle, handle.getReturnType().getMethod("setYHeadRot", float.class),
                        handle.getReturnType().getMethod("setYRot", float.class),
                        handle.getReturnType().getMethod("setXRot", float.class),
                        field(handle.getReturnType(), "bodyRotationControl"));
            } catch (ReflectiveOperationException ex) { return null; }
        }
    };
    private static final ClassValue<Field> STABLE_TIME = new ClassValue<>() {
        @Override protected Field computeValue(Class<?> type) {
            try { return field(type, "headStableTime"); }
            catch (ReflectiveOperationException ex) { return null; }
        }
    };

    public static boolean apply(Mob body, float bodyYaw, float headYaw, float pitch) {
        Access access = ACCESS.get(body.getClass());
        if (access == null) return false;
        try {
            Object handle = access.handle.invoke(body);
            Object control = access.control.get(handle);
            Field stable = STABLE_TIME.get(control.getClass());
            if (stable == null) return false;
            // Vanilla and ModelEngine's body controller both turn toward a stable
            // head after a delay. Restart that delay while this posture is held.
            stable.setInt(control, 0);
            access.yaw.invoke(handle, bodyYaw);
            access.pitch.invoke(handle, pitch);
            body.setBodyYaw(bodyYaw);
            access.head.invoke(handle, headYaw);
            return true;
        } catch (ReflectiveOperationException ex) { return false; }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
