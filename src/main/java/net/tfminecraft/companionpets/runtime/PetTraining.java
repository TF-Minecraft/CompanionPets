package net.tfminecraft.companionpets.runtime;

import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import net.tfminecraft.companionpets.item.ItemRef;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import net.tfminecraft.companionpets.chat.SpokenOrder;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.fx.PetHolograms;
import net.tfminecraft.companionpets.gui.PetMenus;
import net.tfminecraft.companionpets.gui.StatLook;
import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.Trick;
import net.tfminecraft.companionpets.session.PlayerHints;
import net.tfminecraft.companionpets.session.TrainingSession;
import net.tfminecraft.companionpets.text.PetTexts;
import net.tfminecraft.companionpets.training.TrainingMath;
import net.tfminecraft.companionpets.training.TrainingSettings;

final class PetTraining {
    private static boolean sick(Pet pet) { return pet.illness() == Illness.SICK || pet.illness() == Illness.WEAKENED; }
    private static final long ATTEMPT_HOLOGRAM_TICKS = 60L;
    private static final long REWARD_HOLOGRAM_TICKS = 60L;
    private static final long LEARNED_HOLOGRAM_TICKS = 100L;
    interface TrickExecutor { void perform(Player player, Pet pet, Entity entity, Trick trick, boolean partial); }
    private final PetRuntime runtime;
    private final PetMenus menus;
    private final PetHolograms holograms;
    private final PlayerHints hints;
    private final TrickExecutor executor;
    private final java.util.Map<UUID, Long> headTiltUntil = new java.util.HashMap<>();
    PetTraining(PetRuntime runtime, PetMenus menus, PetHolograms holograms, PlayerHints hints, TrickExecutor executor) {
        this.runtime = runtime; this.menus = menus; this.holograms = holograms; this.hints = hints; this.executor = executor;
    }
    private void comfort(Pet pet, long now) { pet.nextCryAtMillis(now + Math.round(runtime.config().cryIntervalSeconds() * 1000)); }
    public void bindTrick(Player player, Pet pet, String word, Trick trick) {
        if (!checkTrick(player, pet, trick)) return;
        pet.bindWord(word, trick);
        TrainingSession session = runtime.sessions().training(player.getUniqueId());
        if (session != null) {
            session.pendingWord(null);
        }
        player.closeInventory();
        String name = net.tfminecraft.companionpets.training.TrickAvailability.name(runtime, trick);
        hologram(pet, "“" + word + "” → " + name, NamedTextColor.WHITE, ATTEMPT_HOLOGRAM_TICKS);
        PetFx.bar(player, "Say “" + word + "” again to practice " + name);
        PetFx.cue(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f);
        if (pet.progress(trick) >= runtime.config().training().learnedAt()) {
            PetFx.tell(player, "“" + word + "” is now another way to ask " + pet.name() + " to " + name.toLowerCase(Locale.ROOT)
                    + ". " + PetTexts.He(pet.sex()) + " already knows this trick.");
            return;
        }
        PetFx.tell(player, "“" + word + "” now means " + name + " for " + pet.name() + ". "
                + PetTexts.He(pet.sex()) + " doesn't know it yet, so keep practicing.");
        if (hints.firstTime(player, "practice")) {
            PetFx.tip(player, "Say “" + word + "” while looking at " + pet.name() + " and reward each try with "
                    + treatName(pet) + " right away. Early tries are clumsy, but once " + PetTexts.he(pet.sex())
                    + " catches on, rewarded successes teach much faster.");
        }
    }

    void handleTrainingChat(Player player, String text, long now) {
        Entity looked = PetActions.lookingAt(player, 6.0);
        Pet pet = runtime.byEntity(looked);
        handleTrainingChat(player, text, now, pet, looked);
    }

    void handleTrainingChat(Player player, String text, long now, Pet pet, Entity looked) {
        if (pet == null || !pet.ownerId().equals(player.getUniqueId()) || pet.stored()) {
            return;
        }
        String line = SpokenOrder.key(text);
        if (line.isEmpty()) {
            return;
        }
        Trick requested = pet.trickFor(line);
        if (requested != null && !checkTrick(player, pet, requested)) return;
        TrainingSession session = runtime.sessions().training(player.getUniqueId());
        TrainingSettings training = runtime.config().training();
        if (session != null && session.petId().equals(pet.id())) {
            if (session.pendingWord() != null) {
                return;
            }
            if (session.bored()) {
                PetFx.bar(player, pet.name() + " has stopped listening. Give " + PetTexts.him(pet.sex()) + " a last treat");
                return;
            }
            Trick known = pet.trickFor(line);
            if (known == null) {
                session.pendingWord(line);
                menus.openTricks(player, pet, line);
                if (looked != null) {
                    trainingHeadTilt(player, pet, looked, now);
                    PetFx.particle(looked, Particle.END_ROD, 4);
                }
                PetFx.bar(player, pet.name() + " tilts " + PetTexts.his(pet.sex()) + " head at “" + line + "”. Pick what it means");
                return;
            }
            if (pet.progress(known) >= training.learnedAt()) {
                executor.perform(player, pet, looked, known, false);
                PetFx.bar(player, pet.name() + " already knows " + PetTexts.trickName(known) + ". No practice needed");
                return;
            }
            if (pet.activity() == Activity.SLEEPING && known != Trick.SLEEP && known != Trick.COME) {
                executor.perform(player, pet, looked, known, false);
                return;
            }
            if (!spendTrainingEffort(player, pet)) {
                return;
            }
            int attempts = session.addAttempt();
            session.lastWord(line);
            TrainingMath.Attempt result = TrainingMath.attempt(pet.progress(known), runtime.random(), training);
            executor.perform(player, pet, looked, known, result != TrainingMath.Attempt.SUCCESS);
            session.reward(
                    now + Math.round(training.rewardWindowSeconds() * 1000.0),
                    result == TrainingMath.Attempt.SUCCESS,
                    known);
            boolean last = attempts >= training.attemptsBeforeBored();
            if (last) {
                session.bored(true);
                runtime.sessions().rest(pet.id(), now + Math.round(training.restSeconds() * 1000.0));
            }
            String encourage = last
                    ? "Last try before a break. A treat now still helps a little"
                    : "A treat now encourages " + PetTexts.him(pet.sex()) + " a little, or say “" + line + "” again";
            switch (result) {
                case SUCCESS -> {
                    if (known.kind() != Trick.Kind.CUSTOM) hologram(pet, "Nailed it! Quick, give a treat", NamedTextColor.GREEN, ATTEMPT_HOLOGRAM_TICKS);
                    PetFx.bar(player, "Reward " + PetTexts.him(pet.sex()) + " now! Right-click " + pet.name()
                            + " with " + treatName(pet) + (last ? " · last try before a break" : ""));
                    PetFx.cue(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.4f);
                }
                case PARTIAL -> {
                    if (known.kind() != Trick.Kind.CUSTOM) hologram(pet, "Almost got it…", NamedTextColor.YELLOW, ATTEMPT_HOLOGRAM_TICKS);
                    PetFx.bar(player, encourage);
                }
                case FAIL -> {
                    trainingHeadTilt(player, pet, looked, now);
                    if (known.kind() != Trick.Kind.CUSTOM) hologram(pet, "Doesn't get it yet", NamedTextColor.GRAY, ATTEMPT_HOLOGRAM_TICKS);
                    PetFx.bar(player, encourage);
                }
            }
            return;
        }
        Trick known = pet.trickFor(line);
        if (known == null) {
            return;
        }
        if (pet.progress(known) >= training.learnedAt()) {
            executor.perform(player, pet, looked, known, false);
            return;
        }
        if (runtime.sessions().resting(pet.id(), now)) {
            PetFx.bar(player, pet.name() + " " + restPhrase(pet, now));
            return;
        }
        PetFx.bar(player, pet.name() + " isn't training right now. Hold " + treatName(pet)
                + " and right-click " + PetTexts.him(pet.sex()) + " to start");
    }

    public void endTraining(Player player, Pet pet, String reason) {
        runtime.sessions().clearTraining(player.getUniqueId());
        if (!player.isOnline()) {
            return;
        }
        String name = pet == null ? "your pet" : pet.name();
        PetFx.tell(player, "Training with " + name + " is over: " + reason + ".");
        PetFx.cue(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f);
    }

    public void missedReward(Player player, Pet pet, TrainingSession session) {
        boolean success = Boolean.TRUE.equals(session.rewardSuccess());
        session.clearReward();
        if (success && pet != null && player.isOnline()) {
            PetFx.bar(player, "You missed the moment to reward " + pet.name() + ". Say “" + session.lastWord() + "” again");
        }
    }

    private String restPhrase(Pet pet, long now) {
        double total = runtime.config().training().restSeconds() * 1000.0;
        double left = runtime.sessions().restUntil(pet.id()) - now;
        if (total <= 0.0 || left <= total * 0.15) {
            return "is almost ready to train again";
        }
        if (left <= total * 0.5) {
            return "is still resting after training";
        }
        return "needs a good rest before training again";
    }

    public String treatName(Pet pet) {
        PetTypeDef type = runtime.config().type(pet.typeId());
        return type == null || type.treats().isEmpty() ? "a treat" : String.join(" or ", type.treats().stream().map(ItemRef::name).toList());
    }

    void beginTraining(Player player, Pet pet, Entity entity) {
        runtime.visual().cancelAction(entity);
        PetTypeDef trainingType = runtime.config().type(pet.typeId());
        if (trainingType == null || trainingType.tricks().stream().noneMatch(t -> allowsTrick(pet, t))) {
            PetFx.bar(player, pet.name() + " has no available tricks to learn");
            return;
        }
        TrainingSession existing = runtime.sessions().training(player.getUniqueId());
        if (existing != null && existing.petId().equals(pet.id())) {
            PetFx.bar(player, PetTexts.trainingPrompt(pet));
            return;
        }
        long now = System.currentTimeMillis();
        if (runtime.sessions().resting(pet.id(), now)) {
            hologram(pet, "Resting…", NamedTextColor.GRAY, ATTEMPT_HOLOGRAM_TICKS);
            PetFx.bar(player, pet.name() + " " + restPhrase(pet, now));
            return;
        }
        if (existing != null) {
            endTraining(player, runtime.store().get(existing.petId()), "you started training another pet");
        }
        runtime.sessions().training(player.getUniqueId(), new TrainingSession(pet.id()));
        PetFx.look(entity, player.getEyeLocation());
        if (entity instanceof Mob mob) {
            mob.getPathfinder().stopPathfinding();
        }
        PetFx.bar(player, PetTexts.trainingPrompt(pet));
        PetFx.tell(player, "Training " + pet.name() + ". Look at " + PetTexts.him(pet.sex()) + " and say a command in chat."
                + practiceList(pet));
        PetFx.cue(player, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.2f);
        if (hints.firstTime(player, "training")) {
            PetFx.tip(player, "Any word works. If " + pet.name() + " doesn't know it yet, you'll pick which trick it means. "
                    + "Keep holding the " + treatName(pet) + " and stay close, or the session ends.");
        }
    }

    private String practiceList(Pet pet) {
        TrainingSettings training = runtime.config().training();
        java.util.List<String> words = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Trick> entry : pet.words().entrySet()) {
            if (!allowsTrick(pet, entry.getValue())) continue;
            double progress = pet.progress(entry.getValue());
            if (progress < training.learnedAt()) {
                words.add("“" + entry.getKey() + "”");
            }
        }
        return words.isEmpty() ? "" : " Still practicing: " + String.join(", ", words) + ".";
    }

    private boolean spendTrainingEffort(Player player, Pet pet) {
        if (TrainingMath.attentionBlocked(pet.need(Need.HUNGER), pet.need(Need.ENERGY), sick(pet))) {
            String reason = pet.need(Need.ENERGY) < 25.0 ? "tired" : "attention";
            hologram(pet, PetTexts.refusal(pet.name(), pet.sex(), reason), NamedTextColor.RED, ATTEMPT_HOLOGRAM_TICKS);
            endTraining(player, pet, focusReason(pet));
            return false;
        }
        TrainingSettings training = runtime.config().training();
        pet.need(Need.ENERGY, pet.need(Need.ENERGY) - training.attemptEnergyCost());
        pet.need(Need.HUNGER, pet.need(Need.HUNGER) - training.attemptHungerCost());
        pet.need(Need.MOOD, pet.need(Need.MOOD) - training.attemptMoodCost());
        return true;
    }

    void reward(Player player, Pet pet, TrainingSession session, ItemStack hand) {
        Trick trick = session.rewardTrick();
        if (!checkTrick(player, pet, trick)) {
            session.clearReward();
            return;
        }
        if (trick == null || !net.tfminecraft.companionpets.item.HandItems.consume(player, hand)) {
            return;
        }
        boolean success = Boolean.TRUE.equals(session.rewardSuccess());
        TrainingSettings training = runtime.config().training();
        double before = pet.progress(trick);
        double after = TrainingMath.afterReward(before, success, pet.need(Need.MOOD), pet.bond(), training);
        pet.progress(trick, after);
        pet.need(Need.HUNGER, pet.need(Need.HUNGER) + runtime.config().training().treatHungerGain());
        session.clearReward();
        Entity entity = runtime.entity(pet);
        if (entity != null) {
            PetFx.eat(entity);
            runtime.visual().play(entity, runtime.config().type(pet.typeId()), "EAT");
            if (success) {
                PetFx.hearts(entity, 3);
            }
        }
        comfort(pet, System.currentTimeMillis());
        showProgress(player, pet, trick, session.lastWord(), before, after, success, training);
        if (session.bored()) {
            endForRest(player, pet);
        }
    }

    public void endForRest(Player player, Pet pet) {
        endTraining(player, pet, PetTexts.he(pet.sex()) + " needs a break. Let " + PetTexts.him(pet.sex())
                + " rest for a while before training again");
        if (player.isOnline() && hints.firstTime(player, "rest")) {
            PetFx.tip(player, "Pets can only focus for a few tries at a time. Progress is kept, so come back after the break.");
        }
    }

    private void showProgress(Player player, Pet pet, Trick trick, String word, double before, double after, boolean success,
            TrainingSettings training) {
        String name = net.tfminecraft.companionpets.training.TrickAvailability.name(runtime, trick);
        String say = "“" + (word == null ? name.toLowerCase(Locale.ROOT) : word) + "”";
        if (before < training.learnedAt() && after >= training.learnedAt()) {
            hologram(pet, "✦ Learned " + name + "! ✦", NamedTextColor.GOLD, LEARNED_HOLOGRAM_TICKS);
            Entity entity = runtime.entity(pet);
            if (entity != null) {
                PetFx.particle(entity, Particle.TOTEM_OF_UNDYING, 20);
            }
            PetFx.tell(player, pet.name() + " has learned " + name + "! Say " + say
                    + " any time and " + PetTexts.he(pet.sex()) + "'ll do it, no treats needed.");
            PetFx.cue(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
            return;
        }
        Component progress = StatLook.bar(TrainingMath.percentLearned(after, training), NamedTextColor.AQUA);
        if (before < training.sometimesAt() && after >= training.sometimesAt()) {
            holograms.show(runtime.entity(pet), Component.text("Catching on to " + name + "  ", NamedTextColor.AQUA).append(progress),
                    REWARD_HOLOGRAM_TICKS);
            PetFx.tell(player, pet.name() + " is catching on to " + name + "! From now on " + PetTexts.he(pet.sex())
                    + " gets it right more often, and every success you reward counts a lot more.");
            PetFx.cue(player, Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
            return;
        }
        holograms.show(runtime.entity(pet),
                Component.text((success ? "Good job! " : "A little closer ") + name + "  ",
                        success ? NamedTextColor.GREEN : NamedTextColor.YELLOW).append(progress),
                REWARD_HOLOGRAM_TICKS);
        PetFx.bar(player, Component.text(name + " ", NamedTextColor.WHITE).append(progress)
                .append(Component.text("  Say " + say + " again to keep practicing", NamedTextColor.GRAY)));
    }

    String focusReason(Pet pet) {
        if (sick(pet)) {
            return PetTexts.he(pet.sex()) + " is too unwell to focus";
        }
        if (pet.need(Need.ENERGY) < 25.0) {
            return PetTexts.he(pet.sex()) + " is too tired to focus. Let " + PetTexts.him(pet.sex()) + " rest";
        }
        return PetTexts.he(pet.sex()) + " is too hungry to focus. Feed " + PetTexts.him(pet.sex()) + " first";
    }

    void hologram(Pet pet, String text, NamedTextColor color, long ticks) {
        holograms.show(runtime.entity(pet), Component.text(text, color), ticks);
    }

    private void trainingHeadTilt(Player player, Pet pet, Entity entity, long now) {
        if (entity == null || pet.activity() == Activity.SLEEPING) return;
        headTiltUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        UUID id = entity.getUniqueId();
        if (headTiltUntil.containsKey(id)) return;
        headTiltUntil.put(id, now + 3_000L);
        PetFx.look(entity, player.getEyeLocation());
        runtime.visual().play(entity, runtime.config().type(pet.typeId()), "HEAD_TILT");
        if (entity instanceof Wolf wolf) {
            wolf.setInterested(true);
            Bukkit.getScheduler().runTaskLater(runtime.plugin(), () -> {
                if (wolf.isValid()) wolf.setInterested(false);
            }, 24L);
        }
    }

    boolean allowsTrick(Pet pet, Trick trick) {
        return net.tfminecraft.companionpets.training.TrickAvailability.allows(runtime, pet, trick);
    }

    boolean checkTrick(Player player, Pet pet, Trick trick) {
        if (allowsTrick(pet, trick)) return true;
        PetFx.bar(player, "That trick is not available for " + pet.name());
        return false;
    }


}
