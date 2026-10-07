package net.tfminecraft.companionpets.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import javax.tools.ToolProvider;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.config.PetAppearance;
import net.tfminecraft.companionpets.config.PetAppearance.Clip;
import net.tfminecraft.companionpets.visual.AnimationPlayer;
import net.tfminecraft.companionpets.visual.PetAnimation;
import net.tfminecraft.companionpets.visual.PetVisual;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Quaternionf;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

class ModelEngineBridgeTest {
    @TempDir static Path classes;
    private static final String API = "com.ticxo.modelengine.api.";
    private static final NamespacedKey MODEL_KEY = new NamespacedKey("companionpets", "visual_model");
    private Provider provider;
    private Object bridge;
    private Entity body;
    private Player player;
    private JavaPlugin plugin;
    private PetAppearance appearance;

    @BeforeAll static void compileProvider() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        // Signatures follow the ModelEngine 4 API. State/counters belong only to these test doubles.
        sources.put("ModelEngineAPI", """
                import java.util.*;
                import org.bukkit.entity.*;
                import com.ticxo.modelengine.api.model.*;
                import com.ticxo.modelengine.api.generator.blueprint.ModelBlueprint;
                public class ModelEngineAPI {
                    public static final Map<String, ModelBlueprint> blueprints = new LinkedHashMap<>();
                    public static final Map<UUID, ModeledEntity> owners = new LinkedHashMap<>();
                    public static final List<ActiveModel> created = new ArrayList<>();
                    public static final List<String> events = new ArrayList<>();
                    public static boolean nullModel, rejectModel, failHandler, failScale, failPlay, failDefaults, failRemoval;
                    public static boolean failBlueprint, failStop, failPlaying, failHead, failLock, failTail, failTailAdvance;
                    public static ModelBlueprint getBlueprint(String id) {
                        if (failBlueprint) throw new IllegalStateException("blueprint lookup failed");
                        return blueprints.get(id);
                    }
                    public static ModeledEntity getModeledEntity(UUID id) { return owners.get(id); }
                    public static ModeledEntity createModeledEntity(Entity entity) {
                        var result = new ModeledEntity(entity); owners.put(entity.getUniqueId(), result); return result;
                    }
                    public static ActiveModel createActiveModel(String id) {
                        if (nullModel) return null;
                        var result = new ActiveModel(id); created.add(result); return result;
                    }
                    public static ModelEngineAPI getAPI() { return new ModelEngineAPI(); }
                    public ModelUpdaters getModelUpdaters() { return new ModelUpdaters(); }
                    public static void blueprint(String id, String... clips) {
                        var result = new ModelBlueprint();
                        for (String clip : clips) result.animations.put(clip, new com.ticxo.modelengine.api.animation.BlueprintAnimation(1.25));
                        for (String bone : List.of("body", "tail", "tail_tip", "ear", "tail2")) result.bones.put(bone, new Object());
                        blueprints.put(id, result);
                    }
                    public static org.bukkit.event.Event interaction(Player player, UUID id, String action, org.bukkit.inventory.EquipmentSlot slot) {
                        return new com.ticxo.modelengine.api.events.BaseEntityInteractEvent(player,
                            new com.ticxo.modelengine.api.entity.BaseEntity(id), action, slot);
                    }
                }
                """);
        sources.put("generator.blueprint.ModelBlueprint", """
                import java.util.*;
                import com.ticxo.modelengine.api.animation.BlueprintAnimation;
                public class ModelBlueprint {
                    public final Map<String, BlueprintAnimation> animations = new LinkedHashMap<>();
                    public final Map<String, Object> bones = new LinkedHashMap<>();
                    public Map<String, BlueprintAnimation> getAnimations() { return animations; }
                    public Map<String, Object> getFlatMap() { return bones; }
                }
                """);
        sources.put("entity.BaseEntity", """
                import java.util.UUID;
                import com.ticxo.modelengine.api.entity.data.IEntityData;
                public class BaseEntity {
                    public final UUID id;
                    public final IEntityData data = new IEntityData();
                    public BaseEntity(UUID id) { this.id = id; }
                    public UUID getUUID() { return id; }
                    public IEntityData getData() { return data; }
                }
                """);
        sources.put("entity.data.IEntityData", """
                public class IEntityData {
                    public Boolean backCull;
                    public void setBackCull(Boolean value) { backCull = value; }
                }
                """);
        sources.put("model.ModeledEntity", """
                import java.util.*;
                import org.bukkit.entity.Entity;
                import com.ticxo.modelengine.api.ModelEngineAPI;
                import com.ticxo.modelengine.api.entity.BaseEntity;
                public class ModeledEntity {
                    public final BaseEntity base;
                    public final Map<String, ActiveModel> models = new LinkedHashMap<>();
                    public boolean saved, visible = true, removed;
                    public ModeledEntity(Entity entity) { base = new BaseEntity(entity.getUniqueId()); }
                    public BaseEntity getBase() { return base; }
                    public Optional<ActiveModel> getModel(String id) { return Optional.ofNullable(models.get(id)); }
                    public Optional<ActiveModel> addModel(ActiveModel model, boolean mainHitbox) {
                        if (ModelEngineAPI.rejectModel) return Optional.empty();
                        return Optional.ofNullable(models.put(model.id, model));
                    }
                    public Optional<ActiveModel> removeModel(String id) { return Optional.ofNullable(models.remove(id)); }
                    public Map<String, ActiveModel> getModels() { return models; }
                    public void setSaved(boolean value) { saved = value; }
                    public void setBaseEntityVisible(boolean value) {
                        visible = value; ModelEngineAPI.events.add("visible:" + value);
                    }
                }
                """);
        sources.put("model.ModelUpdaters", """
                import com.ticxo.modelengine.api.ModelEngineAPI;
                public class ModelUpdaters {
                    public void forceRemoveModeledEntity(ModeledEntity owner) {
                        if (ModelEngineAPI.failRemoval) throw new IllegalStateException("renderer removal failed");
                        owner.removed = true;
                        owner.models.values().forEach(ActiveModel::destroy);
                        owner.models.clear();
                        ModelEngineAPI.owners.remove(owner.base.id);
                    }
                }
                """);
        sources.put("model.ActiveModel", """
                import java.util.*;
                import com.ticxo.modelengine.api.ModelEngineAPI;
                import com.ticxo.modelengine.api.animation.handler.AnimationHandler;
                import com.ticxo.modelengine.api.model.bone.ModelBone;
                public class ActiveModel {
                    public final String id;
                    public final AnimationHandler handler = new AnimationHandler();
                    public final Map<String, ModelBone> bones = new LinkedHashMap<>();
                    public boolean destroyed, hitboxVisible;
                    public Boolean rotationLock;
                    public float bodyYaw, headYaw, pitch;
                    public double scale, hitboxScale;
                    public ActiveModel(String id) {
                        this.id = id;
                        var root = new ModelBone("body", null);
                        var tail = new ModelBone("tail", root);
                        bones.put("body", root); bones.put("tail", tail);
                        bones.put("tail_tip", new ModelBone("tail_tip", tail));
                        bones.put("ear", new ModelBone("ear", root));
                        bones.put("tail2", new ModelBone("tail2", root));
                    }
                    public AnimationHandler getAnimationHandler() {
                        if (ModelEngineAPI.failHandler) throw new IllegalStateException("animation handler failed");
                        return handler;
                    }
                    public Map<String, ModelBone> getBones() { return bones; }
                    public boolean isDestroyed() { return destroyed; }
                    public void destroy() { destroyed = true; }
                    public boolean isModelRotationLocked() { return Boolean.TRUE.equals(rotationLock); }
                    public void setModelRotationLocked(Boolean value) {
                        if (ModelEngineAPI.failLock) throw new IllegalStateException("rotation lock failed");
                        rotationLock = value;
                    }
                    public void setLockedYBodyRot(float value) {
                        if (ModelEngineAPI.failHead) throw new IllegalStateException("head rotation failed");
                        bodyYaw = value;
                    }
                    public void setLockedYHeadRot(float value) { headYaw = value; }
                    public void setLockedXHeadRot(float value) { pitch = value; }
                    public void setScale(double value) {
                        if (ModelEngineAPI.failScale) throw new IllegalStateException("scale failed");
                        scale = value;
                    }
                    public void setHitboxScale(double value) { hitboxScale = value; }
                    public void setHitboxVisible(boolean value) { hitboxVisible = value; }
                }
                """);
        sources.put("animation.BlueprintAnimation", """
                public class BlueprintAnimation {
                    private final double length;
                    public BlueprintAnimation(double length) { this.length = length; }
                    public double getLength() { return length; }
                    public enum LoopMode { ONCE, HOLD, LOOP }
                    public enum OverrideMode { NONE, OVERRIDE }
                }
                """);
        sources.put("animation.ModelState", """
                public enum ModelState { IDLE, WALK, STRAFE, JUMP_START, JUMP, JUMP_END, HOVER, FLY, SPAWN, DEATH }
                """);
        sources.put("animation.property.IAnimationProperty", """
                import com.ticxo.modelengine.api.animation.BlueprintAnimation.*;
                public class IAnimationProperty {
                    public LoopMode loop;
                    public OverrideMode override;
                    public void setForceLoopMode(LoopMode value) { loop = value; }
                    public void setForceOverride(OverrideMode value) { override = value; }
                }
                """);
        sources.put("animation.handler.AnimationHandler", """
                import java.util.*;
                import com.ticxo.modelengine.api.ModelEngineAPI;
                import com.ticxo.modelengine.api.animation.ModelState;
                import com.ticxo.modelengine.api.animation.property.IAnimationProperty;
                public class AnimationHandler {
                    public final Map<String, IAnimationProperty> playing = new LinkedHashMap<>();
                    public final Map<String, DefaultProperty> defaults = new LinkedHashMap<>();
                    public double blendIn, blendOut, speed;
                    public boolean forced;
                    public int resets;
                    public IAnimationProperty playAnimation(String id, double in, double out, double speed, boolean force) {
                        if (ModelEngineAPI.failPlay) throw new IllegalStateException("animation failed");
                        if (id.equals("rejected")) return null;
                        blendIn = in; blendOut = out; this.speed = speed; forced = force;
                        var property = new IAnimationProperty(); playing.put(id, property); return property;
                    }
                    public void stopAnimation(String id) {
                        if (ModelEngineAPI.failStop) throw new IllegalStateException("animation stop failed");
                        playing.remove(id);
                    }
                    public boolean isPlayingAnimation(String id) {
                        if (ModelEngineAPI.failPlaying) throw new IllegalStateException("animation status failed");
                        return playing.containsKey(id);
                    }
                    public void forceStopAllAnimations() { resets++; playing.clear(); }
                    public void setDefaultProperty(DefaultProperty value) { defaults.put(value.state.name(), value); }
                    public static class DefaultProperty {
                        public final ModelState state;
                        public final String name;
                        public final double in, out, speed;
                        public DefaultProperty(ModelState state, String name, double in, double out, double speed) {
                            if (ModelEngineAPI.failDefaults) throw new IllegalStateException("default settings failed");
                            this.state = state; this.name = name; this.in = in; this.out = out; this.speed = speed;
                        }
                    }
                }
                """);
        sources.put("model.bone.ManualAnimator", "public interface ManualAnimator { }");
        sources.put("model.bone.ModelBone", """
                import org.joml.Quaternionf;
                public class ModelBone {
                    public final String id;
                    public final ModelBone parent;
                    public final Quaternionf rotation = new Quaternionf().rotateX(0.2f);
                    public ManualAnimator animator = new ManualAnimator() { };
                    public ModelBone(String id, ModelBone parent) { this.id = id; this.parent = parent; }
                    public String getBoneId() { return id; }
                    public ModelBone getParent() { return parent; }
                    public ManualAnimator getManualAnimator() { return animator; }
                    public void setManualAnimator(ManualAnimator value) {
                        if (com.ticxo.modelengine.api.ModelEngineAPI.failTail) throw new IllegalStateException("tail animator failed");
                        animator = value;
                    }
                }
                """);
        sources.put("model.bone.SimpleManualAnimator", """
                import org.joml.Quaternionf;
                public class SimpleManualAnimator implements ManualAnimator {
                    private final Quaternionf rotation;
                    public SimpleManualAnimator(ModelBone bone) {
                        rotation = new Quaternionf(bone.rotation) {
                            @Override public Quaternionf set(org.joml.Quaternionfc value) {
                                if (com.ticxo.modelengine.api.ModelEngineAPI.failTailAdvance) throw new IllegalStateException("tail rotation failed");
                                return super.set(value);
                            }
                        };
                    }
                    public Quaternionf getRotation() { return rotation; }
                }
                """);
        sources.put("events.BaseEntityInteractEvent", """
                import org.bukkit.entity.Player;
                import org.bukkit.event.*;
                import org.bukkit.inventory.EquipmentSlot;
                import com.ticxo.modelengine.api.entity.BaseEntity;
                public class BaseEntityInteractEvent extends Event implements Cancellable {
                    private boolean cancelled;
                    private static final HandlerList HANDLERS = new HandlerList();
                    private final Player player;
                    private final BaseEntity base;
                    private final Action action;
                    private final EquipmentSlot slot;
                    public enum Action { ATTACK, INTERACT, INTERACT_ON }
                    public BaseEntityInteractEvent(Player player, BaseEntity base, String action, EquipmentSlot slot) {
                        this.player = player; this.base = base; this.action = Action.valueOf(action); this.slot = slot;
                    }
                    public boolean isCancelled() { return cancelled; }
                    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
                    public Player getPlayer() { return player; }
                    public BaseEntity getBaseEntity() { return base; }
                    public Action getAction() { return action; }
                    public EquipmentSlot getSlot() { return slot; }
                    public HandlerList getHandlers() { return HANDLERS; }
                    public static HandlerList getHandlerList() { return HANDLERS; }
                }
                """);
        List<String> args = new ArrayList<>(List.of("-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()));
        for (var entry : sources.entrySet()) {
            String name = API + entry.getKey();
            Path file = classes.resolve(name.replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "package " + name.substring(0, name.lastIndexOf('.')) + ";\n" + entry.getValue());
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        compileVariant("model.ActiveModel", sources.get("model.ActiveModel").replace("setLockedYBodyRot", "legacyLockedBody"), "missing-method");
        compileVariant("animation.BlueprintAnimation", sources.get("animation.BlueprintAnimation").replace("ONCE, HOLD, LOOP", "HOLD, LOOP"), "missing-enum");
    }

    private static void compileVariant(String suffix, String source, String variant) throws IOException {
        String name = API + suffix;
        Path output = classes.resolve(variant);
        Path file = output.resolve(name.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package " + name.substring(0, name.lastIndexOf('.')) + ";\n" + source);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath",
                classes + java.io.File.pathSeparator + System.getProperty("java.class.path"), "-d", output.toString(), file.toString()));
    }

    @BeforeEach void setup() throws Exception {
        var server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        var world = server.addSimpleWorld("models");
        body = world.spawn(new org.bukkit.Location(world, 0, 64, 0), org.bukkit.entity.Wolf.class);
        provider = new Provider(Set.of());
        bridge = provider.bridge();
        provider.api("blueprint", "beagle", new String[]{"idle", "walk", "sit", "paw", "head_tilt", "lie_back", "belly_up", "get_up", "custom"});
        var clips = new EnumMap<PetAnimation, Clip>(PetAnimation.class);
        for (PetAnimation animation : PetAnimation.values()) clips.put(animation, new Clip(animation.name().toLowerCase(Locale.ROOT), 1.2, 0.25));
        appearance = new PetAppearance("beagle", 0.8, clips);
    }

    @AfterEach void cleanup() throws Exception {
        MockBukkit.unmock();
        if (provider != null) provider.close();
    }

    private Object attach() throws Exception { return invoke(bridge, "attach", body, appearance); }
    private Object owner() throws Exception { return provider.api("getModeledEntity", body.getUniqueId()); }
    private Object model() throws Exception { return ((Optional<?>) invoke(owner(), "getModel", "beagle")).orElseThrow(); }
    private List<?> created() throws Exception { return (List<?>) provider.value("created"); }
    private List<?> events() throws Exception { return (List<?>) provider.value("events"); }
    private String savedModel() { return body.getPersistentDataContainer().get(MODEL_KEY, PersistentDataType.STRING); }
    private static Object field(Object object, String name) throws Exception { return object.getClass().getField(name).get(object); }
    private static Map<?, ?> map(Object object, String name) throws Exception { return (Map<?, ?>) field(object, name); }
    private static Object invoke(Object target, String name, Object... args) throws Exception {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        var method = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(name) && m.getParameterCount() == args.length).findFirst().orElseThrow();
        method.setAccessible(true);
        try { return method.invoke(target instanceof Class<?> ? null : target, args); }
        catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception cause) throw cause;
            if (ex.getCause() instanceof Error cause) throw cause;
            throw ex;
        }
    }

    @Test void attachmentConfiguresModelAndNativeStatesWithoutDuplicatingSavedModels() throws Exception {
        Object attachment = attach(), owner = owner(), model = model(), handler = field(model, "handler");
        assertEquals("beagle", savedModel());
        assertEquals(0.8, field(model, "scale"));
        assertEquals(0.8, field(model, "hitboxScale"));
        assertEquals(true, field(model, "hitboxVisible"));
        assertNull(field(model, "rotationLock"));
        assertEquals(false, field(field(field(owner, "base"), "data"), "backCull"));
        assertEquals(true, field(owner, "saved"));
        assertEquals(false, field(owner, "visible"));
        assertEquals("idle", field(map(handler, "defaults").get("IDLE"), "name"));
        assertEquals("walk", field(map(handler, "defaults").get("STRAFE"), "name"));
        assertEquals("__companionpets_manual__", field(map(handler, "defaults").get("SPAWN"), "name"));
        assertEquals(1.2, field(map(handler, "defaults").get("WALK"), "speed"));
        assertTrue((boolean) invoke(attachment, "valid"));
        invoke(bridge, "attach", body, appearance);
        assertSame(owner, owner()); assertSame(model, model());
        assertEquals(1, created().size());
        assertEquals(2, field(handler, "resets"));
    }

    @Test void blueprintQueriesDescribeAvailableClipsBonesAndMissingOrVanillaModels() throws Exception {
        assertTrue((boolean) invoke(bridge, "modelAvailable", appearance));
        assertTrue(((Set<?>) invoke(bridge, "clips", appearance)).contains("paw"));
        assertTrue(((Set<?>) invoke(bridge, "bones", appearance)).contains("tail"));
        assertTrue((boolean) invoke(bridge, "hasClip", appearance, "custom"));
        assertFalse((boolean) invoke(bridge, "hasClip", appearance, "unknown"));
        assertFalse((boolean) invoke(bridge, "hasClip", appearance, ""));
        assertFalse((boolean) invoke(bridge, "hasClip", appearance, null));
        var missing = new PetAppearance("missing", 1, Map.of());
        assertFalse((boolean) invoke(bridge, "modelAvailable", missing));
        assertEquals(Set.of(), invoke(bridge, "clips", missing));
        assertEquals(Set.of(), invoke(bridge, "bones", missing));
        assertFalse((boolean) invoke(bridge, "hasClip", missing, "paw"));
        assertThrows(IllegalStateException.class, () -> invoke(bridge, "attach", body, missing));
        assertNull(owner());
        assertTrue((boolean) invoke(bridge, "modelAvailable", PetAppearance.VANILLA));
        assertEquals(Set.of(), invoke(bridge, "clips", PetAppearance.VANILLA));
        assertEquals(Set.of(), invoke(bridge, "bones", PetAppearance.VANILLA));
        assertFalse((boolean) invoke(bridge, "hasClip", PetAppearance.VANILLA, "idle"));
    }

    @Test void playbackPreservesBlendSpeedLoopHoldAndProviderRejection() throws Exception {
        var attachment = (AnimationPlayer) attach();
        Object handler = field(model(), "handler");
        var clip = new Clip("paw", 1.4, 0.3);
        assertTrue(attachment.play(clip, false));
        assertEquals("ONCE", field(map(handler, "playing").get("paw"), "loop").toString());
        assertEquals("OVERRIDE", field(map(handler, "playing").get("paw"), "override").toString());
        assertEquals(0.3, field(handler, "blendIn")); assertEquals(0.3, field(handler, "blendOut"));
        assertEquals(1.4, field(handler, "speed")); assertEquals(true, field(handler, "forced"));
        assertTrue(attachment.play(clip, true));
        assertEquals("LOOP", field(map(handler, "playing").get("paw"), "loop").toString());
        assertTrue(attachment.hold(clip));
        assertEquals("HOLD", field(map(handler, "playing").get("paw"), "loop").toString());
        assertTrue(attachment.playing("paw"));
        attachment.stop("paw"); assertFalse(attachment.playing("paw"));
        assertEquals(1.25, attachment.length("paw")); assertEquals(0, attachment.length("absent"));
        assertFalse(attachment.play(new Clip("rejected", 1, 0), false));
    }

    @Test void headLookingRestoresTheOriginalLockAfterSeveralUpdates() throws Exception {
        Object attachment = attach(), model = model();
        invoke(attachment, "releaseHeadLook");
        invoke(attachment, "holdHeadLook", 20F, 35F, -12F);
        assertEquals(true, field(model, "rotationLock"));
        assertEquals(20F, field(model, "bodyYaw")); assertEquals(35F, field(model, "headYaw")); assertEquals(-12F, field(model, "pitch"));
        invoke(attachment, "holdHeadLook", 25F, 40F, -10F);
        invoke(attachment, "releaseHeadLook");
        assertEquals(false, field(model, "rotationLock"));
        invoke(model, "setModelRotationLocked", true);
        invoke(attachment, "holdHeadLook", 5F, 7F, 1F);
        invoke(attachment, "releaseHeadLook");
        assertEquals(true, field(model, "rotationLock"));
    }

    @Test void tailsReplaceOnlyRootTailAnimatorsAndRestoreAllOriginalInstances() throws Exception {
        Object attachment = attach(), model = model();
        Map<?, ?> bones = map(model, "bones");
        Map<Object, Object> before = new IdentityHashMap<>();
        for (Object bone : bones.values()) before.put(bone, field(bone, "animator"));
        invoke(attachment, "advanceTail");
        assertFalse((boolean) invoke(attachment, "wagging"));
        invoke(attachment, "wagTail", 4D);
        assertTrue((boolean) invoke(attachment, "wagging"));
        Object animator = field(bones.get("tail"), "animator");
        assertNotSame(before.get(bones.get("tail")), animator);
        assertNotSame(before.get(bones.get("tail2")), field(bones.get("tail2"), "animator"));
        assertSame(before.get(bones.get("tail_tip")), field(bones.get("tail_tip"), "animator"));
        assertSame(before.get(bones.get("ear")), field(bones.get("ear"), "animator"));
        invoke(attachment, "wagTail", 5D);
        assertSame(animator, field(bones.get("tail"), "animator"), "Changing rate must retain the current animator");
        invoke(attachment, "advanceTail");
        Quaternionf rotation = (Quaternionf) invoke(animator, "getRotation");
        assertEquals(1F, rotation.lengthSquared(), 0.0001F);
        assertNotEquals(0F, rotation.x, "Wagging preserves the pre-existing pitch");
        invoke(attachment, "wagTail", 0D);
        for (Object bone : bones.values()) assertSame(before.get(bone), field(bone, "animator"));
        assertFalse((boolean) invoke(attachment, "wagging"));
        invoke(attachment, "wagTail", 0D);
    }

    @Test void removingOneModelPreservesOthersButRemovingTheLastRestoresVisibleBody() throws Exception {
        Object attachment = attach(), owner = owner(), petModel = model();
        Object other = provider.api("createActiveModel", "accessory");
        invoke(owner, "addModel", other, false);
        invoke(attachment, "remove");
        assertEquals(true, field(petModel, "destroyed"));
        assertFalse((boolean) invoke(attachment, "valid"));
        assertSame(owner, owner());
        assertEquals(false, field(owner, "visible"));
        assertEquals(false, field(other, "destroyed"));
        assertNull(savedModel());
        invoke(bridge, "detach", body, "accessory");
        assertNull(owner());
        assertEquals(true, field(other, "destroyed"));
        assertEquals(true, field(owner, "visible"));
        assertEquals(false, field(owner, "saved"));
    }

    @Test void changedSavedModelDetachesButSameModelAndAbsentTagsAreLeftAlone() throws Exception {
        attach(); Object owner = owner(), model = model();
        invoke(bridge, "removeSaved", body, "beagle");
        assertSame(model, model());
        invoke(bridge, "removeSaved", body, "cat");
        assertEquals(true, field(model, "destroyed")); assertEquals(true, field(owner, "visible"));
        assertNull(owner()); assertNull(savedModel());
        invoke(bridge, "removeSaved", body, "cat");
        invoke(bridge, "detach", body, "beagle");
    }

    @Test void bodyDeletionAndInvalidBodyDetachNeverRevealTheVanillaBody() throws Exception {
        attach(); Object owner = owner(), model = model();
        events().clear();
        invoke(bridge, "removeBody", body);
        assertNull(owner()); assertNull(savedModel());
        assertEquals(true, field(model, "destroyed"));
        assertEquals(false, field(owner, "visible"));
        assertFalse(events().contains("visible:true"));
        invoke(bridge, "removeBody", body);
        attach(); Object secondOwner = owner();
        body.remove();
        invoke(bridge, "detach", body, "beagle");
        assertNull(owner()); assertEquals(false, field(secondOwner, "visible"));
    }

    @Test void rejectedAttachmentAndConfigurationFailuresCleanUpModelsAndOwner() throws Exception {
        for (String flag : List.of("rejectModel", "failScale", "failDefaults")) {
            provider.flag(flag, true);
            assertThrows(IllegalStateException.class, this::attach, flag);
            assertNull(owner(), flag); assertNull(savedModel(), flag);
            assertEquals(true, field(created().getLast(), "destroyed"), flag);
            provider.flag(flag, false);
        }
    }

    @Test void earlyHandlerFailureAlsoDestroysTheUnattachedModel() throws Exception {
        PetVisual hook = provider.hook();
        provider.flag("failHandler", true);
        hook.apply(body, type());
        assertFalse(hook.attached(body));
        assertNull(owner()); assertNull(savedModel());
        assertEquals(true, field(created().getLast(), "destroyed"), "A model created before handler failure must not leak");
    }

    @Test void missingProviderFailsExplicitlyAndFailedModelCreationFallsBackCleanly() throws Exception {
        try (var absent = new Provider(Set.of(API + "ModelEngineAPI"))) {
            assertThrows(IllegalStateException.class, absent::bridge);
        }
        provider.flag("nullModel", true);
        PetVisual hook = provider.hook();
        hook.apply(body, type());
        assertFalse(hook.attached(body));
        assertNull(owner()); assertNull(savedModel());
        assertTrue(created().isEmpty());
    }

    @Test void incompatibleProviderMethodsAndLoopModesFailWithSpecificDiagnostics() throws Exception {
        try (var incompatible = new Provider(Set.of(), classes.resolve("missing-method"))) {
            var failure = assertThrows(IllegalStateException.class, incompatible::bridge);
            assertTrue(failure.getMessage().contains("ActiveModel.setLockedYBodyRot"));
        }
        try (var incompatible = new Provider(Set.of(), classes.resolve("missing-enum"))) {
            Object otherBridge = incompatible.bridge();
            incompatible.api("blueprint", "beagle", new String[]{"idle", "walk", "paw"});
            var attachment = (AnimationPlayer) invoke(otherBridge, "attach", body, appearance);
            var failure = assertThrows(IllegalStateException.class, () -> attachment.play(new Clip("paw", 1, 0.1), false));
            assertEquals("Unsupported ModelEngine value: ONCE", failure.getMessage());
            invoke(attachment, "remove");
            assertNull(incompatible.api("getModeledEntity", body.getUniqueId()));
        }
    }

    @Test void modelInteractionsDispatchOnlyMainHandRightClicksForExistingBodies() throws Exception {
        var calls = new ArrayList<Entity>();
        BiConsumer<Player, Entity> consumer = (actor, target) -> { assertSame(player, actor); calls.add(target); };
        invoke(bridge, "registerInteractions", plugin, consumer);
        for (String action : List.of("INTERACT", "INTERACT_ON", "ATTACK")) {
            for (EquipmentSlot slot : List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
                Event event = (Event) provider.api("interaction", player, body.getUniqueId(), action, slot);
                org.bukkit.Bukkit.getPluginManager().callEvent(event);
            }
        }
        org.bukkit.Bukkit.getPluginManager().callEvent((Event) provider.api("interaction", player, UUID.randomUUID(), "INTERACT", EquipmentSlot.HAND));
        assertEquals(List.of(body, body), calls);
    }

    private net.tfminecraft.companionpets.config.PetTypeDef type() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("pets: {wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG, model: beagle}}");
        return CompanionConfig.load(plugin, yaml).type("wolf");
    }

    @Test void hookDrivesRealControllersAndRetainsSavedAttachmentsAcrossUnloading() throws Exception {
        PetVisual hook = provider.hook(); var type = type();
        hook.apply(body, type); assertTrue(hook.attached(body));
        assertTrue(hook.modelAvailable(type)); assertTrue(hook.hasTail(type)); assertTrue(hook.clips(type).contains("paw"));
        hook.update(body, type, PetAnimation.SIT);
        assertTrue(map(field(model(), "handler"), "playing").containsKey("sit"));
        assertTrue(hook.play(body, type, "paw"));
        assertFalse(hook.play(body, type, "unknown"));
        hook.cancelAction(body);
        assertTrue(hook.hasClip(body, type, "custom"));
        assertTrue(hook.playClip(body, type, "custom", 2));
        assertTrue(hook.holdsMovement(body)); hook.cancelAction(body);
        hook.trainingAttention(body, type, true);
        assertTrue(map(field(model(), "handler"), "playing").containsKey("head_tilt"));
        hook.trainingAttention(body, type, false);
        assertTrue(hook.startBelly(body, type, 2_000)); assertTrue(hook.belly(body)); assertTrue(hook.rubBelly(body));
        hook.cancelAction(body); assertFalse(hook.belly(body));
        hook.holdHeadLook(body, 20, 30, 5); hook.releaseHeadLook(body);
        hook.wagTail(body, 4); hook.animateTails();
        Object saved = owner();
        hook.retain(Set.of());
        assertFalse(hook.attached(body)); assertSame(saved, owner());
        hook.apply(body, type); assertTrue(hook.attached(body)); assertEquals(1, created().size());
        hook.close(); assertNull(owner()); assertFalse(hook.attached(body));
    }

    @Test void hookFailureFallsBackAndRateLimitsRetriesWhileDeletionStillRemovesBody() throws Exception {
        PetVisual hook = provider.hook(); var type = type();
        hook.apply(body, type); Object model = model();
        provider.flag("failPlay", true);
        assertFalse(hook.play(body, type, "paw"));
        assertFalse(hook.attached(body)); assertNull(owner()); assertEquals(true, field(model, "destroyed"));
        provider.flag("failPlay", false);
        hook.apply(body, type);
        assertEquals(1, created().size(), "Do not immediately retry a failed type");
        hook.close(); hook.apply(body, type);
        assertEquals(2, created().size());
        provider.flag("failRemoval", true);
        hook.removeBody(body);
        assertFalse(body.isValid(), "Renderer failure must not leave the physical companion behind");
        assertFalse(hook.attached(body));
    }

    @Test void hookDeletesTheRendererAndPhysicalBodyTogether() throws Exception {
        PetVisual hook = provider.hook();
        hook.apply(body, type());
        Object renderer = model();
        Object owner = owner();
        events().clear();

        hook.removeBody(body);

        assertFalse(body.isValid());
        assertFalse(hook.attached(body));
        assertNull(owner());
        assertNull(savedModel());
        assertEquals(true, field(renderer, "destroyed"));
        assertEquals(false, field(owner, "visible"));
        assertFalse(events().contains("visible:true"));
        hook.close();
    }

    @Test void hookReplacesARendererDestroyedByTheProvider() throws Exception {
        PetVisual hook = provider.hook();
        var type = type();
        hook.apply(body, type);
        Object destroyed = model();
        invoke(destroyed, "destroy");
        assertEquals(true, field(destroyed, "destroyed"));

        hook.apply(body, type);

        assertNotSame(destroyed, model(), "a renderer destroyed outside the hook must be replaced");
        assertEquals(false, field(model(), "destroyed"));
        assertEquals(2, created().size());
        assertTrue(hook.attached(body));
        hook.update(body, type, PetAnimation.SIT);
        assertTrue(map(field(model(), "handler"), "playing").containsKey("sit"));
        hook.close();
        assertNull(owner());
    }

    private PetVisual hookWithLogs(List<LogRecord> records) throws Exception {
        var logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        return provider.hook(logger);
    }

    @Test void hookAvailabilityTracksProviderEnablementAndInteractionUsesThePublicHook() throws Exception {
        assertFalse(ModelHook.available());
        var modelPlugin = MockBukkit.createMockPlugin("ModelEngine");
        assertTrue(ModelHook.available());
        org.bukkit.Bukkit.getPluginManager().disablePlugin(modelPlugin);
        assertFalse(ModelHook.available());
        org.bukkit.Bukkit.getPluginManager().enablePlugin(modelPlugin);
        assertTrue(ModelHook.available());
        PetVisual hook = provider.hook();
        var calls = new ArrayList<Entity>();
        BiConsumer<Player, Entity> consumer = (actor, target) -> { assertSame(player, actor); calls.add(target); };
        invoke(hook, "registerInteractions", plugin, consumer);
        org.bukkit.Bukkit.getPluginManager().callEvent((Event) provider.api("interaction", player, body.getUniqueId(), "INTERACT", EquipmentSlot.HAND));
        org.bukkit.Bukkit.getPluginManager().callEvent((Event) provider.api("interaction", player, body.getUniqueId(), "INTERACT", EquipmentSlot.OFF_HAND));
        assertEquals(List.of(body), calls);
        hook.close();
    }

    @Test void hookWarnsOnceForMissingMotionAndCanStillRenderAndReleaseTheModel() throws Exception {
        provider.api("blueprint", "beagle", new String[]{"paw"});
        var records = new ArrayList<LogRecord>();
        PetVisual hook = hookWithLogs(records);
        var type = type();
        hook.apply(body, type);
        Entity other = body.getWorld().spawn(body.getLocation(), org.bukkit.entity.Wolf.class);
        hook.apply(other, type);
        assertTrue(hook.attached(body));
        assertTrue(hook.attached(other));
        assertEquals(1, records.stream().filter(record -> record.getMessage().contains("no idle or walk clip")).count());
        assertTrue(hook.play(body, type, "paw"));
        hook.close();
        assertNull(owner());
        assertNull(provider.api("getModeledEntity", other.getUniqueId()));
        hook.apply(body, type);
        assertEquals(2, records.stream().filter(record -> record.getMessage().contains("no idle or walk clip")).count());
        hook.close();
    }

    @Test void hookPreservesCleanupFailureEvidenceAndCanRecoverAfterProviderRepair() throws Exception {
        var records = new ArrayList<LogRecord>();
        PetVisual hook = hookWithLogs(records);
        var type = type();
        provider.flag("failScale", true);
        provider.flag("failRemoval", true);
        hook.apply(body, type);
        assertFalse(hook.attached(body));
        assertTrue(body.isValid());
        var failure = records.stream().filter(record -> record.getMessage().contains("using its vanilla body")).findFirst().orElseThrow();
        assertTrue(failure.getThrown().getSuppressed().length > 0, "failure must retain unsuccessful cleanup evidence");
        provider.flag("failScale", false);
        provider.flag("failRemoval", false);
        assertFalse(hook.playClip(body, type, "custom", 2));
        assertFalse(hook.startBelly(body, type, 2_000));
        assertFalse(hook.play(body, type, "paw"));
        hook.update(body, type, PetAnimation.SIT);
        assertFalse(hook.attached(body), "failed type remains in its retry cooldown");
        hook.close();
        hook.apply(body, type);
        assertTrue(hook.attached(body));
        hook.close();
        assertNull(owner());
    }

    @Test void hookFallsBackSafelyForEachFailedAnimationAndHeadOperation() throws Exception {
        var type = type();
        for (String operation : List.of("update", "training", "custom", "belly", "movement", "cancel", "head", "release")) {
            var records = new ArrayList<LogRecord>();
            PetVisual hook = hookWithLogs(records);
            hook.apply(body, type);
            if (operation.equals("movement")) assertTrue(hook.playClip(body, type, "custom", 2));
            if (operation.equals("cancel")) assertTrue(hook.play(body, type, "paw"));
            if (operation.equals("release")) hook.holdHeadLook(body, 30, 45, 5);
            String flag = switch (operation) {
                case "movement" -> "failPlaying";
                case "cancel" -> "failStop";
                case "head" -> "failHead";
                case "release" -> "failLock";
                default -> "failPlay";
            };
            provider.flag(flag, true);
            try {
                switch (operation) {
                    case "update" -> hook.update(body, type, PetAnimation.SIT);
                    case "training" -> hook.trainingAttention(body, type, true);
                    case "custom" -> assertFalse(hook.playClip(body, type, "custom", 2));
                    case "belly" -> assertFalse(hook.startBelly(body, type, 2_000));
                    case "movement" -> assertFalse(hook.holdsMovement(body));
                    case "cancel" -> hook.cancelAction(body);
                    case "head" -> hook.holdHeadLook(body, 30, 45, 5);
                    case "release" -> hook.releaseHeadLook(body);
                    default -> throw new AssertionError(operation);
                }
                assertFalse(hook.attached(body), operation);
                assertNull(owner(), operation);
                assertTrue(body.isValid(), operation + " must retain the vanilla pet");
                assertEquals(1, records.stream().filter(record -> record.getMessage().contains("using its vanilla body")).count(), operation);
            } finally {
                provider.flag(flag, false);
                hook.close();
            }
        }
    }

    @Test void clipLookupFailureDoesNotDiscardAnAttachedRenderer() throws Exception {
        PetVisual hook = provider.hook();
        var type = type();
        hook.apply(body, type);
        Object renderer = model();
        provider.flag("failBlueprint", true);
        assertFalse(hook.hasClip(body, type, "custom"));
        assertTrue(hook.attached(body));
        assertSame(renderer, model());
        provider.flag("failBlueprint", false);
        assertTrue(hook.hasClip(body, type, "custom"));
        hook.close();
    }

    @Test void tailProviderFailuresWarnOnceWithoutRemovingThePetModel() throws Exception {
        var type = type();
        for (String phase : List.of("install", "frame")) {
            var records = new ArrayList<LogRecord>();
            PetVisual hook = hookWithLogs(records);
            hook.apply(body, type);
            hook.animateTails();
            if (phase.equals("frame")) hook.wagTail(body, 4);
            String flag = phase.equals("frame") ? "failTailAdvance" : "failTail";
            provider.flag(flag, true);
            try {
                if (phase.equals("frame")) {
                    hook.animateTails();
                    hook.animateTails();
                } else {
                    hook.wagTail(body, 4);
                    hook.wagTail(body, 0);
                }
                assertTrue(hook.attached(body));
                assertTrue(body.isValid());
                assertEquals(1, records.stream().filter(record -> record.getMessage().contains("tail gesture unavailable")).count(), phase);
            } finally {
                provider.flag(flag, false);
                hook.wagTail(body, 0);
                hook.close();
            }
            assertNull(owner());
        }
    }

    @Test void rendererRemovalFailureIsReportedAndCanBeRetriedWithoutDeletingTheBody() throws Exception {
        var records = new ArrayList<LogRecord>();
        PetVisual hook = hookWithLogs(records);
        hook.apply(body, type());
        provider.flag("failRemoval", true);
        hook.remove(body);
        assertFalse(hook.attached(body));
        assertTrue(body.isValid());
        assertEquals(1, records.stream().filter(record -> record.getMessage().contains("Could not detach pet model")).count());
        provider.flag("failRemoval", false);
        hook.remove(body);
        assertNull(owner());
        assertNull(savedModel());
        hook.close();
    }

    @Test void unmodeledAndAbsentSessionsLeaveNativeBodiesUntouched() throws Exception {
        PetVisual hook = provider.hook();
        var yaml = new YamlConfiguration();
        yaml.loadFromString("pets: {wolf: {entity: WOLF, egg: WOLF_SPAWN_EGG}}");
        var vanilla = CompanionConfig.load(plugin, yaml).type("wolf");
        hook.apply(null, vanilla);
        hook.apply(body, null);
        hook.apply(body, vanilla);
        hook.update(body, vanilla, PetAnimation.IDLE);
        hook.trainingAttention(body, vanilla, false);
        hook.holdHeadLook(body, 0, 0, 0);
        hook.releaseHeadLook(body);
        hook.wagTail(body, 2);
        hook.cancelAction(body);
        assertFalse(hook.holdsMovement(body));
        assertFalse(hook.startBelly(body, vanilla, 1_000));
        assertFalse(hook.belly(body));
        assertFalse(hook.rubBelly(body));
        assertFalse(hook.play(body, vanilla, "paw"));
        assertFalse(hook.playClip(body, vanilla, "custom", 1));
        assertFalse(hook.attached(body));
        assertTrue(created().isEmpty());
        assertTrue(body.isValid());
        hook.retain(Set.of(body.getUniqueId()));
        hook.remove(body);
        hook.close();
    }

    private static final class Provider extends URLClassLoader {
        private final Set<String> unavailable;
        Provider(Set<String> unavailable) throws Exception { this(unavailable, null); }
        Provider(Set<String> unavailable, Path variant) throws Exception {
            super(variant == null ? new URL[]{classes.toUri().toURL()}
                    : new URL[]{variant.toUri().toURL(), classes.toUri().toURL()}, ModelEngineBridge.class.getClassLoader());
            this.unavailable = unavailable;
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (unavailable.contains(name)) throw new ClassNotFoundException(name);
                Class<?> type = findLoadedClass(name);
                String prefix = "net.tfminecraft.companionpets.integration.";
                boolean production = name.startsWith(prefix + "ModelEngineBridge") || name.startsWith(prefix + "ModelHook") || name.startsWith(prefix + "TailWag");
                if (type == null && production) {
                    try (var stream = ModelEngineBridge.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (stream == null) throw new ClassNotFoundException(name);
                        byte[] bytes = stream.readAllBytes();
                        type = defineClass(name, bytes, 0, bytes.length, ModelEngineBridge.class.getProtectionDomain());
                    } catch (IOException ex) { throw new ClassNotFoundException(name, ex); }
                } else if (type == null && name.startsWith(API)) type = findClass(name);
                if (type == null) return super.loadClass(name, resolve);
                if (resolve) resolveClass(type);
                return type;
            }
        }
        Object bridge() throws Exception {
            var constructor = loadClass(ModelEngineBridge.class.getName()).getDeclaredConstructor();
            constructor.setAccessible(true);
            try { return constructor.newInstance(); }
            catch (InvocationTargetException ex) { throw (Exception) ex.getCause(); }
        }
        PetVisual hook() throws Exception {
            var logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
            return hook(logger);
        }
        PetVisual hook(Logger logger) throws Exception {
            return (PetVisual) loadClass(ModelHook.class.getName()).getConstructor(Logger.class).newInstance(logger);
        }
        Object api(String name, Object... args) throws Exception { return invoke(loadClass(API + "ModelEngineAPI"), name, args); }
        Object value(String name) throws Exception { return loadClass(API + "ModelEngineAPI").getField(name).get(null); }
        void flag(String name, boolean value) throws Exception { loadClass(API + "ModelEngineAPI").getField(name).set(null, value); }
    }
}
