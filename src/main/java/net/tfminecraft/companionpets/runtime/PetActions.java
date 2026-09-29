package net.tfminecraft.companionpets.runtime;

import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Wolf;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import net.tfminecraft.companionpets.behavior.Locomotion;
import net.tfminecraft.companionpets.care.DominantNeed;
import net.tfminecraft.companionpets.care.Feeding;
import net.tfminecraft.companionpets.chat.SpokenOrder;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.fx.PetHolograms;
import net.tfminecraft.companionpets.gui.MenuHolder;
import net.tfminecraft.companionpets.gui.PetMenus;
import net.tfminecraft.companionpets.gui.StatLook;
import net.tfminecraft.companionpets.management.Quota;
import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.NeedBand;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetOrder;
import net.tfminecraft.companionpets.pet.PetPersonality;
import net.tfminecraft.companionpets.pet.PetSex;
import net.tfminecraft.companionpets.pet.SexMode;
import net.tfminecraft.companionpets.pet.Trick;
import net.tfminecraft.companionpets.play.FavoriteToy;
import net.tfminecraft.companionpets.play.FetchJob;
import net.tfminecraft.companionpets.play.ThrowSpeed;
import net.tfminecraft.companionpets.session.HatchPrompt;
import net.tfminecraft.companionpets.session.PlayerHints;
import net.tfminecraft.companionpets.session.RenamePrompt;
import net.tfminecraft.companionpets.session.ReleasePrompt;
import net.tfminecraft.companionpets.session.TrainingSession;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.text.Names;
import net.tfminecraft.companionpets.text.PetTexts;
import net.tfminecraft.companionpets.training.TrainingMath;
import net.tfminecraft.companionpets.training.TrainingSettings;

public final class PetActions {
    private static final long PROMPT_MILLIS = 60_000L;

    private static final long ATTEMPT_HOLOGRAM_TICKS = 60L;
    private static final long REWARD_HOLOGRAM_TICKS = 60L;
    private static final long LEARNED_HOLOGRAM_TICKS = 100L;
    private static final long PET_COOLDOWN_MILLIS = 30_000L;
    private static final double PET_MOOD_GAIN = 4.0;

    private final PetRuntime runtime;
    private final PetMenus menus;
    private final PetHolograms holograms;
    private final PetMoments moments;
    private final PetSocial social;
    private final PetRoaming roaming;
    private final PlayerHints hints;
    private final java.util.Map<UUID, Long> pettedAt = new java.util.HashMap<>();
    private final java.util.Map<UUID, Long> headTiltUntil = new java.util.HashMap<>();
    private final java.util.Map<UUID, SpinJob> spins = new java.util.HashMap<>();
    private final java.util.Map<UUID, CalmProgress> calming = new java.util.HashMap<>();

    public PetActions(PetRuntime runtime) {
        this.runtime = runtime;
        this.menus = new PetMenus(runtime);
        this.holograms = new PetHolograms(runtime.plugin());
        this.moments = new PetMoments(runtime);
        this.roaming = new PetRoaming(runtime);
        this.social = new PetSocial(runtime, roaming);
        this.hints = new PlayerHints(runtime.plugin());
    }

    public PetMenus menus() {
        return menus;
    }

    public PetHolograms holograms() {
        return holograms;
    }

    PetMoments moments() {
        return moments;
    }

    PetSocial social() {
        return social;
    }

    PetRoaming roaming() {
        return roaming;
    }

    public void orderLookingAt(Player player, String word) {
        Pet target = runtime.byEntity(lookingAt(player, 6.0));
        if (target == null || target.stored() || !target.ownerId().equals(player.getUniqueId())) {
            PetFx.tell(player, "Look at one of your own pets to give it an order.");
            return;
        }
        TrainingSession session = runtime.sessions().training(player.getUniqueId());
        if (target.trickFor(SpokenOrder.key(word)) == null
                && (session == null || !session.petId().equals(target.id()))) {
            PetFx.tell(player, target.name() + " has not learned that word. Check the Tricks page in the pet profile.");
            return;
        }
        handleTrainingChat(player, word, System.currentTimeMillis());
    }

    public java.util.List<String> orderWordsLookingAt(Player player) {
        Pet pet = runtime.byEntity(lookingAt(player, 6.0));
        if (pet == null || !pet.ownerId().equals(player.getUniqueId())) return java.util.List.of();
        java.util.List<String> words = new java.util.ArrayList<>(pet.words().keySet());
        words.removeIf(word -> !allowsTrick(pet, pet.trickFor(word)));
        if (allowsTrick(pet, Trick.COME) && pet.trickFor("follow") == Trick.COME && !words.contains("follow")) words.add("follow");
        return words;
    }

    public void clearInteractions(Pet pet) {
        cancelSpin(pet);
        roaming.cancel(pet);
        calming.remove(pet.id());
        moments.cancel(pet);
        social.cancel(pet);
    }

    public void clearInteractions() {
        spins.clear();
        roaming.clear();
        calming.clear();
        moments.clear();
        social.clear();
    }

    public boolean calmLookingAt(Player player) {
        Entity target = lookingAt(player, 6.0);
        Pet pet = runtime.byEntity(target);
        if (pet == null || !(pet.ownerId().equals(player.getUniqueId())
                || vanillaTarget(pet) == player || player.hasPermission("companionpets.test"))) {
            PetFx.tell(player, "Look at the pet you want to calm.");
            return false;
        }
        if (!calmInteraction(player, pet)) {
            PetFx.tell(player, pet.name() + " is already calm.");
            return false;
        }
        return true;
    }

    private boolean calmInteraction(Player player, Pet pet) {
        boolean hostile = social.status(pet) != null || vanillaTarget(pet) != null;
        if (!hostile || !(pet.ownerId().equals(player.getUniqueId())
                || vanillaTarget(pet) == player || player.hasPermission("companionpets.test"))) return false;
        long now = System.currentTimeMillis();
        CalmProgress progress = calming.get(pet.id());
        int count = progress != null && progress.player.equals(player.getUniqueId()) && now - progress.at < 8_000L
                ? progress.clicks + 1 : 1;
        int required = runtime.config().social().calmClicks();
        if (count < required) {
            calming.put(pet.id(), new CalmProgress(player.getUniqueId(), count, now));
            Entity body = runtime.entity(pet);
            if (body != null) PetFx.hearts(body, 1);
            PetFx.bar(player, "Calming " + pet.name() + "... " + count + "/" + required + " (right-click again)");
            return true;
        }
        calming.remove(pet.id());
        social.calm(player, pet);
        Entity body = runtime.entity(pet);
        if (body instanceof Mob mob && mob.getTarget() instanceof Player) {
            mob.setTarget(null);
            if (mob instanceof Wolf wolf) wolf.setAngry(false);
            mob.getPathfinder().stopPathfinding();
            PetFx.hearts(mob, 2);
        }
        PetFx.bar(player, pet.name() + " has calmed down");
        return true;
    }

    private record CalmProgress(UUID player, int clicks, long at) { }

    private Player vanillaTarget(Pet pet) {
        Entity body = runtime.entity(pet);
        return body instanceof Mob mob && mob.getTarget() instanceof Player target ? target : null;
    }

    public boolean setTestPersonality(Player player, String name) {
        Entity target = lookingAt(player, 6.0);
        Pet pet = runtime.byEntity(target);
        if (pet == null) {
            PetFx.tell(player, "Look at a pet first.");
            return false;
        }
        PetPersonality personality;
        try {
            personality = PetPersonality.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            PetFx.tell(player, "Choose friendly, playful, shy, territorial, or grumpy.");
            return false;
        }
        pet.personality(personality);
        runtime.store().save();
        PetFx.tell(player, pet.name() + " now has a " + personality.label().toLowerCase(Locale.ROOT) + " personality.");
        return true;
    }

    public boolean setTestOwner(Player player, String name) {
        Pet pet = runtime.byEntity(lookingAt(player, 6.0));
        if (pet == null) {
            PetFx.tell(player, "Look at a pet before changing its owner.");
            return false;
        }
        UUID next;
        String label;
        if (name.equalsIgnoreCase("self")) {
            next = player.getUniqueId();
            label = player.getName();
        } else if (name.equalsIgnoreCase("fake")) {
            next = UUID.nameUUIDFromBytes(("CompanionPets:test-owner:" + pet.id())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            label = "a fictional test owner";
        } else {
            Player online = Bukkit.getPlayerExact(name);
            if (online != null) {
                next = online.getUniqueId();
                label = online.getName();
            } else {
                try {
                    next = UUID.fromString(name);
                    label = next.toString();
                } catch (IllegalArgumentException ex) {
                    PetFx.tell(player, "Use self, fake, an online player name, or a UUID.");
                    return false;
                }
            }
        }
        if (next.equals(pet.ownerId())) {
            PetFx.tell(player, pet.name() + " already belongs to " + label + ".");
            return true;
        }
        if (!Quota.canBringOut(runtime.store().countOut(next), runtime.config().limits().maxOut())) {
            PetFx.tell(player, "That owner already has the maximum number of pets outside.");
            return false;
        }
        Player previous = Bukkit.getPlayer(pet.ownerId());
        RenamePrompt pendingRename = runtime.sessions().rename(pet.ownerId());
        if (pendingRename != null && pendingRename.petId().equals(pet.id())) {
            runtime.sessions().clearRename(pet.ownerId());
        }
        clearInteractions(pet);
        releaseFetch(pet, previous, true);
        pet.ownerId(next);
        pet.order(PetOrder.FOLLOW);
        pet.staying(false);
        Entity body = runtime.entity(pet);
        if (body instanceof Tameable tameable) {
            tameable.setTamed(true);
            tameable.setOwner(Bukkit.getOfflinePlayer(next));
        }
        runtime.store().save();
        PetFx.tell(player, pet.name() + " now belongs to " + label + ".");
        return true;
    }

    public boolean triggerTestSocial(Player player, String kind) {
        Entity target = lookingAt(player, 6.0);
        Pet pet = runtime.byEntity(target);
        if (pet == null || !pet.ownerId().equals(player.getUniqueId())) {
            PetFx.tell(player, "Look at one of your own pets first.");
            return false;
        }
        String mode = kind.toLowerCase(Locale.ROOT);
        if (!java.util.Set.of("sniff", "chase", "bark").contains(mode)) {
            PetFx.tell(player, "Choose sniff, chase, or bark.");
            return false;
        }
        if (!social.trigger(player, pet, mode)) {
            PetFx.tell(player, "No eligible pet is nearby. Barking needs two territorial pets close together.");
            return false;
        }
        return true;
    }

    public boolean triggerTestMoment(Player player, String moment) {
        Entity target = lookingAt(player, 6.0);
        Pet pet = runtime.byEntity(target);
        if (pet == null || pet.stored() || !pet.ownerId().equals(player.getUniqueId()) || !(target instanceof Mob body)) {
            PetFx.tell(player, "Look at one of your pets that is outside the shelter first.");
            return false;
        }
        Locomotion.Mode mode = Locomotion.choose(pet.illness(), pet.need(Need.HEALTH),
                pet.need(Need.ENERGY), pet.need(Need.HUNGER), pet.activity(), pet.fetch() != null,
                System.currentTimeMillis() < pet.forcedSitUntilMillis(), pet.order(), pet.staying());
        if (mode != Locomotion.Mode.FOLLOW || pet.activity() != Activity.NONE) {
            PetFx.tell(player, pet.name() + " must be awake, standing, and following you for a moment.");
            return false;
        }
        boolean triggered = switch (moment.toLowerCase(Locale.ROOT)) {
            case "affection", "cry" -> moments.triggerAffection(pet, body, player);
            case "bark", "anger" -> moments.triggerBark(pet, body, player);
            case "mischief", "naughty" -> moments.triggerMischief(pet, body, player);
            case "dig", "gift" -> moments.triggerDig(pet, body, player);
            case "belly" -> {
                boolean started = moments.triggerBelly(pet, body, player);
                if (started) {
                    social.cancel(pet);
                    roaming.cancel(pet);
                }
                yield started;
            }
            default -> false;
        };
        if (!triggered) {
            PetFx.tell(player, switch (moment.toLowerCase(Locale.ROOT)) {
                case "mischief", "naughty" -> "No small plant is nearby, or the world has mobGriefing disabled. Place grass, a fern, or a flower beside the pet and try again.";
                case "bark", "anger" -> "The pet could not find anything nearby to react to.";
                case "dig", "gift" -> "This pet cannot dig right now. Check that digging is enabled and that it is not carrying a toy.";
                case "belly" -> "Belly rub must be enabled and needs a healthy pet on land with lie_back, belly_up, and get_up clips. Finish other actions first.";
                default -> "Choose affection, bark, mischief, dig, or belly.";
            });
        }
        return triggered;
    }

    public boolean useOnPet(Player player, Entity entity, ItemStack hand) {
        Pet pet = runtime.byEntity(entity);
        if (pet == null || pet.stored()) {
            return false;
        }
        PetTypeDef type = runtime.config().type(pet.typeId());
        if (type == null) {
            return false;
        }
        Material held = hand == null ? Material.AIR : hand.getType();
        boolean owner = pet.ownerId().equals(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (held == type.medicine() && (pet.illness() == Illness.SICK || pet.illness() == Illness.WEAKENED)) {
            if (!consumeHand(player, hand)) {
                return true;
            }
            pet.treated(true);
            pet.need(Need.HEALTH, pet.need(Need.HEALTH) + runtime.config().care().medicineHealthBump());
            comfort(pet, now);
            PetFx.hearts(entity, 3);
            PetFx.bar(player, pet.name() + " is already starting to feel better");
            return true;
        }
        if (held == Material.BRUSH) {
            pet.need(Need.CLEANLINESS, 100);
            comfort(pet, now);
            PetFx.hearts(entity, 2);
            PetFx.bar(player, pet.name() + "'s coat is clean and shiny again");
            return true;
        }
        if (owner && held == type.favoriteFood()) {
            TrainingSession session = runtime.sessions().training(player.getUniqueId());
            if (session != null && session.petId().equals(pet.id()) && session.rewardTrick() != null && session.rewardUntil() > now) {
                reward(player, pet, session, hand);
                return true;
            }
            if (pet.need(Need.HUNGER) >= 60.0) {
                if (TrainingMath.attentionBlocked(pet.need(Need.HUNGER), pet.need(Need.ENERGY), sick(pet))) {
                    PetFx.bar(player, pet.name() + " can't train right now: " + focusReason(pet));
                    return true;
                }
                beginTraining(player, pet, entity);
                return true;
            }
        }
        Double gain = type.foodGain(held);
        if (gain != null) {
            if (feed(player, pet, entity, hand, gain, held == type.favoriteFood())) {
                return true;
            }
            if (owner && held == type.favoriteFood()) {
                PetFx.bar(player, pet.name() + " was too hungry to train, so " + PetTexts.he(pet.sex())
                        + " ate the treat. Keep feeding " + PetTexts.him(pet.sex()) + " before training");
            } else {
                PetFx.bar(player, pet.name() + " eats happily");
            }
            return true;
        }
        if (held == Material.AIR) {
            if (player.isSneaking()) {
                menus.openCare(player, pet);
                return true;
            }
            if (calmInteraction(player, pet)) {
                return true;
            }
            checkIn(player, pet, entity, type, now);
            return true;
        }
        if (type.acceptsToy(held)) {
            PetFx.bar(player, "Throw it into the air and " + pet.name() + " will fetch it");
            return true;
        }
        return false;
    }

    private boolean ownerNearby(Pet pet) {
        Player owner = Bukkit.getPlayer(pet.ownerId());
        if (owner == null || !owner.isOnline()) {
            return false;
        }
        return runtime.distance(owner, pet) <= runtime.config().ownerNearRadius();
    }

    private void checkIn(Player player, Pet pet, Entity entity, PetTypeDef type, long now) {
        if (runtime.visual().belly(entity)) {
            if (entity instanceof Mob mob && moments.petBelly(pet, mob, player, now)) {
                cheer(pet, now, PET_MOOD_GAIN);
                return;
            }
            if (!pet.ownerId().equals(player.getUniqueId())) return;
            runtime.visual().cancelAction(entity);
        }
        PetFx.look(entity, player.getEyeLocation());
        if (!ownerNearby(pet)) {
            PetFx.bar(player, PetTexts.missesOwner(pet.name(), pet.sex()));
            PetFx.sad(entity);
            return;
        }
        if (pet.illness() != Illness.NONE) {
            String medicine = type.medicine() == null ? "medicine" : "a " + PetTexts.itemName(type.medicine().name());
            cheer(pet, now, runtime.config().care().playMoodGain());
            PetFx.bar(player, PetTexts.illnessCheck(pet.name(), pet.sex(), pet.illness(), medicine)
                    + ". A little attention still cheers " + PetTexts.him(pet.sex()) + " up");
            PetFx.sad(entity);
            PetFx.hearts(entity, 1);
            return;
        }
        Need need = DominantNeed.select(pet);
        if (need != null) {
            PetFx.bar(player, PetTexts.needCheck(pet.name(), pet.sex(), need, NeedBand.of(pet.need(need)) == NeedBand.CRITICAL));
            PetFx.sad(entity);
            return;
        }
        comfort(pet, now);
        cheer(pet, now, PET_MOOD_GAIN);
        if (runtime.sessions().resting(pet.id(), now)) {
            PetFx.bar(player, PetTexts.restingCheck(pet.name(), pet.sex()));
            PetFx.happy(entity, false);
            PetFx.hearts(entity, 1);
            return;
        }
        boolean devoted = pet.bond() >= 85.0;
        if (entity instanceof Mob mob && moments.petBelly(pet, mob, player, now)) {
            social.cancel(pet);
            roaming.cancel(pet);
            return;
        }
        boolean animatedPet = runtime.visual().play(entity, type, "PET");
        PetFx.bar(player, PetTexts.petted(pet.name(), pet.sex(), pet.typeId(), devoted));
        PetFx.happy(entity, runtime.random().nextInt(3) == 0);
        PetFx.hearts(entity, devoted ? 4 : 2);
        if (!animatedPet && !runtime.visual().holdsMovement(entity)
                && devoted && pet.order() != PetOrder.SIT && !pet.staying()) {
            PetFx.jump(entity, true);
        }
    }

    public void useWorld(Player player, ItemStack hand, Block clicked, BlockFace face, boolean sneaking, boolean air) {
        Material held = hand == null ? Material.AIR : hand.getType();
        if (clicked != null && isKennel(clicked) && !sneaking) {
            UUID owner = runtime.store().kennelOwner(PetStore.kennelKey(
                    clicked.getWorld().getName(), clicked.getX(), clicked.getY(), clicked.getZ()));
            if (owner != null && !owner.equals(player.getUniqueId())) {
                PetFx.bar(player, "This shelter belongs to someone else");
                return;
            }
            menus.openKennel(player);
            return;
        }
        if (sneaking && held == runtime.config().kennel() && clicked != null && face != null) {
            placeKennel(player, hand, clicked, face);
            return;
        }
        PetTypeDef egg = runtime.config().byEgg(hand);
        if (egg != null && hand != null) {
            beginHatch(player, egg);
            return;
        }
        if (air && hand != null && isToy(held)) {
            throwToy(player, hand);
        }
    }

    public boolean handledWorld(Player player, ItemStack hand, Block clicked, BlockFace face, boolean sneaking, boolean air) {
        Material held = hand == null ? Material.AIR : hand.getType();
        if (clicked != null && isKennel(clicked) && !sneaking) {
            return true;
        }
        if (sneaking && held == runtime.config().kennel() && clicked != null && face != null) {
            return true;
        }
        if (runtime.config().byEgg(hand) != null) {
            return true;
        }
        return air && isToy(held);
    }

    public void clickMenu(Player player, MenuHolder holder, int slot, ItemStack current, boolean rightClick, boolean shift, boolean lettingGo) {
        if ((holder.kind() == MenuHolder.Kind.TRICK || holder.kind() == MenuHolder.Kind.LEARNED) && (slot == 18 || slot == 26)) {
            Pet pet = runtime.store().get(holder.petId());
            if (pet != null && pet.ownerId().equals(player.getUniqueId())) {
                int page = holder.page() + (slot == 18 ? -1 : 1);
                if (holder.kind() == MenuHolder.Kind.TRICK) menus.openTricks(player, pet, holder.word(), page);
                else menus.openLearned(player, pet, page);
            }
            return;
        }
        if (holder.kind() == MenuHolder.Kind.CARE) {
            clickCare(player, holder, slot);
            return;
        }
        if (holder.kind() == MenuHolder.Kind.LEARNED) {
            if (slot == PetMenus.TRICKS_BACK_SLOT) {
                Pet pet = runtime.store().get(holder.petId());
                if (pet != null && pet.ownerId().equals(player.getUniqueId())) {
                    menus.openCare(player, pet);
                }
            }
            return;
        }
        if (holder.kind() == MenuHolder.Kind.TRICK) {
            clickTrick(player, holder, slot);
            return;
        }
        if (current == null || slot >= 27) {
            return;
        }
        String raw = current.getItemMeta() == null
                ? null
                : current.getItemMeta().getPersistentDataContainer().get(runtime.petKey(), PersistentDataType.STRING);
        if (raw == null) {
            return;
        }
        UUID petId;
        try {
            petId = UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return;
        }
        Pet pet = runtime.store().get(petId);
        if (pet == null || !pet.ownerId().equals(player.getUniqueId())) {
            return;
        }
        menus.openCare(player, pet);
    }

    private void clickCare(Player player, MenuHolder holder, int slot) {
        Pet pet = runtime.store().get(holder.petId());
        if (pet == null || !pet.ownerId().equals(player.getUniqueId())) {
            return;
        }
        if (slot == PetMenus.NAME_SLOT) {
            beginRename(player, pet);
            player.closeInventory();
            return;
        }
        if (slot == PetMenus.BACK_SLOT) {
            menus.openKennel(player);
            return;
        }
        if (slot == PetMenus.TRICKS_SLOT) {
            menus.openLearned(player, pet);
            return;
        }
        if (slot == PetMenus.RELEASE_SLOT) {
            beginRelease(player, pet);
            return;
        }
        if (slot == PetMenus.CALL_SLOT) {
            if (pet.stored()) {
                takeOut(player, pet);
            } else {
                call(player, pet);
            }
            menus.openCare(player, pet);
            return;
        }
        if (slot == PetMenus.STORE_SLOT && !pet.stored()) {
            storePet(player, pet);
            player.closeInventory();
        }
    }

    public void onChat(Player player, String text) {
        long now = System.currentTimeMillis();
        if (handleReleaseChat(player, text, now) || handleHatchChat(player, text, now) || handleRenameChat(player, text, now)) {
            return;
        }
        Pet looked = runtime.byEntity(lookingAt(player, 6.0));
        TrainingSession session = runtime.sessions().training(player.getUniqueId());
        if (looked != null && looked.ownerId().equals(player.getUniqueId()) && !looked.stored()
                && (looked.trickFor(SpokenOrder.key(text)) != null
                        || session != null && session.petId().equals(looked.id()))) {
            handleTrainingChat(player, text, now);
            return;
        }
        if (respondToName(player, text, now)) return;
        handleTrainingChat(player, text, now);
    }

    private boolean respondToName(Player player, String text, long now) {
        boolean answered = false;
        for (Pet pet : runtime.store().all()) {
            if (pet.stored() || pet.dead() || !pet.ownerId().equals(player.getUniqueId())
                    || !SpokenOrder.matches(text, pet.name())) continue;
            Entity entity = runtime.entity(pet);
            if (!(entity instanceof Mob mob) || !mob.getWorld().equals(player.getWorld())) continue;
            clearInteractions(pet);
            releaseFetch(pet, player, true);
            pet.order(PetOrder.FOLLOW);
            pet.staying(false);
            pet.forcedSitUntilMillis(0L);
            wakeToFollow(pet, now);
            PetFx.sit(mob, false);
            PetFx.lie(mob, false);
            roaming.attend(pet, player, now);
            PetFx.bar(player, pet.name() + " heard its name and comes to you");
            answered = true;
        }
        return answered;
    }

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

    public void reattach(Entity entity) {
        UUID id = runtime.bodies().readId(entity);
        if (id == null) {
            return;
        }
        Pet pet = runtime.store().get(id);
        if (pet == null) {
            return;
        }
        if (pet.stored()) {
            runtime.visual().remove(entity);
            entity.remove();
            return;
        }
        if (pet.entityId() != null && !pet.entityId().equals(entity.getUniqueId())) {
            runtime.visual().remove(entity);
            entity.remove();
            return;
        }
        pet.entityId(entity.getUniqueId());
        runtime.remember(pet, entity);
        PetTypeDef type = runtime.config().type(pet.typeId());
        runtime.bodies().reattach(entity, pet, type);
    }

    public void stashLooseToys() {
        for (Pet pet : runtime.store().all()) {
            FetchJob job = pet.fetch();
            if (job == null) {
                continue;
            }
            remove(job.projectileId());
            remove(job.itemId());
            pet.carriedToy(job.toy());
            pet.fetch(null);
            pet.activity(Activity.NONE);
        }
    }

    private void clickTrick(Player player, MenuHolder holder, int slot) {
        Trick selected = holder.trick(slot);
        if (selected == null || holder.word() == null || holder.petId() == null) {
            return;
        }
        Pet pet = runtime.store().get(holder.petId());
        if (pet == null || !pet.ownerId().equals(player.getUniqueId())) {
            return;
        }
        bindTrick(player, pet, holder.word(), selected);
    }

    private void beginHatch(Player player, PetTypeDef type) {
        if (!Quota.canBringOut(runtime.store().countOut(player.getUniqueId()), runtime.config().limits().maxOut())) {
            PetFx.bar(player, PetTexts.refusal("", PetSex.FEMALE, "full-out"));
            return;
        }
        HatchPrompt prompt = new HatchPrompt(type.id(), System.currentTimeMillis() + PROMPT_MILLIS);
        runtime.sessions().hatch(player.getUniqueId(), prompt);
        String species = PetTexts.speciesName(type.id()).toLowerCase(Locale.ROOT);
        if (type.sexMode() == SexMode.CHOOSE) {
            PetFx.tell(player, "Will your new " + species + " be a boy or a girl? Type \"male\" or \"female\", or \"cancel\" to keep the egg.");
            return;
        }
        prompt.sex(runtime.random().nextBoolean() ? PetSex.MALE : PetSex.FEMALE);
        PetFx.tell(player, "Your new " + species + " is a " + (prompt.sex() == PetSex.FEMALE ? "girl" : "boy") + "! What will you call "
                + PetTexts.him(prompt.sex()) + "? Type a name in chat, or \"cancel\" to keep the egg.");
    }

    private boolean handleHatchChat(Player player, String text, long now) {
        HatchPrompt prompt = runtime.sessions().hatch(player.getUniqueId());
        if (prompt == null) {
            return false;
        }
        if (now > prompt.expiresAt()) {
            runtime.sessions().clearHatch(player.getUniqueId());
            PetFx.tell(player, "You took too long to answer. Your egg is safe.");
            return true;
        }
        if (Names.cancels(text)) {
            runtime.sessions().clearHatch(player.getUniqueId());
            PetFx.tell(player, "Hatching cancelled. Your egg is safe.");
            return true;
        }
        PetTypeDef type = runtime.config().type(prompt.typeId());
        if (type == null) {
            runtime.sessions().clearHatch(player.getUniqueId());
            return true;
        }
        if (prompt.sex() == null) {
            PetSex sex = Names.sex(text);
            if (sex == null) {
                PetFx.tell(player, "Type \"male\" or \"female\", or \"cancel\" to keep the egg.");
                return true;
            }
            prompt.sex(sex);
            PetFx.tell(player, "A " + (sex == PetSex.FEMALE ? "girl" : "boy") + "! What will you call "
                    + PetTexts.him(sex) + "? Type a name in chat.");
            return true;
        }
        if (prompt.name() == null) {
            String name = Names.sanitize(text);
            if (name.isBlank()) {
                PetFx.tell(player, "That name is empty. Try another one.");
                return true;
            }
            prompt.name(name);
            prompt.confirming(true);
            PetFx.tell(player, "Name " + PetTexts.him(prompt.sex()) + " " + name + "? Type \"yes\" to confirm or \"no\" to cancel.");
            return true;
        }
        if (prompt.confirming()) {
            if (!Names.confirms(text)) {
                PetFx.tell(player, "Type \"yes\" to confirm or \"no\" to cancel.");
                return true;
            }
            finishHatch(player, type, prompt.name(), prompt.sex());
        }
        return true;
    }

    private void finishHatch(Player player, PetTypeDef type, String name, PetSex sex) {
        runtime.sessions().clearHatch(player.getUniqueId());
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!type.matchesEgg(hand)) {
            PetFx.tell(player, "The egg left your hand, so nothing hatched. Use it again to start over.");
            return;
        }
        if (!Quota.canBringOut(runtime.store().countOut(player.getUniqueId()), runtime.config().limits().maxOut())) {
            PetFx.tell(player, PetTexts.refusal(name, sex, "full-out"));
            return;
        }
        if (!consumeHand(player, hand)) {
            return;
        }
        Pet pet = new Pet(UUID.randomUUID(), player.getUniqueId(), type.id(), name, sex);
        pet.bornAt(System.currentTimeMillis());
        FavoriteToy.Result favorite = FavoriteToy.reconcile(null, toyNames(type), runtime.random());
        pet.favoriteToy(favorite.toy());
        runtime.store().add(pet);
        PetFx.tell(player, name + " has hatched! Welcome to the family.");
        Entity entity = runtime.bodies().spawn(pet, type, PetRuntime.beside(player), player);
        if (entity == null) {
            pet.stored(true);
            PetFx.tell(player, "There was no room out here, so " + name + " is waiting for you in the shelter.");
        } else {
            pet.stored(false);
            runtime.remember(pet, entity);
        }
        runtime.store().save();
    }

    private void beginRename(Player player, Pet pet) {
        runtime.sessions().rename(player.getUniqueId(), new RenamePrompt(pet.id(), System.currentTimeMillis() + PROMPT_MILLIS));
        PetFx.tell(player, "Type a new name for " + pet.name() + " in chat, or \"cancel\" to keep it.");
    }

    private boolean handleRenameChat(Player player, String text, long now) {
        RenamePrompt prompt = runtime.sessions().rename(player.getUniqueId());
        if (prompt == null) {
            return false;
        }
        Pet pet = runtime.store().get(prompt.petId());
        if (pet == null || now > prompt.expiresAt() || !pet.ownerId().equals(player.getUniqueId())) {
            runtime.sessions().clearRename(player.getUniqueId());
            return true;
        }
        if (Names.cancels(text)) {
            runtime.sessions().clearRename(player.getUniqueId());
            PetFx.tell(player, pet.name() + " keeps " + PetTexts.his(pet.sex()) + " name.");
            return true;
        }
        if (prompt.name() == null) {
            String name = Names.sanitize(text);
            if (name.isBlank()) {
                PetFx.tell(player, "That name is empty. Try another one.");
                return true;
            }
            prompt.name(name);
            prompt.confirming(true);
            PetFx.tell(player, "Rename " + pet.name() + " to " + name + "? Type \"yes\" to confirm or \"no\" to cancel.");
            return true;
        }
        if (!prompt.confirming()) {
            return true;
        }
        if (!Names.confirms(text)) {
            PetFx.tell(player, "Type \"yes\" to confirm or \"no\" to cancel.");
            return true;
        }
        pet.name(prompt.name());
        Entity entity = runtime.entity(pet);
        if (entity != null) {
            runtime.bodies().name(entity, pet.name());
        }
        runtime.sessions().clearRename(player.getUniqueId());
        PetFx.tell(player, "From now on, your pet answers to " + pet.name() + ".");
        return true;
    }

    private void handleTrainingChat(Player player, String text, long now) {
        Entity looked = lookingAt(player, 6.0);
        Pet pet = runtime.byEntity(looked);
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
                perform(player, pet, looked, known, false);
                PetFx.bar(player, pet.name() + " already knows " + PetTexts.trickName(known) + ". No practice needed");
                return;
            }
            if (pet.activity() == Activity.SLEEPING && known != Trick.SLEEP && known != Trick.COME) {
                perform(player, pet, looked, known, false);
                return;
            }
            if (!spendTrainingEffort(player, pet)) {
                return;
            }
            int attempts = session.addAttempt();
            session.lastWord(line);
            TrainingMath.Attempt result = TrainingMath.attempt(pet.progress(known), runtime.random(), training);
            perform(player, pet, looked, known, result != TrainingMath.Attempt.SUCCESS);
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
            perform(player, pet, looked, known, false);
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
        return type == null || type.favoriteFood() == null ? "a treat" : PetTexts.itemName(type.favoriteFood().name());
    }

    private void beginTraining(Player player, Pet pet, Entity entity) {
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

    private void reward(Player player, Pet pet, TrainingSession session, ItemStack hand) {
        Trick trick = session.rewardTrick();
        if (!checkTrick(player, pet, trick)) {
            session.clearReward();
            return;
        }
        if (trick == null || !consumeHand(player, hand)) {
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

    private String focusReason(Pet pet) {
        if (sick(pet)) {
            return PetTexts.he(pet.sex()) + " is too unwell to focus";
        }
        if (pet.need(Need.ENERGY) < 25.0) {
            return PetTexts.he(pet.sex()) + " is too tired to focus. Let " + PetTexts.him(pet.sex()) + " rest";
        }
        return PetTexts.he(pet.sex()) + " is too hungry to focus. Feed " + PetTexts.him(pet.sex()) + " first";
    }

    private void hologram(Pet pet, String text, NamedTextColor color, long ticks) {
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

    private boolean allowsTrick(Pet pet, Trick trick) {
        return net.tfminecraft.companionpets.training.TrickAvailability.allows(runtime, pet, trick);
    }

    private boolean checkTrick(Player player, Pet pet, Trick trick) {
        if (allowsTrick(pet, trick)) return true;
        PetFx.bar(player, "That trick is not available for " + pet.name());
        return false;
    }

    private void perform(Player player, Pet pet, Entity entity, Trick trick, boolean partial) {
        if (!checkTrick(player, pet, trick)) return;
        long now = System.currentTimeMillis();
        if (pet.activity() == Activity.SLEEPING && trick != Trick.SLEEP && trick != Trick.COME) {
            PetFx.bar(player, pet.name() + " is resting. Tell " + PetTexts.him(pet.sex()) + " to follow");
            return;
        }
        if (partial && trick.kind() == Trick.Kind.CUSTOM) return;
        if (trick != Trick.SPIN) cancelSpin(pet);
        runtime.visual().cancelAction(entity);
        if (trick.kind() == Trick.Kind.CUSTOM) {
            var definition = runtime.config().customTrick(trick);
            if (definition == null) return;
            boolean played = runtime.visual().playClip(entity, runtime.config().type(pet.typeId()), definition.animation(), definition.duration());
            if (!played && !definition.fallbackText().isBlank())
                hologram(pet, definition.fallbackText().replace("{pet}", pet.name()).replace("{owner}", player.getName()), NamedTextColor.WHITE,
                        Math.round(definition.duration() * 20));
            PetFx.bar(player, pet.name() + " performs " + definition.displayName());
            return;
        }
        switch (trick.kind()) {
            case SIT -> {
                if (partial) {
                    pet.forcedSitUntilMillis(now + 800L);
                } else {
                    pet.order(PetOrder.SIT);
                    pet.staying(false);
                }
            }
            case COME -> {
                releaseFetch(pet, player, true);
                pet.order(PetOrder.FOLLOW);
                pet.staying(false);
                pet.forcedSitUntilMillis(0L);
                wakeToFollow(pet, now);
                if (entity != null) {
                    PetFx.sit(entity, false);
                    PetFx.lie(entity, false);
                }
            }
            case STAY -> {
                pet.staying(true);
                pet.order(PetOrder.FOLLOW);
            }
            case SPEAK -> {
                if (entity != null) {
                    PetFx.ambient(entity);
                }
            }
            case JUMP -> {
                if (entity != null) {
                    standForAction(pet, entity);
                    PetFx.jump(entity, partial);
                }
            }
            case SPIN -> {
                if (entity instanceof Mob mob) {
                    standForAction(pet, mob);
                    spin(pet, mob, now);
                }
            }
            case SLEEP -> {
                releaseFetch(pet, player, true);
                pet.activity(Activity.SLEEPING);
                pet.staying(false);
                markSleep(entity, true);
            }
            case PAW -> {
                if (entity != null) {
                    PetFx.look(entity, player.getEyeLocation());
                    PetFx.particle(entity, Particle.HEART, 2);
                }
            }
            case BEG -> {
                pet.forcedSitUntilMillis(now + (partial ? 800L : 2_000L));
                if (entity != null) {
                    PetFx.beg(entity);
                    Bukkit.getScheduler().runTaskLater(runtime.plugin(), () -> PetFx.stopBeg(entity), partial ? 16L : 40L);
                }
            }
            default -> {
            }
        }
        if (trick == Trick.COME) {
            Locomotion.Mode actual = Locomotion.choose(pet.illness(), pet.need(Need.HEALTH),
                    pet.need(Need.ENERGY), pet.need(Need.HUNGER), pet.activity(), pet.fetch() != null,
                    false, pet.order(), pet.staying());
            if (actual != Locomotion.Mode.FOLLOW) {
                PetFx.bar(player, pet.name() + " heard you but needs food, rest, or care before following.");
                return;
            }
        }
        PetFx.bar(player, PetTexts.reaction(pet.name(), pet.sex(), trick));
        PetTypeDef type = runtime.config().type(pet.typeId());
        if (entity != null && type != null) {
            if (runtime.visual().play(entity, type, trick.name()) && trick == Trick.SPIN) {
                cancelSpin(pet);
            }
        }
    }

    private void standForAction(Pet pet, Entity entity) {
        pet.order(PetOrder.FOLLOW);
        pet.staying(false);
        pet.forcedSitUntilMillis(0L);
        PetFx.sit(entity, false);
        PetFx.lie(entity, false);
    }

    private void spin(Pet pet, Mob body, long now) {
        cancelSpin(pet);
        social.cancel(pet);
        moments.cancel(pet);
        pet.activity(Activity.TRICK);
        spins.put(pet.id(), new SpinJob(body.getLocation().clone(), Math.toRadians(body.getLocation().getYaw()), now));
    }

    boolean advanceSpin(Pet pet, Mob body, Locomotion.Mode mode, long now) {
        SpinJob job = spins.get(pet.id());
        if (job == null) return false;
        if (!body.isValid() || mode != Locomotion.Mode.FOLLOW || !body.getWorld().equals(job.center.getWorld())
                || pet.order() != PetOrder.FOLLOW || pet.staying() || pet.activity() != Activity.TRICK) {
            cancelSpin(pet);
            return false;
        }
        if (now >= job.nextStepAt) {
            if (job.step >= 12) {
                cancelSpin(pet);
                body.getPathfinder().stopPathfinding();
                return false;
            }
            double angle = job.heading + 2.0 * Math.PI * ++job.step / 12.0;
            Location target = job.center.clone().add(Math.cos(angle) * 1.2, 0, Math.sin(angle) * 1.2);
            body.getPathfinder().moveTo(target, 1.25);
            job.nextStepAt = now + 500L;
        }
        return true;
    }

    private void cancelSpin(Pet pet) {
        spins.remove(pet.id());
        if (pet.activity() == Activity.TRICK) pet.activity(Activity.NONE);
    }

    private static final class SpinJob {
        private final Location center;
        private final double heading;
        private long nextStepAt;
        private int step;

        private SpinJob(Location center, double heading, long now) {
            this.center = center;
            this.heading = heading;
            this.nextStepAt = now;
        }
    }

    private void wakeToFollow(Pet pet, long now) {
        if (pet.activity() == Activity.SLEEPING) {
            long wait = Math.round(runtime.config().care().restAgainSeconds() * 1000.0);
            pet.refuseRestUntilMillis(now + wait);
        }
        pet.activity(Activity.NONE);
    }

    private boolean feed(Player player, Pet pet, Entity entity, ItemStack hand, double gain, boolean favorite) {
        if (!consumeHand(player, hand)) {
            return true;
        }
        if (Feeding.outcome(pet.need(Need.HUNGER)) == Feeding.Outcome.OVERATE) {
            pet.need(Need.MOOD, pet.need(Need.MOOD) - runtime.config().care().overfeedMoodPenalty());
            pet.need(Need.HEALTH, pet.need(Need.HEALTH) - runtime.config().care().overfeedHealthPenalty());
            if (entity != null) {
                PetFx.eat(entity);
                runtime.visual().play(entity, runtime.config().type(pet.typeId()), "EAT");
                PetFx.sad(entity);
                PetFx.particle(entity, Particle.SMOKE, 4);
            }
            PetFx.bar(player, PetTexts.overfed(pet.name(), pet.sex()));
            return true;
        }
        pet.need(Need.HUNGER, pet.need(Need.HUNGER) + gain);
        if (favorite) {
            pet.need(Need.MOOD, pet.need(Need.MOOD) + runtime.config().care().favoriteFoodMood());
        }
        comfort(pet, System.currentTimeMillis());
        if (entity != null) {
            PetFx.eat(entity);
            runtime.visual().play(entity, runtime.config().type(pet.typeId()), "EAT");
            PetFx.hearts(entity, favorite ? 4 : 2);
        }
        return false;
    }

    public void lostBody(Pet pet) {
        clearInteractions(pet);
        Entity body = pet.entityId() == null ? null : Bukkit.getEntity(pet.entityId());
        if (body != null) {
            markSleep(body, false);
        }
        runtime.store().remove(pet.id());
        runtime.store().save();
        Player owner = Bukkit.getPlayer(pet.ownerId());
        if (owner != null && owner.isOnline()) {
            PetFx.tell(owner, pet.name() + " has died");
        }
    }

    public void struck(Pet pet, Entity body) {
        pet.need(Need.MOOD, pet.need(Need.MOOD) - runtime.config().care().struckMoodPenalty());
        if (body != null) {
            PetFx.particle(body, Particle.CRIT, 6);
        }
        Player owner = Bukkit.getPlayer(pet.ownerId());
        if (owner != null && owner.isOnline()) {
            PetFx.bar(owner, PetTexts.struck(pet.name()));
        }
    }

    public void markSleep(Entity body, boolean asleep) {
        holograms.sleep(body, asleep);
    }

    private void throwToy(Player player, ItemStack hand) {
        Material material = hand.getType();
        Pet pet = nearestToyPet(player, material);
        if (pet == null) {
            PetFx.bar(player, "None of your pets nearby wants to play with that");
            return;
        }
        if (sick(pet) || pet.illness() == Illness.WEAKENED || pet.need(Need.ENERGY) < 25.0) {
            PetFx.bar(player, PetTexts.refusal(pet.name(), pet.sex(), sick(pet) || pet.illness() == Illness.WEAKENED ? "sick" : "tired"));
            return;
        }
        releaseFetch(pet, player, true);
        if (!consumeHand(player, hand)) {
            return;
        }
        PetTypeDef type = runtime.config().type(pet.typeId());
        boolean favorite = type != null && material.name().equals(pet.favoriteToy());
        FetchJob job = new FetchJob(material.name(), favorite);
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        double speed = ThrowSpeed.speed(
                player.getLocation().getPitch(),
                player.isSneaking(),
                runtime.config().play().throwSpeedLow(),
                runtime.config().play().throwSpeedHigh());
        World world = player.getWorld();
        Snowball ball;
        try {
            ball = world.spawn(eye, Snowball.class, snowball -> {
                snowball.setShooter(player);
                snowball.setItem(new ItemStack(material));
                snowball.setVelocity(direction.multiply(speed));
                snowball.getPersistentDataContainer().set(
                        runtime.toyKey(),
                        PersistentDataType.STRING,
                        pet.id() + "|" + material.name());
            });
        } catch (RuntimeException ex) {
            if (player.getGameMode() != GameMode.CREATIVE) {
                player.getInventory().addItem(new ItemStack(material));
            }
            return;
        }
        job.projectileId(ball.getUniqueId());
        pet.fetch(job);
        pet.activity(Activity.PLAYING);
        pet.playUntilMillis(0L);
    }

    public void toyLanded(Pet pet, UUID itemId) {
        FetchJob job = pet.fetch();
        if (job == null) {
            return;
        }
        job.itemId(itemId);
        job.phase(net.tfminecraft.companionpets.play.FetchPhase.GROUND);
        job.projectileId(null);
    }

    public void releaseFetch(Pet pet, Player owner, boolean toOwner) {
        FetchJob job = pet.fetch();
        if (job == null) {
            return;
        }
        remove(job.projectileId());
        remove(job.itemId());
        pet.fetch(null);
        if (pet.activity() == Activity.PLAYING) {
            pet.activity(Activity.NONE);
        }
        if (toOwner && owner != null && owner.isOnline()) {
            dropPlain(PetRuntime.inFront(owner), job.toy());
        } else {
            pet.carriedToy(job.toy());
        }
    }

    public void dropPlain(Location location, String toy) {
        if (location.getWorld() == null || toy == null) {
            return;
        }
        Material material = Material.matchMaterial(toy);
        if (material == null) {
            return;
        }
        location.getWorld().dropItem(location, new ItemStack(material)).setPickupDelay(0);
    }

    public void dropToy(Location location, Pet pet, String toy) {
        if (location.getWorld() == null) {
            return;
        }
        Material material = Material.matchMaterial(toy);
        if (material == null) {
            return;
        }
        org.bukkit.entity.Item item = location.getWorld().dropItem(location, new ItemStack(material));
        item.setPickupDelay(Integer.MAX_VALUE);
        item.getPersistentDataContainer().set(runtime.toyKey(), PersistentDataType.STRING, pet.id().toString());
        FetchJob job = pet.fetch();
        if (job != null) {
            job.itemId(item.getUniqueId());
        }
    }

    private Pet nearestToyPet(Player player, Material material) {
        Pet best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Pet pet : runtime.store().of(player.getUniqueId())) {
            if (pet.stored() || pet.dead()) {
                continue;
            }
            PetTypeDef type = runtime.config().type(pet.typeId());
            if (type == null || !type.acceptsToy(material)) {
                continue;
            }
            Entity entity = runtime.entity(pet);
            if (entity == null || !entity.getWorld().equals(player.getWorld())) {
                continue;
            }
            double distance = entity.getLocation().distance(player.getLocation());
            if (distance > runtime.config().ownerNearRadius()) {
                continue;
            }
            if (distance < bestDistance) {
                best = pet;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void takeOut(Player player, Pet pet) {
        if (!pet.stored()) {
            return;
        }
        if (!Quota.canBringOut(runtime.store().countOut(player.getUniqueId()), runtime.config().limits().maxOut())) {
            PetFx.bar(player, PetTexts.refusal(pet.name(), pet.sex(), "full-out"));
            return;
        }
        PetTypeDef type = runtime.config().type(pet.typeId());
        Entity entity = runtime.bodies().spawn(pet, type, PetRuntime.beside(player), player);
        if (entity == null) {
            PetFx.bar(player, "There's no room here for " + pet.name() + " to come out");
            return;
        }
        pet.stored(false);
        runtime.remember(pet, entity);
    }

    private void releasePet(Player player, Pet pet) {
        clearInteractions(pet);
        releaseFetch(pet, player, false);
        if (pet.carriedToy() != null) {
            dropPlain(pet.entityId() == null ? player.getLocation() : PetRuntime.beside(player), pet.carriedToy());
            pet.carriedToy(null);
        }
        Entity entity = runtime.entity(pet);
        if (entity == null && pet.entityId() != null) {
            entity = Bukkit.getEntity(pet.entityId());
        }
        if (entity != null) {
            markSleep(entity, false);
            runtime.visual().remove(entity);
            entity.remove();
        }
        runtime.store().remove(pet.id());
        runtime.store().save();
        PetFx.tell(player, pet.name() + " is gone. " + PetTexts.He(pet.sex()) + " is no longer with you");
    }

    private void beginRelease(Player player, Pet pet) {
        runtime.sessions().release(player.getUniqueId(), new ReleasePrompt(pet.id(), System.currentTimeMillis() + PROMPT_MILLIS));
        player.closeInventory();
        PetFx.tell(player, "Release " + pet.name() + " forever? Type \"yes\" to confirm, or \"no\" to cancel.");
    }

    private boolean handleReleaseChat(Player player, String text, long now) {
        UUID playerId = player.getUniqueId();
        ReleasePrompt prompt = runtime.sessions().release(playerId);
        if (prompt == null) {
            return false;
        }
        Pet pet = runtime.store().get(prompt.petId());
        if (pet == null || !pet.ownerId().equals(playerId)) {
            runtime.sessions().clearRelease(playerId);
            PetFx.tell(player, "That pet is no longer available to release.");
            return true;
        }
        if (now > prompt.expiresAt()) {
            runtime.sessions().clearRelease(playerId);
            PetFx.tell(player, pet.name() + " is safe. The release confirmation expired.");
            return true;
        }
        if (Names.confirms(text)) {
            runtime.sessions().clearRelease(playerId);
            releasePet(player, pet);
        } else if (Names.cancels(text)) {
            runtime.sessions().clearRelease(playerId);
            PetFx.tell(player, pet.name() + " stays with you.");
        } else {
            PetFx.tell(player, "Type \"yes\" to release " + pet.name() + " forever, or \"no\" to cancel.");
        }
        return true;
    }

    public boolean spawnTestDog(Player player, String name) {
        PetTypeDef type = runtime.config().type("wolf");
        if (type == null) {
            PetFx.tell(player, "The wolf pet type is not configured.");
            return false;
        }
        if (!Quota.canBringOut(runtime.store().countOut(player.getUniqueId()), runtime.config().limits().maxOut())) {
            PetFx.tell(player, "You have reached the active pet limit. Send one to the shelter first.");
            return false;
        }
        String safeName = Names.sanitize(name);
        if (safeName.isBlank()) {
            safeName = "Test Dog";
        }
        Pet pet = new Pet(UUID.randomUUID(), player.getUniqueId(), type.id(), safeName, PetSex.MALE);
        pet.bornAt(System.currentTimeMillis());
        pet.favoriteToy(FavoriteToy.reconcile(null, toyNames(type), runtime.random()).toy());
        for (Trick trick : runtime.config().tricks()) {
            if (!type.allowsTrick(trick)) continue;
            String word = trick.name().toLowerCase(Locale.ROOT);
            pet.bindWord(word, trick);
            pet.progress(trick, 100.0);
        }
        if (type.allowsTrick(Trick.COME)) pet.bindWord("follow", Trick.COME);
        Entity entity = runtime.bodies().spawn(pet, type, PetRuntime.beside(player), player);
        if (entity == null) {
            PetFx.tell(player, "There is no room beside you to spawn the test dog.");
            return false;
        }
        pet.stored(false);
        runtime.store().add(pet);
        runtime.remember(pet, entity);
        runtime.store().save();
        PetFx.tell(player, safeName + " spawned with every trick learned. Look at the dog and say a trick word to see it perform.");
        return true;
    }

    private void storePet(Player player, Pet pet) {
        if (pet.stored()) {
            return;
        }
        if (!Quota.canStore(runtime.store().countStored(player.getUniqueId()), runtime.config().limits().maxStored())) {
            PetFx.bar(player, PetTexts.refusal(pet.name(), pet.sex(), "full-stored"));
            return;
        }
        clearInteractions(pet);
        releaseFetch(pet, player, true);
        if (pet.carriedToy() != null) {
            dropPlain(PetRuntime.inFront(player), pet.carriedToy());
            pet.carriedToy(null);
        }
        Entity entity = runtime.entity(pet);
        if (entity != null) {
            runtime.visual().remove(entity);
            entity.remove();
        }
        pet.entityId(null);
        pet.stored(true);
        pet.clearRuntimeMotion();
    }

    private void call(Player player, Pet pet) {
        if (pet.stored()) {
            takeOut(player, pet);
            return;
        }
        World world = Bukkit.getWorld(pet.worldName());
        if (world != null) {
            int chunkX = ((int) Math.floor(pet.x())) >> 4;
            int chunkZ = ((int) Math.floor(pet.z())) >> 4;
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            chunk.load(true);
            chunk.getEntities();
        }
        Entity entity = runtime.entity(pet);
        if (entity == null && pet.entityId() != null) {
            entity = Bukkit.getEntity(pet.entityId());
        }
        if (entity == null || !entity.isValid() || entity.isDead()) {
            PetFx.bar(player, pet.name() + " can't be found. Send " + PetTexts.him(pet.sex()) + " to the shelter to bring "
                    + PetTexts.him(pet.sex()) + " back");
            return;
        }
        wakeToFollow(pet, System.currentTimeMillis());
        entity.teleport(PetRuntime.beside(player));
        pet.order(PetOrder.FOLLOW);
        pet.staying(false);
        runtime.remember(pet, entity);
        PetFx.bar(player, pet.name() + " comes running to your side");
    }

    private void placeKennel(Player player, ItemStack hand, Block clicked, BlockFace face) {
        Block place = clicked.getRelative(face);
        if (!place.getType().isAir() && !place.isReplaceable()) {
            PetFx.bar(player, "There isn't enough room for a shelter there");
            return;
        }
        if (!consumeHand(player, hand)) {
            return;
        }
        place.setType(runtime.config().kennel());
        runtime.store().kennel(PetStore.kennelKey(place.getWorld().getName(), place.getX(), place.getY(), place.getZ()), player.getUniqueId());
        PetFx.bar(player, "Shelter placed. Right-click it to look after your pets");
    }

    private boolean isKennel(Block block) {
        if (block.getType() != runtime.config().kennel()) {
            return false;
        }
        return runtime.store().kennelOwner(PetStore.kennelKey(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ())) != null;
    }

    private boolean isToy(Material material) {
        for (PetTypeDef type : runtime.config().types().values()) {
            if (type.acceptsToy(material)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sick(Pet pet) {
        return pet.illness() == Illness.SICK || pet.illness() == Illness.WEAKENED;
    }

    private void cheer(Pet pet, long now, double gain) {
        Long last = pettedAt.get(pet.id());
        if (last != null && now - last < PET_COOLDOWN_MILLIS) {
            return;
        }
        pettedAt.put(pet.id(), now);
        pet.need(Need.MOOD, pet.need(Need.MOOD) + gain);
    }

    private void comfort(Pet pet, long now) {
        pet.nextCryAtMillis(now + Math.round(runtime.config().cryIntervalSeconds() * 1000.0));
    }

    private static boolean consumeHand(Player player, ItemStack hand) {
        if (hand == null || hand.getType().isAir() || hand.getAmount() <= 0) {
            return false;
        }
        if (player.getGameMode() == GameMode.CREATIVE) {
            return true;
        }
        hand.setAmount(hand.getAmount() - 1);
        return true;
    }

    private void remove(UUID entityId) {
        if (entityId == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(entityId);
        if (entity != null) {
            runtime.visual().remove(entity);
            entity.remove();
        }
    }

    public static Entity lookingAt(Player player, double range) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        RayTraceResult block = player.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
        double limit = range;
        if (block != null) {
            limit = Math.min(range, eye.toVector().distance(block.getHitPosition()));
        }
        if (limit <= 0.0) {
            return null;
        }
        RayTraceResult result = player.getWorld().rayTraceEntities(eye, direction, limit, entity -> entity != player);
        if (result == null) {
            return null;
        }
        return result.getHitEntity();
    }

    public static java.util.List<String> toyNames(PetTypeDef type) {
        java.util.List<String> names = new java.util.ArrayList<>();
        if (type == null) {
            return names;
        }
        for (Material toy : type.toys()) {
            names.add(toy.name());
        }
        return names;
    }

    public void clearPendingWord(Player player, String word) {
        TrainingSession session = runtime.sessions().training(player.getUniqueId());
        if (session != null && word != null && word.equals(session.pendingWord())) {
            Pet pet = runtime.store().get(session.petId());
            if (pet == null || !pet.knowsWord(word)) {
                session.pendingWord(null);
                PetFx.bar(player, "“" + word + "” wasn't linked to a trick. Say it again to choose one");
            }
        }
    }
}
