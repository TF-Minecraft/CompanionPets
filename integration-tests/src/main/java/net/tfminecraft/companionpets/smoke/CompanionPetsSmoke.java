package net.tfminecraft.companionpets.smoke;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Snowball;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import net.tfminecraft.companionpets.PetsPlugin;
import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.integration.ModelHook;
import net.tfminecraft.companionpets.item.ToyItems;
import net.tfminecraft.companionpets.listen.PetListener;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.play.FetchJob;
import net.tfminecraft.companionpets.runtime.*;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.staff.StaffCommands;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.visual.*;
import org.bukkit.persistence.PersistentDataType;

/** Uses its own store and namespace. It never edits the live CompanionPets records. */
public final class CompanionPetsSmoke extends JavaPlugin {
    private final Set<Entity> entities = new LinkedHashSet<>();
    private int checks;
    private PetVisual visual;
    private PetStore store;
    private final Set<UUID> trackedPets = new HashSet<>();
    private Set<String> checkedTypes = Set.of();
    private boolean checksComplete;
    private static final class TeleportProbe implements org.bukkit.event.Listener {
        private final NamespacedKey key;
        private int blocked;
        TeleportProbe(NamespacedKey key) { this.key = key; }
        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onTeleport(org.bukkit.event.entity.EntityTeleportEvent event) {
            if (event.getEntity().getPersistentDataContainer().has(key, PersistentDataType.STRING) && event.isCancelled()) blocked++;
        }
    }
    private final Set<org.bukkit.Chunk> forcedChunks = new HashSet<>();
    private record Held(Mob body, Location origin, int ticks, String description) { }
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
    @Override public void onEnable() { Bukkit.getScheduler().runTaskLater(this, this::run, 240); }

    private void run() {
        boolean deferred = false;
        try {
            var plugin = (PetsPlugin) Bukkit.getPluginManager().getPlugin("CompanionPets");
            var config = CompanionConfig.load(plugin);
            var world = Bukkit.getWorlds().getFirst();
            Location at = world.getSpawnLocation().add(8, 1, 8);
            at.getChunk().load();
            visual = ModelHook.available() ? new ModelHook(getLogger()) : new IdleVisual();
            store = new PetStore(this); check(store.load() && store.beginSession(), "Isolated persistence available");
            var key = new NamespacedKey(this, "pet");
            var runtime = new PetRuntime(this, config, store, new Sessions(), new Bodies(this, key, visual), visual, key, new NamespacedKey(this, "toy"));
            check(Bukkit.getPluginCommand("companionpets") != null
                    && Bukkit.getPluginCommand("companionpets").getPlugin().getName().equals("CompanionPets"), "canonical command belongs to CompanionPets");
            check(Bukkit.getPluginCommand("petcompanions") == null, "removed root is not registered");
            check(Bukkit.getPluginCommand("companionpets").getName().equals("companionpets"), "no namespace needed for canonical root");
            var actions = new PetActions(runtime);
            var staff = new StaffCommands(runtime, actions);
            var listener = new PetListener(runtime, actions);
            Bukkit.getPluginManager().registerEvents(listener, this);
            var teleportProbe = new TeleportProbe(key); Bukkit.getPluginManager().registerEvents(teleportProbe, this);
            var tests = new net.tfminecraft.companionpets.staff.TestCommands(runtime, actions, staff);
            UUID owner = UUID.randomUUID();
            var held = new ArrayList<Held>();
            for (var type : config.types().values()) {
                var oldIds = new HashSet<UUID>(); store.all().forEach(p -> oldIds.add(p.id()));
                try {
                    staff.execute(Bukkit.getConsoleSender(), "create", owner.toString(), "type=" + type.id(), "name=Replacement", "tricks=follow", "hunger=61", "energy=83", "bond=66", "sex=female");
                } finally {
                    store.all().stream().filter(p -> !oldIds.contains(p.id())).forEach(p -> trackedPets.add(p.id()));
                }
                var created = store.all().stream().filter(p -> !oldIds.contains(p.id())).toList();
                check(created.size() == 1, type.id() + " create adds exactly one pet");
                var replacement = created.getFirst();
                check(replacement.ownerId().equals(owner) && replacement.typeId().equals(type.id()) && replacement.stored(), type.id() + " manual replacement ownership and shelter");
                check(replacement.need(Need.HUNGER) == 61 && replacement.need(Need.ENERGY) == 83 && replacement.bond() == 66, type.id() + " replacement stats");
                check(replacement.progress(Trick.FOLLOW) == 100 && replacement.trickFor("follow") == Trick.FOLLOW, type.id() + " replacement learning");
                check(store.remove(replacement.id()) && store.save(), type.id() + " isolated replacement cleanup");
                var pet = new Pet(UUID.randomUUID(), owner, type.id(), "Smoke " + type.id(), PetSex.FEMALE);
                net.tfminecraft.companionpets.training.DefaultTricks.apply(config, pet);
                check(pet.progress(Trick.FOLLOW) == 100, type.id() + " Follow learned by default");
                check(pet.trickFor("follow") == Trick.FOLLOW, type.id() + " Follow has visible command word");
                pet.need(Need.HUNGER, 55); pet.progress(Trick.SPEAK, 73); pet.bindWord("here", Trick.SPEAK);
                var body = runtime.bodies().spawn(pet, type, at, null);
                if (body != null) entities.add(body);
                check(body instanceof Mob, type.id() + " body spawn"); runtime.remember(pet, body);
                trackedPets.add(pet.id()); store.add(pet);
                check(pet.id().equals(runtime.bodies().readId(body)), type.id() + " tagged identity");
                check(((Mob) body).isAware(), type.id() + " ordinary pet has native AI");
                check(type.matchesEgg(type.eggIcon()), type.id() + " egg identity");
                var orderOwner = ownerFixture(owner, at);
                for (Trick posture : List.of(Trick.SIT, Trick.STAY, Trick.LAY)) {
                    if (!type.allowsTrick(posture)) continue;
                    String word = posture.name().toLowerCase(Locale.ROOT); pet.bindWord(word, posture); pet.progress(posture, 100);
                    seedMovement((Mob) body);
                    actions.onChat(orderOwner, pet.name() + " " + word);
                    check(pet.order().name().equals(posture.name()) && !((Mob) body).isAware(), type.id() + " named " + word + " holds native AI");
                    checkStopped((Mob) body, type.id() + " " + word);
                    if (posture == Trick.STAY && body instanceof org.bukkit.entity.Tameable tameable) {
                        var far = at.clone().add(40, 0, 0); far.setY(world.getHighestBlockYAt(far) + 1);
                        if (!far.getChunk().isForceLoaded()) { far.getChunk().setForceLoaded(true); forcedChunks.add(far.getChunk()); }
                        var farOwner = world.spawn(far, org.bukkit.entity.ArmorStand.class, stand -> {
                            stand.setVisible(false); stand.setMarker(true); stand.setGravity(false); stand.setInvulnerable(true); stand.setPersistent(false);
                        }); entities.add(farOwner);
                        var nativeBody = handle((Mob) body);
                        var nativeOwner = farOwner.getClass().getMethod("getHandle").invoke(farOwner);
                        tameable.setTamed(true);
                        nativeBody.getClass().getMethod("setOwner", Class.forName("net.minecraft.world.entity.LivingEntity")).invoke(nativeBody, nativeOwner);
                        check(nativeBody.getClass().getMethod("getOwner").invoke(nativeBody) == nativeOwner, type.id() + " native far-away owner resolves");
                        var origin = body.getLocation(); int blocked = teleportProbe.blocked;
                        for (int attempts = 0; attempts < 20; attempts++) nativeBody.getClass().getMethod("tryToTeleportToOwner").invoke(nativeBody);
                        check(teleportProbe.blocked > blocked, type.id() + " Stay cancels actual native teleport attempts");
                        check(body.getLocation().distanceSquared(origin) < 0.0001 && !((org.bukkit.entity.Sittable) body).isSitting(), type.id() + " Stay remains standing at its original position");
                    }
                    new PetTicker(runtime, actions).run();
                    check(pet.order().name().equals(posture.name()) && !((Mob) body).isAware(), type.id() + " posture persists at full energy");
                    if (type.allowsTrick(Trick.COME)) {
                        pet.bindWord("come", Trick.COME); pet.progress(Trick.COME, 100);
                        actions.onChat(orderOwner, pet.name() + " come");
                        check(pet.order() == PetOrder.FOLLOW && pet.activity() == Activity.ATTENDING && ((Mob) body).isAware(), type.id() + " Come gets up for a temporary trip");
                        new PetTicker(runtime, actions).run();
                        check(pet.order().name().equals(posture.name()) && !((Mob) body).isAware(), type.id() + " Come restores posture if owner disconnects");
                        checkStopped((Mob) body, type.id() + " interrupted Come " + word);
                    }
                    actions.onChat(orderOwner, "follow " + pet.name());
                    check(pet.order() == PetOrder.FOLLOW && ((Mob) body).isAware(), type.id() + " default Follow resumes posture");
                }
                check(!type.allowsTrick(Trick.SPIN), type.id() + " spin is removed");
                var menus = new net.tfminecraft.companionpets.gui.PetMenus(runtime);
                var profile = Bukkit.createInventory(null, 45); menus.fillInformation(profile, pet);
                check(profile.getItem(40).getType() == org.bukkit.Material.BOOK && profile.getItem(36).getType() == org.bukkit.Material.ITEM_FRAME, type.id() + " centered tricks and left corner Back");
                var trickMenu = Bukkit.createInventory(null, 27); menus.fillLearnedInformation(trickMenu, pet, 0);
                check(trickMenu.getItem(18).getType() == org.bukkit.Material.ITEM_FRAME && trickMenu.getItem(26).getType() == org.bukkit.Material.ARROW, type.id() + " opposite corner navigation");
                if (type.appearance().modeled()) {
                    check(visual.attached(body), type.id() + " model attached");
                    var clips = visual.clips(type);
                    check(!clips.isEmpty(), type.id() + " registered animations");
                    for (var animation : type.appearance().availableClips(clips).keySet()) {
                        check(visual.playClip(body, type, type.appearance().availableClips(clips).get(animation).name(), 2), type.id() + " clip " + animation);
                        visual.cancelAction(body);
                    }
                    for (var trick : config.tricks()) {
                        var custom = config.customTrick(trick);
                        if (custom != null && type.allowsTrick(trick) && !custom.animation().isBlank() && clips.contains(custom.animation())) {
                            check(visual.playClip(body, type, custom.animation(), custom.duration()), type.id() + " custom " + trick);
                            visual.cancelAction(body);
                        }
                    }
                    var shake = type.appearance().availableClips(clips).get(PetAnimation.SHAKE);
                    if (body instanceof org.bukkit.entity.Wolf wolf && shake != null) {
                        var handle = wolf.getClass().getMethod("getHandle").invoke(wolf);
                        var clock = handle.getClass().getDeclaredField("shakeAnim"); clock.setAccessible(true); clock.setFloat(handle, 0);
                        visual.cancelAction(body); var visualTick = new PetVisualTicker(runtime); visualTick.run();
                        check(!net.tfminecraft.companionpets.integration.WolfShake.shaking(wolf), type.id() + " no shake before native start");
                        clock.setFloat(handle, 0.05F); visualTick.run();
                        check(net.tfminecraft.companionpets.integration.WolfShake.shaking(wolf), type.id() + " native shake clock supported");
                        var active = Class.forName("com.ticxo.modelengine.api.model.ModeledEntity").getMethod("getModel", String.class).invoke(modeledOwner(body), type.appearance().model());
                        var model = ((Optional<?>) active).orElseThrow();
                        var handler = Class.forName("com.ticxo.modelengine.api.model.ActiveModel").getMethod("getAnimationHandler").invoke(model);
                        check(Boolean.TRUE.equals(Class.forName("com.ticxo.modelengine.api.animation.handler.AnimationHandler").getMethod("isPlayingAnimation", String.class).invoke(handler, shake.name())), type.id() + " shake model starts with native clock");
                        clock.setFloat(handle, 0); visual.cancelAction(body);
                    }
                }
                check(actions.restoreBody(pet) == body, type.id() + " recovery adopts live body");
                body.remove(); visual.remove(body);
                var restored = actions.restoreBody(pet);
                check(restored != null && restored != body, type.id() + " missing body restored"); entities.add(restored);
                check(pet.need(Need.HUNGER) == 55 && pet.progress(Trick.SPEAK) == 73 && pet.trickFor("here") == Trick.SPEAK, type.id() + " recovery preserves state");
                var duplicate = runtime.bodies().spawn(pet, type, at, null); entities.add(duplicate);
                actions.reattach(duplicate); check(!duplicate.isValid(), type.id() + " duplicate removed");
                Object modeledOwner = modeledOwner(restored);
                var ownerFixture = ownerFixture(owner, at);
                var care = new net.tfminecraft.companionpets.gui.MenuHolder(net.tfminecraft.companionpets.gui.MenuHolder.Kind.CARE, pet.id(), null);
                actions.clickMenu(ownerFixture, care, net.tfminecraft.companionpets.gui.PetMenus.STORE_SLOT, null, false, false, false);
                check(pet.stored() && pet.entityId() == null && !restored.isValid(), type.id() + " owner's shelter removes body");
                checkRemovedVisual(restored, modeledOwner, type.id() + " owner's shelter");
                pet.stored(false);
                restored = actions.restoreBody(pet); check(restored != null, type.id() + " shelter pet can be restored"); entities.add(restored);
                modeledOwner = modeledOwner(restored);
                actions.clickMenu(ownerFixture, care, net.tfminecraft.companionpets.gui.PetMenus.STORE_SLOT, null, false, false, false);
                check(pet.stored() && runtime.entity(pet) == null && !restored.isValid(), type.id() + " second shelter entry removes body");
                checkRemovedVisual(restored, modeledOwner, type.id() + " second shelter entry");

                check(pet.need(Need.HUNGER) == 55 && pet.progress(Trick.SPEAK) == 73, type.id() + " shelter preserves learning and needs");
                check(store.remove(pet.id()), type.id() + " cleanup journal");
                var released = new Pet(UUID.randomUUID(), owner, type.id(), "Release smoke", PetSex.MALE);
                trackedPets.add(released.id()); store.add(released);
                var releaseBody = runtime.bodies().spawn(released, type, at, null); entities.add(releaseBody); runtime.remember(released, releaseBody);
                modeledOwner = modeledOwner(releaseBody);
                runtime.sessions().release(owner, new net.tfminecraft.companionpets.session.ReleasePrompt(released.id(), System.currentTimeMillis() + 30_000));
                actions.onChat(ownerFixture, "yes");
                check(!releaseBody.isValid() && store.get(released.id()) == null && store.isDeleted(released.id()), type.id() + " release deletes body and record");
                checkRemovedVisual(releaseBody, modeledOwner, type.id() + " release");
                for (Trick posture : List.of(Trick.SIT, Trick.STAY, Trick.LAY)) {
                    if (!type.allowsTrick(posture)) continue;
                    var resting = new Pet(UUID.randomUUID(), owner, type.id(), "Hold " + type.id() + " " + posture.name(), PetSex.MALE);
                    net.tfminecraft.companionpets.training.DefaultTricks.apply(config, resting);
                    String word = posture.name().toLowerCase(Locale.ROOT); resting.bindWord(word, posture); resting.progress(posture, 100);
                    trackedPets.add(resting.id());
                    store.add(resting);
                    var mob = (Mob) runtime.bodies().spawn(resting, type, at, null); entities.add(mob); runtime.remember(resting, mob);
                    mob.setCollidable(false);
                    if (!mob.getChunk().isForceLoaded()) { mob.getChunk().setForceLoaded(true); forcedChunks.add(mob.getChunk()); }
                    seedMovement(mob);
                    actions.onChat(orderOwner, resting.name() + " " + word);
                    held.add(new Held(mob, mob.getLocation(), nativeTicks(mob), type.id() + " " + word));
                }
            }
            var toyType = config.type("wolf");
            check(toyType != null && !toyType.toys().isEmpty(), "Wolf test toy configured");
            var toy = toyType.toys().getFirst().create(); check(toy != null, "Provider creates configured toy");
            var meta = toy.getItemMeta(); meta.getPersistentDataContainer().set(new NamespacedKey(this, "custom-data"), PersistentDataType.STRING, "keep"); toy.setItemMeta(meta);
            var pet = new Pet(UUID.randomUUID(), owner, "wolf", "Toy smoke", PetSex.MALE);
            trackedPets.add(pet.id()); store.add(pet);
            String saved = ToyItems.encode(toy);
            Snowball ball = world.spawn(at, Snowball.class); entities.add(ball);
            ball.getPersistentDataContainer().set(runtime.toyKey(), PersistentDataType.STRING, pet.id() + "|" + saved);
            var job = new FetchJob(saved, false); job.projectileId(ball.getUniqueId()); pet.fetch(job);
            listener.onToyHit(new ProjectileHitEvent(ball));
            var item = (Item) Bukkit.getEntity(pet.fetch().itemId()); check(item != null, "Toy landed"); entities.add(item);
            check(toy.isSimilar(item.getItemStack()), "Toy preserves data after landing");
            actions.releaseFetch(pet, null, false); check(!item.isValid() && pet.fetch() == null, "Offline return removes ground toy");
            check(toy.isSimilar(ToyItems.decode(pet.carriedToy())), "Offline return preserves data");
            var stale = world.spawn(at, Snowball.class); entities.add(stale);
            stale.getPersistentDataContainer().set(runtime.toyKey(), PersistentDataType.STRING, pet.id() + "|" + saved);
            int before = world.getEntitiesByClass(Item.class).size(); listener.onToyHit(new ProjectileHitEvent(stale));
            check(world.getEntitiesByClass(Item.class).size() == before, "Stale projectile cannot duplicate returned toy");
            store.remove(pet.id());
            check(staff.diagnostics().validate().isEmpty(), "Configured provider IDs, models and clips validate");
            Bukkit.getScheduler().runTaskLater(this, () -> {
                try {
                    for (Held sample : held) {
                        check(nativeTicks(sample.body()) > sample.ticks(), sample.description() + " actually receives native ticks");
                        var after = sample.body().getLocation();
                        double dx = after.getX() - sample.origin().getX(), dz = after.getZ() - sample.origin().getZ();
                        check(dx * dx + dz * dz < 0.0025, sample.description() + " remains in place after 20 native ticks");
                        checkStopped(sample.body(), sample.description() + " after native ticks");
                    }
                    checkedTypes = new LinkedHashSet<>(config.types().keySet());
                    checksComplete = true;
                } catch (Throwable ex) { getLogger().log(java.util.logging.Level.SEVERE, "COMPANIONPETS_INTEGRATION FAIL", ex); }
                finally { Bukkit.getPluginManager().disablePlugin(this); }
            }, 20);
            deferred = true;
        } catch (Throwable ex) { getLogger().log(java.util.logging.Level.SEVERE, "COMPANIONPETS_INTEGRATION FAIL", ex); }
        finally { if (!deferred) Bukkit.getPluginManager().disablePlugin(this); }
    }

    private static Object handle(Mob body) throws Exception { return body.getClass().getMethod("getHandle").invoke(body); }
    private static int nativeTicks(Mob body) throws Exception { var nativeBody = handle(body); return nativeBody.getClass().getField("tickCount").getInt(nativeBody); }
    private static void seedMovement(Mob body) throws Exception {
        var nativeBody = handle(body); var type = nativeBody.getClass();
        var control = type.getMethod("getMoveControl").invoke(nativeBody);
        var at = body.getLocation();
        control.getClass().getMethod("setWantedPosition", double.class, double.class, double.class, double.class).invoke(control, at.getX() + 30, at.getY(), at.getZ() + 30, 1D);
        type.getMethod("setSpeed", float.class).invoke(nativeBody, 0.3F);
        type.getMethod("setXxa", float.class).invoke(nativeBody, 0.4F);
        type.getMethod("setZza", float.class).invoke(nativeBody, 0.8F);
        body.setVelocity(new org.bukkit.util.Vector(0.4, 0, 0.3));
    }
    private void checkStopped(Mob body, String description) throws Exception {
        var nativeBody = handle(body);
        check(nativeBody.getClass().getField("xxa").getFloat(nativeBody) == 0 && nativeBody.getClass().getField("zza").getFloat(nativeBody) == 0, description + " clears native travel inputs");
        check(body.getVelocity().getX() == 0 && body.getVelocity().getZ() == 0, description + " clears horizontal velocity");
        var control = nativeBody.getClass().getMethod("getMoveControl").invoke(nativeBody);
        check(Math.abs(((Number) control.getClass().getMethod("getWantedX").invoke(control)).doubleValue() - body.getX()) < 0.001
                && Math.abs(((Number) control.getClass().getMethod("getWantedZ").invoke(control)).doubleValue() - body.getZ()) < 0.001, description + " clears the previous controller destination");
    }

    private Object modeledOwner(Entity entity) throws Exception {
        if (!ModelHook.available()) return null;
        return Class.forName("com.ticxo.modelengine.api.ModelEngineAPI").getMethod("getModeledEntity", UUID.class).invoke(null, entity.getUniqueId());
    }

    private void checkRemovedVisual(Entity entity, Object previousOwner, String action) throws Exception {
        if (previousOwner == null) return;
        var modeled = Class.forName("com.ticxo.modelengine.api.model.ModeledEntity");
        check(Boolean.FALSE.equals(modeled.getMethod("isBaseEntityVisible").invoke(previousOwner)), action + " never force-spawns vanilla body");
        check(modeledOwner(entity) == null, action + " clears ModelEngine registry");
        check(Boolean.TRUE.equals(modeled.getMethod("isDestroyed").invoke(previousOwner)), action + " destroys modeled owner");
    }

    /** Only identity, position and chat feedback are needed by the confirmed release/menu routes. */
    private static org.bukkit.entity.Player ownerFixture(UUID owner, Location at) {
        return (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(org.bukkit.entity.Player.class.getClassLoader(),
                new Class<?>[]{org.bukkit.entity.Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> owner;
                    case "getLocation" -> at.clone();
                    case "getEyeLocation" -> at.clone().add(0, 1.62, 0);
                    case "getWorld" -> at.getWorld();
                    case "getName", "toString" -> "Isolated smoke owner";
                    case "sendMessage", "sendActionBar", "closeInventory" -> null;
                    case "isOnline" -> false;
                    case "hashCode" -> owner.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError("Unexpected owner fixture call: " + method.getName());
                });
    }

    @Override public void onDisable() {
        boolean cleanupSucceeded = true;
        for (Entity entity : entities) {
            try {
                if (visual != null) visual.remove(entity);
            } catch (Throwable ex) {
                cleanupSucceeded = false;
                getLogger().log(java.util.logging.Level.SEVERE, "COMPANIONPETS_INTEGRATION FAIL: visual cleanup", ex);
            } finally {
                try { entity.remove(); }
                catch (Throwable ex) {
                    cleanupSucceeded = false;
                    getLogger().log(java.util.logging.Level.SEVERE, "COMPANIONPETS_INTEGRATION FAIL: entity cleanup", ex);
                }
            }
        }
        try { if (visual != null) visual.close(); }
        catch (Throwable ex) {
            cleanupSucceeded = false;
            getLogger().log(java.util.logging.Level.SEVERE, "COMPANIONPETS_INTEGRATION FAIL: visual shutdown", ex);
        }
        if (store != null) {
            try {
                for (UUID id : trackedPets) {
                    if (store.get(id) != null && !store.remove(id)) {
                        cleanupSucceeded = false;
                        getLogger().severe("COMPANIONPETS_INTEGRATION FAIL: cannot remove temporary record " + id);
                    }
                }
                if (!store.close()) {
                    cleanupSucceeded = false;
                    getLogger().severe("COMPANIONPETS_INTEGRATION FAIL: isolated store did not close");
                }
            } catch (Throwable ex) {
                cleanupSucceeded = false;
                getLogger().log(java.util.logging.Level.SEVERE, "COMPANIONPETS_INTEGRATION FAIL: store shutdown", ex);
            }
        }
        for (var chunk : forcedChunks) {
            try { chunk.setForceLoaded(false); }
            catch (Throwable ex) {
                cleanupSucceeded = false;
                getLogger().log(java.util.logging.Level.SEVERE, "COMPANIONPETS_INTEGRATION FAIL: chunk cleanup", ex);
            }
        }
        forcedChunks.clear();
        if (checksComplete && cleanupSucceeded)
            getLogger().info("COMPANIONPETS_INTEGRATION PASS checks=" + checks + " types=" + checkedTypes);
    }
}
