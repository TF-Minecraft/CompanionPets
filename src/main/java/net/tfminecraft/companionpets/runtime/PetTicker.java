package net.tfminecraft.companionpets.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import net.tfminecraft.companionpets.behavior.Locomotion;
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
import net.tfminecraft.companionpets.play.FetchJob;
import net.tfminecraft.companionpets.play.FetchPhase;
import net.tfminecraft.companionpets.session.TrainingSession;
import net.tfminecraft.companionpets.gui.StatLook;
import net.tfminecraft.companionpets.text.PetTexts;

public final class PetTicker implements Runnable {
    private final PetRuntime runtime;
    private final PetActions actions;
    private final Map<UUID, String> shown = new HashMap<>();
    private long lastCareAt;

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
        move(now);
        watchTraining(now);
        lookBars();
    }

    private void care(long now, long elapsed) {
        for (Pet pet : runtime.store().all()) {
            if (pet.dead()) {
                continue;
            }
            Player owner = Bukkit.getPlayer(pet.ownerId());
            boolean online = owner != null && owner.isOnline();
            Entity body = runtime.entity(pet);
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
            if (!pet.stored()
                    && pet.activity() == Activity.NONE
                    && pet.fetch() == null
                    && pet.need(Need.ENERGY) < 25.0) {
                pet.activity(Activity.SLEEPING);
                if (online) {
                    PetFx.bar(owner, pet.name() + " lies down to rest");
                }
            }
            List<CareNotice> notices = NeedClock.advance(pet, new CareInput(
                    presence,
                    mode == Locomotion.Mode.FOLLOW,
                    pet.activity() == Activity.PLAYING,
                    pet.activity() == Activity.SLEEPING,
                    withOwner,
                    elapsed,
                    runtime.config().care(),
                    runtime.config().awayRate()));
            if (pet.dead()) {
                if (body != null) {
                    body.remove();
                }
                runtime.store().remove(pet.id());
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
                            PetFx.ambient(body);
                            PetFx.particle(body, Particle.END_ROD, 6);
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
                    PetFx.tell(owner, favorite.toy() == null
                            ? pet.name() + " has lost interest in " + PetTexts.his(pet.sex()) + " favorite toy."
                            : pet.name() + " has a new favorite toy: the " + PetTexts.itemName(favorite.toy()) + ".");
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
            if (pet.activity() == Activity.SLEEPING && pet.need(Need.ENERGY) >= 100.0) {
                pet.activity(Activity.NONE);
                if (online) {
                    PetFx.bar(owner, pet.name() + " wakes up, fully rested");
                }
            }
        }
    }

    private void move(long now) {
        for (Pet pet : runtime.store().all()) {
            if (pet.stored() || pet.dead()) {
                continue;
            }
            Entity body = runtime.entity(pet);
            if (!(body instanceof Mob mob)) {
                continue;
            }
            Player owner = Bukkit.getPlayer(pet.ownerId());
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
            if (mode == Locomotion.Mode.FETCH) {
                stepFetch(pet, mob, owner, now);
            } else {
                stepMode(pet, mob, owner, mode, now);
            }
            express(pet, mob, owner, mode, now);
            playVisual(pet, mob, mode);
        }
    }

    private void stepMode(Pet pet, Mob mob, Player owner, Locomotion.Mode mode, long now) {
        boolean sameWorld = owner != null && owner.isOnline() && mob.getWorld().equals(owner.getWorld());
        double speed = Locomotion.speed(pet.illness(), pet.bond(), pet.need(Need.CLEANLINESS), false);
        switch (mode) {
            case FOLLOW -> {
                PetFx.sit(mob, false);
                PetFx.lie(mob, false);
                if (!sameWorld) {
                    mob.getPathfinder().stopPathfinding();
                    return;
                }
                if (pet.illness() == Illness.SICK && now < pet.pauseUntilMillis()) {
                    mob.getPathfinder().stopPathfinding();
                    return;
                }
                if (pet.illness() == Illness.SICK && now > pet.pauseUntilMillis() + 8_000L) {
                    pet.pauseUntilMillis(now + 2_000L);
                }
                double distance = mob.getLocation().distance(owner.getLocation());
                if (distance > runtime.config().followTeleportBlocks()) {
                    mob.teleport(PetRuntime.beside(owner));
                    mob.getPathfinder().stopPathfinding();
                } else if (distance > Locomotion.followDistance(pet.bond())) {
                    mob.getPathfinder().moveTo(owner.getLocation(), speed);
                } else {
                    mob.getPathfinder().stopPathfinding();
                    PetFx.look(mob, owner.getEyeLocation());
                }
            }
            case SIT, STAY -> {
                mob.getPathfinder().stopPathfinding();
                PetFx.lie(mob, false);
                PetFx.sit(mob, mode == Locomotion.Mode.SIT);
            }
            case LIE, SLEEP -> {
                mob.getPathfinder().stopPathfinding();
                PetFx.lie(mob, true);
                if (mode == Locomotion.Mode.SLEEP && mob.getTicksLived() % 40 < 10) {
                    PetFx.particle(mob, Particle.WAX_OFF, 1);
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
            case FETCH -> {
            }
            default -> {
            }
        }
        if (pet.bond() >= 70.0 && runtime.random().nextInt(40) == 0) {
            PetFx.hearts(mob, 1);
        }
    }

    private void stepFetch(Pet pet, Mob mob, Player owner, long now) {
        FetchJob job = pet.fetch();
        if (job == null) {
            return;
        }
        if (owner == null || !owner.isOnline()) {
            actions.releaseFetch(pet, null, false);
            return;
        }
        double speed = Locomotion.speed(pet.illness(), pet.bond(), pet.need(Need.CLEANLINESS), job.favorite());
        PetFx.sit(mob, false);
        PetFx.lie(mob, false);
        if (job.phase() == FetchPhase.AIR) {
            Entity projectile = job.projectileId() == null ? null : Bukkit.getEntity(job.projectileId());
            if (projectile == null) {
                if (job.missingSince() == 0L) {
                    job.missingSince(now);
                } else if (now - job.missingSince() > 1_000L) {
                    actions.releaseFetch(pet, owner, true);
                }
                return;
            }
            job.missingSince(0L);
            mob.getPathfinder().moveTo(projectile.getLocation(), speed);
            return;
        }
        if (job.phase() == FetchPhase.GROUND) {
            Entity item = job.itemId() == null ? null : Bukkit.getEntity(job.itemId());
            if (item == null) {
                actions.releaseFetch(pet, owner, true);
                return;
            }
            if (mob.getLocation().distance(item.getLocation()) < 1.7) {
                item.remove();
                job.phase(FetchPhase.CARRY);
                mob.getPathfinder().stopPathfinding();
            } else {
                mob.getPathfinder().moveTo(item.getLocation(), speed);
            }
            return;
        }
        if (!mob.getWorld().equals(owner.getWorld())) {
            actions.releaseFetch(pet, owner, false);
            return;
        }
        if (mob.getLocation().distance(owner.getLocation()) < 2.2) {
            actions.dropPlain(PetRuntime.inFront(owner), job.toy());
            pet.fetch(null);
            pet.activity(Activity.NONE);
            double mood = runtime.config().care().playMoodGain() * (job.favorite() ? runtime.config().care().favoriteMoodMultiplier() : 1.0);
            pet.need(Need.MOOD, pet.need(Need.MOOD) + mood);
            pet.need(Need.ENERGY, pet.need(Need.ENERGY) - runtime.config().care().playEnergyCost());
            PetFx.hearts(mob, job.favorite() ? 6 : 3);
            mob.getPathfinder().stopPathfinding();
        } else {
            mob.getPathfinder().moveTo(owner.getLocation(), speed);
        }
    }

    private void express(Pet pet, Mob mob, Player owner, Locomotion.Mode mode, long now) {
        boolean critical = pet.causeCritical() || pet.illness() != Illness.NONE;
        if (critical && now >= pet.nextCriticalSoundAtMillis()) {
            pet.nextCriticalSoundAtMillis(now + Math.round(runtime.config().care().criticalSoundSeconds() * 1000.0));
            PetFx.ambient(mob);
            Need dominant = DominantNeed.select(pet);
            if (pet.illness() == Illness.SICK || pet.illness() == Illness.UNWELL || pet.illness() == Illness.WEAKENED) {
                PetFx.particle(mob, Particle.SNEEZE, 2);
            } else if (dominant == Need.HUNGER) {
                PetFx.particle(mob, Particle.ANGRY_VILLAGER, 2);
            } else if (dominant == Need.CLEANLINESS) {
                PetFx.particle(mob, Particle.DUST_PLUME, 4);
            }
        }
        if (pet.need(Need.CLEANLINESS) < 60.0 && mob.getTicksLived() % 40 < 10) {
            PetFx.particle(mob, Particle.DUST_PLUME, 2);
        }
        if (owner != null && owner.isOnline() && !pet.stored()) {
            double distance = runtime.distance(owner, pet);
            if (distance > runtime.config().ownerNearRadius() && now >= pet.nextCryAtMillis()) {
                pet.nextCryAtMillis(now + Math.round(runtime.config().cryIntervalSeconds() * 1000.0));
                mob.getWorld().playSound(mob.getLocation(), PetFx.ambientSound(mob.getType()), 0.45f, 0.8f);
                PetFx.particle(mob, Particle.WAX_OFF, 3);
            }
        }
        if (mode == Locomotion.Mode.SLEEP) {
            return;
        }
    }

    private void playVisual(Pet pet, Mob mob, Locomotion.Mode mode) {
        PetTypeDef type = runtime.config().type(pet.typeId());
        if (type == null) {
            return;
        }
        String state = switch (mode) {
            case SIT, STAY -> "SITTING";
            case LIE, SLEEP -> "SLEEPING";
            case PLAY, FETCH -> "PLAYING";
            default -> "FOLLOWING";
        };
        if ((pet.illness() == Illness.SICK || pet.illness() == Illness.WEAKENED) && type.animations().containsKey("SICK")) {
            state = "SICK";
        }
        if (state.equals(shown.get(pet.id()))) {
            return;
        }
        shown.put(pet.id(), state);
        runtime.visual().play(mob, type, state);
    }

    private void watchTraining(long now) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            TrainingSession session = runtime.sessions().training(player.getUniqueId());
            if (session == null) {
                continue;
            }
            Pet pet = runtime.store().get(session.petId());
            PetTypeDef type = pet == null ? null : runtime.config().type(pet.typeId());
            Material treat = type == null ? null : type.favoriteFood();
            Entity body = pet == null ? null : runtime.entity(pet);
            boolean holding = treat != null && player.getInventory().getItemInMainHand().getType() == treat;
            boolean close = body != null
                    && body.getWorld().equals(player.getWorld())
                    && body.getLocation().distance(player.getLocation()) <= runtime.config().training().sessionDistance();
            if (pet == null || pet.stored()) {
                actions.endTraining(player, pet, "your pet went back to the kennel");
            } else if (!holding) {
                String treatName = actions.treatName(pet);
                actions.endTraining(player, pet, player.getInventory().contains(treat)
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

    private void lookBars() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Entity looked = PetActions.lookingAt(player, 4.5);
            Pet pet = runtime.byEntity(looked);
            if (pet == null) {
                continue;
            }
            TrainingSession session = runtime.sessions().training(player.getUniqueId());
            boolean training = session != null && session.petId().equals(pet.id());
            if (training && (session.pendingWord() != null || session.rewardTrick() != null)) {
                continue;
            }
            Component tag = training ? StatLook.tag("Training · say a command", NamedTextColor.AQUA) : null;
            PetFx.status(player, StatLook.summary(pet, tag));
        }
    }

}
