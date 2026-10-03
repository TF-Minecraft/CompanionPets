package net.tfminecraft.companionpets.integration;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Wolf;

/** Paper's public Wolf API exposes wetness, which ends after the shake/sound.
 * Read the native animation clock to start the modeled animation with the sound. */
public final class WolfShake {
    private WolfShake() { }
    private static final Set<UUID> deferred = new HashSet<>();
    private record Access(Method handle, Method progress, Field wet, Method cancel,
                          Method level, Method broadcast) { }
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override protected Access computeValue(Class<?> type) {
            try {
                Method handle = type.getMethod("getHandle");
                Class<?> nativeType = handle.getReturnType();
                Method progress = nativeType.getMethod("getShakeAnim", float.class);
                try {
                    Method level = nativeType.getMethod("level");
                    Method broadcast = java.util.Arrays.stream(level.getReturnType().getMethods())
                            .filter(m -> m.getName().equals("broadcastEntityEvent") && m.getParameterCount() == 2
                                    && m.getParameterTypes()[0].isAssignableFrom(nativeType)
                                    && m.getParameterTypes()[1] == byte.class).findFirst().orElse(null);
                    return new Access(handle, progress, nativeType.getField("isWet"),
                            nativeType.getMethod("handleEntityEvent", byte.class), level, broadcast);
                } catch (ReflectiveOperationException ex) {
                    return new Access(handle, progress, null, null, null, null);
                }
            } catch (ReflectiveOperationException ex) { return new Access(null, null, null, null, null, null); }
        }
    };

    public static boolean shaking(Wolf wolf) {
        Access access = ACCESS.get(wolf.getClass());
        if (access.handle == null) return false;
        try {
            return ((Number) access.progress.invoke(access.handle.invoke(wolf), 1F)).floatValue() > 0;
        } catch (ReflectiveOperationException ex) { return false; }
    }

    /** Clear the native shake clock before Wolf.aiStep can interrupt a fetch route. */
    public static boolean defer(Wolf wolf) {
        Access access = ACCESS.get(wolf.getClass());
        if (access.wet == null) return false;
        try {
            Object handle = access.handle.invoke(wolf);
            boolean shaking = ((Number) access.progress.invoke(handle, 1F)).floatValue() > 0;
            if (access.wet.getBoolean(handle) || shaking) deferred.add(wolf.getUniqueId());
            access.cancel.invoke(handle, (byte) 56);
            access.wet.setBoolean(handle, false);
            if (shaking && access.broadcast != null)
                access.broadcast.invoke(access.level.invoke(handle), handle, (byte) 56);
            return true;
        } catch (ReflectiveOperationException ex) { return false; }
    }

    /** Put wetness back once so vanilla can shake after the toy has been returned. */
    public static void restore(Wolf wolf) {
        if (!deferred.remove(wolf.getUniqueId())) return;
        Access access = ACCESS.get(wolf.getClass());
        if (access.wet == null) return;
        try { access.wet.setBoolean(access.handle.invoke(wolf), true); }
        catch (ReflectiveOperationException ignored) { }
    }

    public static void retain(Set<UUID> loaded) { deferred.retainAll(loaded); }
}
