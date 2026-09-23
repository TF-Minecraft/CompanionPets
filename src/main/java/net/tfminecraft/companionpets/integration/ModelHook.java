package net.tfminecraft.companionpets.integration;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Entity;

import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.visual.PetVisual;

public final class ModelHook implements PetVisual {
    private final Logger logger;
    private boolean logged;

    public ModelHook(Logger logger) {
        this.logger = logger;
    }

    public static boolean available() {
        try {
            Class.forName("com.ticxo.modelengine.api.ModelEngineAPI");
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    @Override
    public void apply(Entity entity, PetTypeDef type) {
        if (type == null || type.model() == null || entity == null) {
            return;
        }
        try {
            Object modeled = modeledEntity(entity, true);
            if (modeled == null) {
                return;
            }
            Object model = findModel(modeled, type.model());
            if (model != null) {
                return;
            }
            Class<?> api = Class.forName("com.ticxo.modelengine.api.ModelEngineAPI");
            Object created = api.getMethod("createActiveModel", String.class).invoke(null, type.model());
            if (created == null) {
                return;
            }
            if (!addModel(modeled, created)) {
                return;
            }
            markSaved(created, modeled);
        } catch (ReflectiveOperationException ex) {
            log(ex);
        }
    }

    @Override
    public void play(Entity entity, PetTypeDef type, String state) {
        if (type == null || entity == null || state == null) {
            return;
        }
        String animation = type.animations().get(state);
        if (animation == null || animation.isBlank()) {
            return;
        }
        try {
            Object modeled = modeledEntity(entity, false);
            if (modeled == null || type.model() == null) {
                return;
            }
            Object model = findModel(modeled, type.model());
            if (model == null) {
                return;
            }
            Object handler = model.getClass().getMethod("getAnimationHandler").invoke(model);
            Method play = handler.getClass().getMethod(
                    "playAnimation",
                    String.class,
                    double.class,
                    double.class,
                    double.class,
                    boolean.class);
            play.invoke(handler, animation, 0.2d, 0.2d, 1.0d, true);
        } catch (ReflectiveOperationException ex) {
            log(ex);
        }
    }

    private Object modeledEntity(Entity entity, boolean create) throws ReflectiveOperationException {
        Class<?> api = Class.forName("com.ticxo.modelengine.api.ModelEngineAPI");
        Object existing = invokeUuid(api, entity);
        if (existing == null && create) {
            existing = api.getMethod("createModeledEntity", Entity.class).invoke(null, entity);
        }
        if (existing instanceof Optional<?> optional) {
            return optional.orElse(null);
        }
        return existing;
    }

    private static Object invokeUuid(Class<?> api, Entity entity) {
        try {
            return api.getMethod("getModeledEntity", java.util.UUID.class).invoke(null, entity.getUniqueId());
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private static Object findModel(Object modeled, String blueprint) throws ReflectiveOperationException {
        Object found = modeled.getClass().getMethod("getModel", String.class).invoke(modeled, blueprint);
        if (found instanceof Optional<?> optional) {
            return optional.orElse(null);
        }
        return found;
    }

    private static boolean addModel(Object modeled, Object created) throws ReflectiveOperationException {
        for (Method method : modeled.getClass().getMethods()) {
            if (!method.getName().equals("addModel") || method.getParameterCount() != 2) {
                continue;
            }
            if (method.getParameterTypes()[1] != boolean.class) {
                continue;
            }
            if (!method.getParameterTypes()[0].isInstance(created)) {
                continue;
            }
            method.invoke(modeled, created, true);
            return true;
        }
        return false;
    }

    private static void markSaved(Object created, Object modeled) {
        try {
            created.getClass().getMethod("setSaved", boolean.class).invoke(created, true);
            return;
        } catch (ReflectiveOperationException ignored) {
            // Some builds save from the modeled entity instead of the active model.
        }
        try {
            modeled.getClass().getMethod("setSaved", boolean.class).invoke(modeled, true);
        } catch (ReflectiveOperationException ignored) {
            // The model still shows for this life of the entity.
        }
    }

    private void log(Exception ex) {
        if (!logged) {
            logged = true;
            logger.log(Level.WARNING, "ModelEngine hook failed", ex);
        }
    }
}
