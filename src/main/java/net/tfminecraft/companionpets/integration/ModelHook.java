package net.tfminecraft.companionpets.integration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.visual.AnimationController;
import net.tfminecraft.companionpets.visual.PetAnimation;
import net.tfminecraft.companionpets.visual.PetVisual;

public final class ModelHook implements PetVisual {
    private final Logger logger;
    private final ModelEngineBridge bridge = new ModelEngineBridge();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<String> failedTypes = new HashSet<>();
    private final Set<String> warnedMotion = new HashSet<>();
    private final Map<String, Long> retryAfter = new HashMap<>();

    public ModelHook(Logger logger) { this.logger = logger; }

    public static boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("ModelEngine");
    }

    public void registerInteractions(JavaPlugin plugin, BiConsumer<Player, Entity> interaction) {
        bridge.registerInteractions(plugin, interaction);
    }

    @Override
    public void apply(Entity entity, PetTypeDef type) {
        if (entity == null || type == null) return;
        try {
            Session current = sessions.get(entity.getUniqueId());
            if (current != null && current.attachment.valid()) return;
            sessions.remove(entity.getUniqueId());
            bridge.removeSaved(entity, type.appearance().model());
            if (!type.appearance().modeled()) return;
            if (System.currentTimeMillis() < retryAfter.getOrDefault(type.id(), 0L)) return;
            var attachment = bridge.attach(entity, type.appearance());
            if ((!attachment.clips.containsKey(PetAnimation.IDLE) || !attachment.clips.containsKey(PetAnimation.WALK))
                    && warnedMotion.add(type.id())) logger.warning("Pet " + type.id() + ": model has no idle or walk clip; it may look static");
            var controller = new AnimationController(attachment, attachment.clips);
            sessions.put(entity.getUniqueId(), new Session(type.id(), attachment, controller));
            controller.update(PetAnimation.IDLE);
        } catch (RuntimeException ex) {
            try {
                if (type.appearance().modeled()) bridge.detach(entity, type.appearance().model());
            } catch (RuntimeException cleanup) {
                ex.addSuppressed(cleanup);
            }
            fail(entity, type.id(), ex);
        }
    }

    @Override
    public void update(Entity entity, PetTypeDef type, PetAnimation pose) {
        apply(entity, type);
        Session session = sessions.get(entity.getUniqueId());
        if (session == null) return;
        try {
            session.controller.update(pose);
        } catch (RuntimeException ex) {
            fail(entity, session.type, ex);
        }
    }

    @Override
    public boolean play(Entity entity, PetTypeDef type, String action) {
        if (entity == null || type == null) return false;
        PetAnimation animation;
        try {
            animation = PetAnimation.valueOf(action.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return false;
        }
        apply(entity, type);
        Session session = sessions.get(entity.getUniqueId());
        if (session == null) return false;
        try {
            return session.controller.play(animation);
        } catch (RuntimeException ex) {
            fail(entity, session.type, ex);
            return false;
        }
    }

    @Override
    public boolean attached(Entity entity) {
        return entity != null && sessions.containsKey(entity.getUniqueId());
    }

    @Override public boolean hasClip(Entity entity, PetTypeDef type, String clip) {
        try { return type != null && bridge.hasClip(type.appearance(), clip); }
        catch (RuntimeException ex) { return false; }
    }

    @Override public boolean playClip(Entity entity, PetTypeDef type, String clip, double duration) {
        if (entity == null || !hasClip(entity, type, clip)) return false;
        apply(entity, type);
        Session session = sessions.get(entity.getUniqueId());
        if (session == null) return false;
        try { return session.controller.playCustom(new net.tfminecraft.companionpets.config.PetAppearance.Clip(clip, 1, 0.15), duration); }
        catch (RuntimeException ex) { fail(entity, session.type, ex); return false; }
    }

    @Override public boolean belly(Entity entity) {
        Session session = entity == null ? null : sessions.get(entity.getUniqueId());
        return session != null && session.controller.belly();
    }

    @Override public boolean startBelly(Entity entity, PetTypeDef type, long idleMillis) {
        if (entity == null || type == null) return false;
        apply(entity, type);
        Session session = sessions.get(entity.getUniqueId());
        if (session == null) return false;
        try { return session.controller.startBelly(idleMillis); }
        catch (RuntimeException ex) { fail(entity, session.type, ex); return false; }
    }

    @Override public boolean rubBelly(Entity entity) {
        Session session = entity == null ? null : sessions.get(entity.getUniqueId());
        return session != null && session.controller.rubBelly();
    }

    @Override
    public boolean holdsMovement(Entity entity) {
        Session session = entity == null ? null : sessions.get(entity.getUniqueId());
        if (session == null) return false;
        try {
            return session.controller.holdsMovement();
        } catch (RuntimeException ex) {
            fail(entity, session.type, ex);
            return false;
        }
    }

    @Override
    public void cancelAction(Entity entity) {
        Session session = entity == null ? null : sessions.get(entity.getUniqueId());
        if (session == null) return;
        try {
            session.controller.cancelAction();
        } catch (RuntimeException ex) {
            fail(entity, session.type, ex);
        }
    }

    @Override
    public void remove(Entity entity) {
        if (entity == null) return;
        Session session = sessions.remove(entity.getUniqueId());
        if (session == null) return;
        try {
            session.attachment.remove();
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "Could not detach pet model " + session.type, ex);
        }
    }

    @Override
    public void retain(Set<UUID> loaded) {
        // Saved attachments survive chunk unloads; death renderers belong to ModelEngine.
        sessions.keySet().removeIf(id -> !loaded.contains(id));
    }

    @Override
    public void close() {
        for (Session session : java.util.List.copyOf(sessions.values())) remove(session.attachment.entity);
        failedTypes.clear();
        warnedMotion.clear();
        retryAfter.clear();
    }

    private void fail(Entity entity, String type, RuntimeException ex) {
        remove(entity);
        retryAfter.put(type, System.currentTimeMillis() + 30_000L);
        if (failedTypes.add(type)) logger.log(Level.WARNING, "Pet " + type + " is using its vanilla body: " + ex.getMessage(), ex);
    }

    private record Session(String type, ModelEngineBridge.Attachment attachment, AnimationController controller) { }
}
