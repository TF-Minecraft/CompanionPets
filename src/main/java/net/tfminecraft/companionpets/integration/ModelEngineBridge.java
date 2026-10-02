package net.tfminecraft.companionpets.integration;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.companionpets.config.PetAppearance;
import net.tfminecraft.companionpets.config.PetAppearance.Clip;
import net.tfminecraft.companionpets.visual.AnimationPlayer;
import net.tfminecraft.companionpets.visual.PetAnimation;

/** ModelEngine 4 API boundary. No ModelEngine classes are linked for vanilla servers. */
final class ModelEngineBridge {
    private static final NamespacedKey MODEL_KEY = new NamespacedKey("companionpets", "visual_model");
    private final Class<?> api = api("ModelEngineAPI");
    private final Class<?> modeled = api("model.ModeledEntity");
    private final Class<?> active = api("model.ActiveModel");
    private final Class<?> handler = api("animation.handler.AnimationHandler");
    private final Class<?> property = api("animation.property.IAnimationProperty");
    private final Class<?> state = api("animation.ModelState");
    private final Class<?> defaults = api("animation.handler.AnimationHandler$DefaultProperty");
    private final Class<?> loop = api("animation.BlueprintAnimation$LoopMode");
    private final Class<?> override = api("animation.BlueprintAnimation$OverrideMode");
    private final Class<?> blueprint = api("generator.blueprint.ModelBlueprint");
    private final Method play = method(handler, "playAnimation", String.class, double.class, double.class, double.class, boolean.class);
    private final Method stop = method(handler, "stopAnimation", String.class);
    private final Method playing = method(handler, "isPlayingAnimation", String.class);
    private final Method forceLoop = method(property, "setForceLoopMode", loop);
    private final Method forceOverride = method(property, "setForceOverride", override);
    private final Method destroyed = method(active, "isDestroyed");

    void registerInteractions(JavaPlugin plugin, BiConsumer<Player, Entity> interaction) {
        Class<?> eventType = api("events.BaseEntityInteractEvent");
        Class<?> baseType = api("entity.BaseEntity");
        Method getAction = method(eventType, "getAction");
        Method getSlot = method(eventType, "getSlot");
        Method getPlayer = method(eventType, "getPlayer");
        Method getBase = method(eventType, "getBaseEntity");
        Method getUuid = method(baseType, "getUUID");
        Listener listener = new Listener() { };
        Bukkit.getPluginManager().registerEvent(eventType.asSubclass(Event.class), listener, EventPriority.NORMAL,
                (ignored, event) -> {
                    String action = ((Enum<?>) call(getAction, event)).name();
                    if (!action.equals("INTERACT") && !action.equals("INTERACT_ON")) return;
                    if (call(getSlot, event) != org.bukkit.inventory.EquipmentSlot.HAND) return;
                    UUID uuid = (UUID) call(getUuid, call(getBase, event));
                    Entity entity = Bukkit.getEntity(uuid);
                    if (entity != null) interaction.accept((Player) call(getPlayer, event), entity);
                }, plugin);
    }

    void removeSaved(Entity entity, String desiredModel) {
        String previous = entity.getPersistentDataContainer().get(MODEL_KEY, PersistentDataType.STRING);
        if (previous != null && !previous.equals(desiredModel)) detach(entity, previous);
    }

    void detach(Entity entity, String id) {
        Object owner = unwrap(call(method(api, "getModeledEntity", UUID.class), null, entity.getUniqueId()));
        if (owner != null) {
            Object model = unwrap(call(method(modeled, "removeModel", String.class), owner, id));
            if (model != null) call(method(active, "destroy"), model);
            finishRemoval(owner, entity.isValid() && !entity.isDead());
        }
        entity.getPersistentDataContainer().remove(MODEL_KEY);
    }

    /** Destroy the complete modeled entity without force-spawning its hidden vanilla body. */
    void removeBody(Entity entity) {
        Object owner = unwrap(call(method(api, "getModeledEntity", UUID.class), null, entity.getUniqueId()));
        if (owner != null) destroyOwner(owner);
        entity.getPersistentDataContainer().remove(MODEL_KEY);
    }

    private void finishRemoval(Object owner, boolean restoreBase) {
        Map<?, ?> remaining = (Map<?, ?>) call(method(modeled, "getModels"), owner);
        if (!remaining.isEmpty()) return;
        if (restoreBase) call(method(modeled, "setBaseEntityVisible", boolean.class), owner, true);
        destroyOwner(owner);
    }

    private void destroyOwner(Object owner) {
        // This removes both registry entries and renderers synchronously on Paper.
        // removeModeledEntity(UUID) only marks removal for a later ModelEngine tick.
        call(method(modeled, "setSaved", boolean.class), owner, false);
        Object updaters = call(method(api, "getModelUpdaters"), call(method(api, "getAPI"), null));
        call(method(api("model.ModelUpdaters"), "forceRemoveModeledEntity", modeled), updaters, owner);
    }

    boolean modelAvailable(PetAppearance appearance) {
        return !appearance.modeled() || call(method(api, "getBlueprint", String.class), null, appearance.model()) != null;
    }

    java.util.Set<String> clips(PetAppearance appearance) {
        if (!appearance.modeled()) return java.util.Set.of();
        Object definition = call(method(api, "getBlueprint", String.class), null, appearance.model());
        if (definition == null) return java.util.Set.of();
        Map<?, ?> available = (Map<?, ?>) call(method(blueprint, "getAnimations"), definition);
        return available.keySet().stream().map(Object::toString).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    Attachment attach(Entity entity, PetAppearance appearance) {
        Object definition = call(method(api, "getBlueprint", String.class), null, appearance.model());
        if (definition == null) throw new IllegalStateException("ModelEngine model '" + appearance.model() + "' is not registered");
        Map<?, ?> available = (Map<?, ?>) call(method(blueprint, "getAnimations"), definition);
        Map<PetAnimation, Clip> clips = appearance.availableClips(available.keySet());
        Object owner = unwrap(call(method(api, "getModeledEntity", UUID.class), null, entity.getUniqueId()));
        if (owner == null) owner = call(method(api, "createModeledEntity", Entity.class), null, entity);
        Object model = unwrap(call(method(modeled, "getModel", String.class), owner, appearance.model()));
        boolean created = model == null;
        if (created) model = call(method(api, "createActiveModel", String.class), null, appearance.model());
        if (model == null) throw new IllegalStateException("ModelEngine could not create " + appearance.model());
        Attachment attachment = new Attachment(entity, owner, model, appearance.model(), clips, available);
        try {
            call(method(active, "setScale", double.class), model, appearance.scale());
            call(method(active, "setHitboxScale", double.class), model, appearance.scale());
            // ModelEngine uses this flag to send the interaction hitbox to clients.
            call(method(active, "setHitboxVisible", boolean.class), model, true);
            call(method(handler, "forceStopAllAnimations"), attachment.animationHandler);
            // We select movement and poses. ModelEngine retains its death renderer itself.
            for (Object value : state.getEnumConstants()) {
                Clip death = clips.get(PetAnimation.DEATH);
                String clip = ((Enum<?>) value).name().equals("DEATH") && death != null ? death.name() : "__companionpets_manual__";
                Object settings = construct(defaults, new Class<?>[]{state, String.class, double.class, double.class, double.class},
                        value, clip, death == null ? 0.15 : death.blend(), death == null ? 0.15 : death.blend(), death == null ? 1.0 : death.speed());
                call(method(handler, "setDefaultProperty", defaults), attachment.animationHandler, settings);
            }
            if (created) call(method(modeled, "addModel", active, boolean.class), owner, model, true);
            if (unwrap(call(method(modeled, "getModel", String.class), owner, appearance.model())) != model) {
                throw new IllegalStateException("ModelEngine attachment was rejected for " + appearance.model());
            }
            call(method(modeled, "setSaved", boolean.class), owner, true);
            call(method(modeled, "setBaseEntityVisible", boolean.class), owner, false);
            entity.getPersistentDataContainer().set(MODEL_KEY, PersistentDataType.STRING, appearance.model());
            return attachment;
        } catch (RuntimeException ex) {
            attachment.remove();
            throw ex;
        }
    }

    boolean hasClip(PetAppearance appearance, String clip) {
        if (!appearance.modeled() || clip == null || clip.isBlank()) return false;
        Object definition = call(method(api, "getBlueprint", String.class), null, appearance.model());
        return definition != null && ((Map<?, ?>) call(method(blueprint, "getAnimations"), definition)).containsKey(clip);
    }

    final class Attachment implements AnimationPlayer {
        final Entity entity;
        final Object owner;
        final Object model;
        final String id;
        final Object animationHandler;
        final Map<PetAnimation, Clip> clips;
        final Map<?, ?> available;

        Attachment(Entity entity, Object owner, Object model, String id, Map<PetAnimation, Clip> clips, Map<?, ?> available) {
            this.entity = entity;
            this.owner = owner;
            this.model = model;
            this.id = id;
            this.clips = Map.copyOf(clips);
            this.available = available;
            animationHandler = call(method(active, "getAnimationHandler"), model);
        }

        @Override
        public boolean play(Clip clip, boolean repeat) {
            return playMode(clip, repeat ? "LOOP" : "ONCE");
        }

        @Override public boolean hold(Clip clip) { return playMode(clip, "HOLD"); }
        @Override public double length(String clip) {
            Object animation = available.get(clip);
            return animation == null ? 0 : ((Number) call(method(api("animation.BlueprintAnimation"), "getLength"), animation)).doubleValue();
        }

        private boolean playMode(Clip clip, String mode) {
            Object result = call(play, animationHandler, clip.name(), clip.blend(), clip.blend(), clip.speed(), true);
            if (result == null) return false;
            call(forceLoop, result, enumValue(loop, mode));
            call(forceOverride, result, enumValue(override, "OVERRIDE"));
            return true;
        }

        @Override
        public void stop(String clip) { call(stop, animationHandler, clip); }

        @Override
        public boolean playing(String clip) { return Boolean.TRUE.equals(call(playing, animationHandler, clip)); }

        boolean valid() { return !Boolean.TRUE.equals(call(destroyed, model)); }

        void remove() {
            call(method(modeled, "removeModel", String.class), owner, id);
            call(method(active, "destroy"), model);
            finishRemoval(owner, entity.isValid() && !entity.isDead());
            entity.getPersistentDataContainer().remove(MODEL_KEY);
        }
    }

    private static Object unwrap(Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
    }

    private static Class<?> api(String name) {
        try {
            return Class.forName("com.ticxo.modelengine.api." + name);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("ModelEngine 4 API is unavailable", ex);
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unsupported ModelEngine API: " + type.getSimpleName() + "." + name, ex);
        }
    }

    private static Object call(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("ModelEngine call failed: " + method.getName(), ex);
        }
    }

    private static Object construct(Class<?> type, Class<?>[] signature, Object... args) {
        try {
            return type.getConstructor(signature).newInstance(args);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unsupported ModelEngine animation settings", ex);
        }
    }

    private static Object enumValue(Class<?> type, String value) {
        for (Object constant : type.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(value)) return constant;
        }
        throw new IllegalStateException("Unsupported ModelEngine value: " + value);
    }
}
