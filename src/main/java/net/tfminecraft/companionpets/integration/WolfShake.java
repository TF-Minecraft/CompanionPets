package net.tfminecraft.companionpets.integration;

import java.lang.reflect.Method;
import org.bukkit.entity.Wolf;

/** Paper's public Wolf API exposes wetness, which ends after the shake/sound.
 * Read the native animation clock to start the modeled animation with the sound. */
public final class WolfShake {
    private WolfShake() { }
    private record Access(Method handle, Method progress) { }
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override protected Access computeValue(Class<?> type) {
            try {
                Method handle = type.getMethod("getHandle");
                return new Access(handle, handle.getReturnType().getMethod("getShakeAnim", float.class));
            } catch (ReflectiveOperationException ex) { return new Access(null, null); }
        }
    };

    public static boolean shaking(Wolf wolf) {
        Access access = ACCESS.get(wolf.getClass());
        if (access.handle == null) return false;
        try {
            return ((Number) access.progress.invoke(access.handle.invoke(wolf), 1F)).floatValue() > 0;
        } catch (ReflectiveOperationException ex) { return false; }
    }
}
