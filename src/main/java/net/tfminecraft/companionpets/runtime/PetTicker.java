package net.tfminecraft.companionpets.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import net.tfminecraft.companionpets.item.ItemRef;
import net.tfminecraft.companionpets.item.HeldItem;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;


import net.tfminecraft.companionpets.behavior.Locomotion;
import net.tfminecraft.companionpets.behavior.Rest;
import net.tfminecraft.companionpets.behavior.WaterEscape;
import net.tfminecraft.companionpets.care.CareInput;
import net.tfminecraft.companionpets.care.CareNotice;
import net.tfminecraft.companionpets.care.DominantNeed;
import net.tfminecraft.companionpets.care.NeedClock;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.management.PresenceRules;
import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.Presence;
import net.tfminecraft.companionpets.play.FavoriteToy;
import net.tfminecraft.companionpets.session.TrainingSession;
import net.tfminecraft.companionpets.text.PetTexts;

public final class PetTicker implements Runnable {
    private static final long MISSING_BODY_GRACE_MILLIS = 5_000L;
    private final PetRuntime runtime;
    private final PetActions actions;
    private final Map<UUID, Long> missingBodySince = new HashMap<>();
    private long lastCareAt;
    private final Map<UUID, Locomotion.Mode> previousModes = new HashMap<>();

    public PetTicker(PetRuntime runtime, PetActions actions) {
        this.runtime = runtime;
        this.actions = actions;
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        long elapsed = lastCareAt == 0L ? 0L : now - lastCareAt;
        lastCareAt = now;
        if (elapsed > 0L) {
            care(now, elapsed);
        }
        actions.roaming().tickOwners(now);
        actions.fetchActions().tick(now);
        actions.anticipation().tick(now);
        actions.greetings().tick(now);
        actions.social().tick(now);
        move(now);
        runtime.voice().tick(now);
        previousModes.keySet().removeIf(id -> runtime.store().get(id) == null || runtime.entity(runtime.store().get(id)) == null);
        watchTraining(now);
        runtime.forgetBodies();
    }

    private void care(long now, long elapsed) {
        for (Pet pet : runtime.store().all()) {
            if (pet.dead() || runtime.config().type(pet.typeId()) == null) {
                missingBodySince.remove(pet.id());
                continue;
            }
            Player owner = Bukkit.getPlayer(pet.ownerId());
            boolean online = owner != null && owner.isOnline();
            // Pet House pets with frozen care (owner offline, or no decay while stored) have nothing to update.
            if (pet.stored() && PresenceRules.resolve(true, online, 0, 0,
                    runtime.config().care().decayWhileStored()) == Presence.FROZEN) {
                missingBodySince.remove(pet.id());
                continue;
            }
            Entity body = runtime.entity(pet);
            if (body != null && !runtime.bodies().compatible(body, runtime.config().type(pet.typeId()))) continue;
            if (!pet.stored() && body == null && bodyChunkEntitiesLoaded(pet)) {
                long firstMissing = missingBodySince.computeIfAbsent(pet.id(), id -> now);
                if (now - firstMissing >= MISSING_BODY_GRACE_MILLIS) {
                    body = actions.restoreBody(pet);
                    if (body != null) {
                        missingBodySince.remove(pet.id());
                    } else {
                        missingBodySince.put(pet.id(), now);
                    }
                }
            } else {
                missingBodySince.remove(pet.id());
            }
            // An unloaded or missing body is not evidence of death. Keep its record
            // and freeze care until the saved chunk and body are available again.
            if (!pet.stored() && body == null) continue;
            if (body != null) {
                runtime.remember(pet, body);
            }
            double distance = runtime.distance(owner, pet);
            Presence presence = PresenceRules.resolve(
                    pet.stored(),
                    online,
                    distance,
                    runtime.config().ownerNearRadius(),
                    runtime.config().care().decayWhileStored());
            Locomotion.Mode mode = body == null
                    ? null
                    : Locomotion.choose(
                            pet.illness(),
                            pet.need(Need.HEALTH),
                            pet.need(Need.ENERGY),
                            pet.need(Need.HUNGER),
                            pet.activity(),
                            pet.fetch() != null,
                            now < pet.forcedSitUntilMillis(),
                            pet.order(),
                            pet.staying());
            boolean withOwner = !pet.stored() && online && distance <= runtime.config().ownerNearRadius();
            boolean swimming = body instanceof Mob mob && WaterEscape.needed(mob);
            if (!pet.stored() && !swimming && Rest.shouldLieDown(
                    pet.need(Need.ENERGY),
                    pet.activity(),
                    pet.fetch() != null,
                    now,
                    pet.refuseRestUntilMillis())) {
                pet.activity(Activity.SLEEPING);
                if (body != null) {
                    actions.markSleep(body, true);
                }
                if (online) {
                    PetFx.bar(owner, pet.name() + " lies down to rest");
                }
            }
            List<CareNotice> notices = NeedClock.advance(pet, new CareInput(
                    presence,
                    swimming || mode == Locomotion.Mode.FOLLOW,
                    pet.activity() == Activity.PLAYING,
                    !swimming && (pet.activity() == Activity.SLEEPING || mode == Locomotion.Mode.SLEEP),
                    !swimming && Rest.recoversEnergy(mode),
                    withOwner,
                    elapsed,
                    runtime.config().care(),
                    runtime.config().awayRate()));
            if (pet.dead()) {
                if (body != null && body.isSilent()) runtime.voice().play(body, net.tfminecraft.companionpets.config.PetSounds.Event.DEATH);
                if (!runtime.store().remove(pet.id())) continue;
                runtime.store().requestSave();
                actions.clearInteractions(pet);
                if (body instanceof org.bukkit.entity.LivingEntity living && runtime.visual().attached(body)) {
                    living.setHealth(0);
                } else if (body != null) {
                    runtime.visual().removeBody(body);
                }
                if (online) {
                    PetFx.tell(owner, pet.name() + " grew too weak without care and has passed away.");
                }
                continue;
            }
            if (online) {
                for (CareNotice notice : notices) {
                    if (notice.kind() == CareNotice.Kind.ENTERED_LOW && notice.need() != null) {
                        PetFx.bar(owner, PetTexts.lowNeed(pet.name(), pet.sex(), notice.need()));
                        if (body != null) {
                            runtime.voice().ambient(body);
                            PetTypeDef petType = runtime.config().type(pet.typeId());
                            PetFx.need(body, notice.need(), petType == null ? null : petType.foodIcon());
                        }
                    } else if (notice.kind() != CareNotice.Kind.ENTERED_LOW) {
                        PetFx.bar(owner, PetTexts.illness(pet.name(), pet.sex(), pet.illness()));
                    }
                }
            }
            if (online) {
                actions.menus().refreshCare(owner, pet);
            }
            PetTypeDef type = runtime.config().type(pet.typeId());
            String before = pet.favoriteToy();
            FavoriteToy.Result favorite = FavoriteToy.reconcile(before, PetActions.toyNames(type), runtime.random());
            if ((before == null && favorite.toy() != null) || (before != null && !before.equals(favorite.toy()))) {
                pet.favoriteToy(favorite.toy());
                if (favorite.notifyLost() && online) {
                    PetFx.tell(owner, pet.name() + " has a new favorite toy: the " + PetTexts.itemName(favorite.toy()) + ".");
                }
            }
            if (pet.carriedToy() != null && withOwner && body != null && owner != null) {
                actions.dropPlain(PetRuntime.inFront(owner), pet.carriedToy());
                pet.carriedToy(null);
            }
            if (pet.activity() == Activity.PLAYING && pet.fetch() == null && now >= pet.playUntilMillis()) {
                pet.need(Need.MOOD, pet.need(Need.MOOD) + runtime.config().care().playMoodGain());
                pet.need(Need.ENERGY, pet.need(Need.ENERGY) - runtime.config().care().playEnergyCost());
                pet.activity(Activity.NONE);
                if (body != null) {
                    PetFx.hearts(body, 3);
                    body.getWorld().playSound(body.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.2f);
                }
            }
            if (pet.activity() == Activity.SLEEPING && pet.order() != net.tfminecraft.companionpets.pet.PetOrder.LAY && pet.need(Need.ENERGY) >= 100.0) {
                pet.activity(Activity.NONE);
                if (online) {
                    PetFx.bar(owner, pet.name() + " wakes up, fully rested");
                }
            }
        }
        missingBodySince.keySet().removeIf(id -> runtime.store().get(id) == null);
    }

    private static boolean bodyChunkEntitiesLoaded(Pet pet) {
        World world = Bukkit.getWorld(pet.worldName());
        if (world == null) {
            return false;
        }
        int chunkX = ((int) Math.floor(pet.x())) >> 4;
        int chunkZ = ((int) Math.floor(pet.z())) >> 4;
        return world.isChunkLoaded(chunkX, chunkZ) && world.getChunkAt(chunkX, chunkZ).isEntitiesLoaded();
    }

    private void move(long now) {
        runtime.forgetMissingGround();
        for (Pet pet : runtime.store().active()) {
            Entity body = runtime.entity(pet);
            if (!(body instanceof Mob mob)) {
                continue;
            }
            if (!runtime.bodies().compatible(mob, runtime.config().type(pet.typeId()))) {
                net.tfminecraft.companionpets.integration.PetMotion.hold(mob);
                continue;
            }
            Player owner = Bukkit.getPlayer(pet.ownerId());
            if (WaterEscape.needed(mob)) {
                if (pet.fetch() == null && pet.activity() != Activity.ATTENDING) {
                    actions.roaming().cancelWithPosture(pet);
                    actions.clearInteractions(pet);
                }
                actions.markSleep(mob, false);
                runtime.visual().cancelAction(mob);
                WaterEscape.swim(mob, null);
                WaterNavigationGoal.ensure(runtime, pet, mob, actions);
                continue;
            }
            runtime.rememberGround(pet, mob);
            if (TrainingNavigationGoal.hold(runtime, pet, mob)) continue;
            if (actions.greeting(pet) || actions.anticipation().active(pet)) continue;
            Locomotion.Mode mode = Locomotion.choose(
                    pet.illness(),
                    pet.need(Need.HEALTH),
                    pet.need(Need.ENERGY),
                    pet.need(Need.HUNGER),
                    pet.activity(),
                    pet.fetch() != null,
                    now < pet.forcedSitUntilMillis(),
                    pet.order(),
                    pet.staying());
            if (mode == Locomotion.Mode.FOLLOW && pet.activity() != Activity.ATTENDING
                    && !runtime.followingAllowed(pet, owner)) mode = Locomotion.Mode.STAY;
            if (mode == Locomotion.Mode.FETCH) {
                actions.fetchActions().navigate(pet, mob);
                express(pet, mob, owner, mode, now);
                continue;
            }
            Locomotion.Mode previous = previousModes.put(pet.id(), mode);
            actions.markSleep(mob, mode == Locomotion.Mode.SLEEP);
            if ((mode == Locomotion.Mode.SLEEP || pet.activity() == Activity.SLEEPING)
                    && now >= pet.listeningUntilMillis()) PetFx.stopLooking(mob);
            if (actions.moments().tickBelly(pet, mob, owner)) {
                continue;
            }
            boolean held = mode == Locomotion.Mode.SIT || mode == Locomotion.Mode.STAY
                    || mode == Locomotion.Mode.LIE || mode == Locomotion.Mode.SLEEP;
            // The posture waits until the pet reaches the bank it swam towards.
            if (held && WaterNavigationGoal.finishing(runtime, mob)) continue;
            if (!held && runtime.visual().holdsMovement(mob)) {
                net.tfminecraft.companionpets.integration.PetMotion.settle(mob);
                continue;
            }
            if (held && mob.isAware()) actions.clearInteractions(pet);
            if (held) {
                actions.roaming().cancel(pet);
                // Staying, sitting, lying and sleeping keep native AI awake, so the head looks around natively.
                PostureNavigationGoal.hold(runtime, pet, mob);
                actions.roaming().tickListening(pet, mob, now);
            } else {
                if (previous != null && previous != mode || !mob.isAware()) {
                    PetFx.sit(mob, false); PetFx.lie(mob, false); PetFx.stopLooking(mob);
                }
                if (!mob.isAware()) mob.setAware(true);
            }
            if (!held && actions.roaming().tickAttention(pet, mob, now)) {
                continue;
            }
            if (mode == Locomotion.Mode.FOLLOW && mob.getTarget() != null) {
                continue;
            }
            if (actions.social().engaged(pet)) {
                continue;
            }
            if (mode == Locomotion.Mode.PLAY)
                PlayNavigationGoal.ensure(runtime, pet, mob,
                        () -> stepMode(pet, mob, Bukkit.getPlayer(pet.ownerId()), Locomotion.Mode.PLAY, System.currentTimeMillis())).tick();
            else if (mode != Locomotion.Mode.FOLLOW) stepMode(pet, mob, owner, mode, now);
            express(pet, mob, owner, mode, now);
            actions.moments().tick(pet, mob, owner, mode, now);
        }
    }

    private void stepMode(Pet pet, Mob mob, Player owner, Locomotion.Mode mode, long now) {
        boolean sameWorld = owner != null && owner.isOnline() && mob.getWorld().equals(owner.getWorld());
        double speed = Locomotion.speed(pet.illness(), pet.bond(), pet.need(Need.CLEANLINESS), false);
        switch (mode) {
            case SIT, STAY -> {
                mob.getPathfinder().stopPathfinding();
                PetFx.lie(mob, false);
                PetFx.sit(mob, mode == Locomotion.Mode.SIT);
            }
            case LIE, SLEEP -> {
                mob.getPathfinder().stopPathfinding();
                if (mode == Locomotion.Mode.SLEEP) {
                    PetFx.sit(mob, true);
                } else {
                    PetFx.lie(mob, true);
                }
            }
            case PLAY -> {
                PetFx.sit(mob, false);
                PetFx.lie(mob, false);
                if (!sameWorld) {
                    mob.getPathfinder().stopPathfinding();
                    return;
                }
                double distance = mob.getLocation().distance(owner.getLocation());
                if (distance > 3.5) {
                    mob.getPathfinder().moveTo(owner.getLocation(), speed);
                } else {
                    mob.getPathfinder().stopPathfinding();
                    if (mob.getTicksLived() % 30 < 10) {
                        PetFx.jump(mob, false);
                        mob.getWorld().playSound(mob.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.4f);
                    }
                }
            }
            default -> {
            }
        }
        if (pet.bond() >= 70.0 && runtime.random().nextInt(40) == 0) {
            PetFx.hearts(mob, 1);
        }
    }

    private void express(Pet pet, Mob mob, Player owner, Locomotion.Mode mode, long now) {
        boolean critical = pet.causeCritical() || pet.illness() != Illness.NONE;
        if (critical && now >= pet.nextCriticalSoundAtMillis()) {
            pet.nextCriticalSoundAtMillis(now + Math.round(runtime.config().care().criticalSoundSeconds() * 1000.0));
            runtime.voice().ambient(mob);
            Need dominant = DominantNeed.select(pet);
            if (pet.illness() == Illness.SICK || pet.illness() == Illness.UNWELL || pet.illness() == Illness.WEAKENED) {
                PetFx.particle(mob, Particle.SNEEZE, 2);
            } else {
                PetTypeDef type = runtime.config().type(pet.typeId());
                PetFx.need(mob, dominant, type == null ? null : type.foodIcon());
            }
        }
        if (pet.need(Need.CLEANLINESS) < 60.0 && mob.getTicksLived() % 40 < 10) {
            PetFx.particle(mob, Particle.DUST_PLUME, 2);
        }
        if (owner != null && owner.isOnline() && !pet.stored()) {
            double distance = runtime.distance(owner, pet);
            if (distance > runtime.config().ownerNearRadius() && now >= pet.nextCryAtMillis()) {
                pet.nextCryAtMillis(now + Math.round(runtime.config().cryIntervalSeconds() * 1000.0));
                runtime.voice().play(mob, net.tfminecraft.companionpets.config.PetSounds.Event.SAD, .55f, .9f);
                PetFx.particle(mob, Particle.SPLASH, 3);
            }
        }
        if (mode == Locomotion.Mode.SLEEP) {
            return;
        }
    }

    private void watchTraining(long now) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            TrainingSession session = runtime.sessions().training(player.getUniqueId());
            if (session == null) {
                continue;
            }
            Pet pet = runtime.store().get(session.petId());
            PetTypeDef type = pet == null ? null : runtime.config().type(pet.typeId());
            Entity body = pet == null ? null : runtime.entity(pet);
            boolean holding = type != null && type.isTreat(HeldItem.of(player.getInventory().getItemInMainHand()));
            boolean close = body != null
                    && body.getWorld().equals(player.getWorld())
                    && body.getLocation().distance(player.getLocation()) <= runtime.config().training().sessionDistance();
            if (pet == null || pet.stored()) {
                actions.endTraining(player, pet, "your pet went back to the Pet House");
            } else if (type == null) {
                actions.endTraining(player, pet, "your pet type is no longer configured");
            } else if (!holding) {
                String treatName = actions.treatName(pet);
                actions.endTraining(player, pet, java.util.Arrays.stream(player.getInventory().getContents()).anyMatch(item -> type.isTreat(HeldItem.of(item)))
                        ? "you put the " + treatName + " away"
                        : "you ran out of " + treatName);
            } else if (!close) {
                actions.endTraining(player, pet, "you walked too far from " + PetTexts.him(pet.sex()));
            } else if (session.bored() && now > session.rewardUntil()) {
                actions.endForRest(player, pet);
            } else if (session.rewardTrick() != null && now > session.rewardUntil()) {
                actions.missedReward(player, pet, session);
            }
        }
    }

}
