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
                    Field wet = nativeType.getDeclaredField("isWet");
                    if (!wet.trySetAccessible()) throw new IllegalAccessException("isWet is inaccessible");
                    return new Access(handle, progress, wet,
                            nativeType.getMethod("handleEntityEvent", byte.class), level, broadcast);
                } catch (ReflectiveOperationException ex) {
                    org.bukkit.Bukkit.getLogger().warning("CompanionPets: cannot access native wolf wetness for " + type.getName() + "; shake deferral disabled: " + ex.getMessage());
                    return new Access(handle, progress, null, null, null, null);
                }
            } catch (ReflectiveOperationException ex) { return new Access(null, null, null, null, null, null); }
        }
    };

    public static boolean shaking(Wolf wolf) {
        return progress(wolf) > 0;
    }

    public static float progress(Wolf wolf) {
        Access access = ACCESS.get(wolf.getClass());
        if (access.handle == null) return 0;
        try {
            return ((Number) access.progress.invoke(access.handle.invoke(wolf), 1F)).floatValue();
        } catch (ReflectiveOperationException ex) { return 0; }
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

    /** A deferred shake ends dry; finishing an interaction must never re-wet the wolf. */
    public static void restore(Wolf wolf) {
        deferred.remove(wolf.getUniqueId());
    }

    public static void retain(Set<UUID> loaded) { deferred.retainAll(loaded); }
}
