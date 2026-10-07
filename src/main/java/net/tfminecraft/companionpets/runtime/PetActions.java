package net.tfminecraft.companionpets.runtime;

import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import net.tfminecraft.companionpets.item.ItemRef;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.format.NamedTextColor;

import net.tfminecraft.companionpets.behavior.Locomotion;
import net.tfminecraft.companionpets.care.DominantNeed;
import net.tfminecraft.companionpets.chat.SpokenOrder;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.fx.PetHolograms;
import net.tfminecraft.companionpets.gui.MenuHolder;
import net.tfminecraft.companionpets.gui.PetMenus;
import net.tfminecraft.companionpets.management.Quota;
import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.NeedBand;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetOrder;
import net.tfminecraft.companionpets.pet.PetPersonality;
import net.tfminecraft.companionpets.pet.PetSex;
import net.tfminecraft.companionpets.pet.Trick;
import net.tfminecraft.companionpets.play.FavoriteToy;
import net.tfminecraft.companionpets.session.PlayerHints;
import net.tfminecraft.companionpets.session.RenamePrompt;
import net.tfminecraft.companionpets.session.ReleasePrompt;
import net.tfminecraft.companionpets.session.TrainingSession;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.text.Names;
import net.tfminecraft.companionpets.text.PetTexts;
import net.tfminecraft.companionpets.training.TrainingMath;

public final class PetActions {
    private static final long PROMPT_MILLIS = 60_000L;

    private static final long PET_COOLDOWN_MILLIS = 30_000L;
    private static final double PET_MOOD_GAIN = 4.0;

    private final PetRuntime runtime;
    private final PetHatching hatching;
    private final PetCareActions careActions;
    private final PetFetchActions fetchActions;
    private final PetTraining training;
    private final PetMenus menus;
    private final PetHolograms holograms;
    private final PetMoments moments;
    private final PetGreetings greetings;
    private final PetToyAnticipation anticipation;
    private final PetSocial social;
    private final PetRoaming roaming;
    private final PlayerHints hints;
    private final java.util.Map<UUID, Long> pettedAt = new java.util.HashMap<>();
    private final java.util.Map<UUID, CalmProgress> calming = new java.util.HashMap<>();

    public PetActions(PetRuntime runtime) {
        this.runtime = runtime;
        this.menus = new PetMenus(runtime);
        this.holograms = new PetHolograms(runtime.plugin());
        this.moments = new PetMoments(runtime);
        this.roaming = new PetRoaming(runtime, this::markSleep);
        this.social = new PetSocial(runtime, roaming);
        this.greetings = new PetGreetings(runtime, this);
        this.anticipation = new PetToyAnticipation(runtime, this);
        this.hints = new PlayerHints(runtime.plugin());
        hatching = new PetHatching(runtime);
        careActions = new PetCareActions(runtime);
        fetchActions = new PetFetchActions(runtime, this);
        training = new PetTraining(runtime, menus, holograms, hints, this::perform);
    }

    public void bindTrick(Player player, Pet pet, String word, Trick trick) { training.bindTrick(player, pet, word, trick); }
    public void endTraining(Player player, Pet pet, String reason) { training.endTraining(player, pet, reason); }
    public void missedReward(Player player, Pet pet, TrainingSession session) { training.missedReward(player, pet, session); }
    public void endForRest(Player player, Pet pet) { training.endForRest(player, pet); }
    public String treatName(Pet pet) { return training.treatName(pet); }
    public void toyLanded(org.bukkit.entity.Snowball ball) { fetchActions.toyLanded(ball); }
    PetFetchActions fetchActions() { return fetchActions; }
    public void releaseFetch(Pet pet, Player owner, boolean toOwner) { fetchActions.releaseFetch(pet, toOwner); }
    public boolean fetchingOrReturning(Pet pet) { return pet.fetch() != null || roaming.returningFromFetch(pet); }
    public boolean greeting(Pet pet) { return greetings.active(pet); }
    public boolean socializing(Pet pet) { return social.engaged(pet); }
    PetToyAnticipation anticipation() { return anticipation; }
    public void ownerDeparted(Player owner) { greetings.ownerDeparted(owner, System.currentTimeMillis()); }
    PetGreetings greetings() { return greetings; }
    public void dropPlain(Location location, String toy) { fetchActions.dropPlain(location, toy); }

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

    private void resumeFollowing(Player player, Pet pet, long now) {
        clearInteractions(pet); releaseFetch(pet, player, true);
        pet.order(PetOrder.FOLLOW); pet.staying(false); pet.forcedSitUntilMillis(0);
        runtime.resumeFollowing(pet);
        wakeToFollow(pet, now);
        Entity body = runtime.entity(pet);
        if (body != null) {
            markSleep(body, false); runtime.visual().cancelAction(body);
            PetFx.sit(body, false); PetFx.lie(body, false);
            if (body instanceof Mob mob) mob.setAware(true);
        }
        runtime.store().requestSave();
        PetFx.bar(player, pet.name() + " will follow you again (unless food, rest or health prevents it)");
    }

    public void clearInteractions(Pet pet) {
        anticipation.cancel(pet);
        greetings.cancel(pet);
        roaming.cancel(pet);
        calming.remove(pet.id());
        moments.cancel(pet);
        social.cancel(pet);
    }

    public void clearInteractions() {
        anticipation.clear();
        greetings.clear();
        roaming.clear();
        calming.clear();
        moments.clear();
        social.clear();
    }

    public void ownerSessionChanged(Player player) {
        runtime.suspendFollowing(player.getUniqueId());
        for (Pet pet : runtime.store().of(player.getUniqueId())) {
            if (pet.fetch() != null) continue;
            roaming.cancelWithPosture(pet);
            clearInteractions(pet);
            if (runtime.entity(pet) instanceof Mob mob && pet.order() == PetOrder.FOLLOW)
                PostureNavigationGoal.hold(runtime, pet, mob);
        }
    }

    private void pauseRestoredFollowing(Pet pet, Entity entity) {
        if (entity instanceof Mob mob && pet.order() == PetOrder.FOLLOW
                && !runtime.followingAllowed(pet, Bukkit.getPlayer(pet.ownerId())))
            PostureNavigationGoal.hold(runtime, pet, mob);
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

    public boolean triggerTestMoment(Player player, String moment) {
        Entity target = lookingAt(player, 6.0);
        Pet pet = runtime.byEntity(target);
        if (pet == null || pet.stored() || !pet.ownerId().equals(player.getUniqueId()) || !(target instanceof Mob body)) {
            PetFx.tell(player, "Look at one of your pets that is outside the Pet House first.");
            return false;
        }
        if (moment.equalsIgnoreCase("pet-greeting")) {
            boolean started = social.trigger(player, pet, "greeting");
            if (!started) PetFx.tell(player, "Bring another awake, following pet within five blocks, with both owners nearby. Pet greetings must be enabled; finish playing or training first.");
            return started;
        }
        if (moment.equalsIgnoreCase("greeting")) {
            boolean started = greetings.trigger(pet, player, System.currentTimeMillis());
            if (!started) PetFx.tell(player, "Greeting must be enabled for this pet and needs you nearby on land. Finish playing or training first; sit, lay and stay are allowed. Tired or ill pets greet quietly.");
            return started;
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
        boolean favorite = type.isTreat(hand);
        long now = System.currentTimeMillis();
        if (careActions.groom(player, pet, entity, hand, type, now)) return true;
        if (owner && favorite) {
            TrainingSession session = runtime.sessions().training(player.getUniqueId());
            if (session != null && session.petId().equals(pet.id()) && session.rewardTrick() != null && session.rewardUntil() > now) {
                training.reward(player, pet, session, hand);
                return true;
            }
            if (pet.need(Need.HUNGER) >= 60.0) {
                if (TrainingMath.attentionBlocked(pet.need(Need.HUNGER), pet.need(Need.ENERGY), sick(pet))) {
                    PetFx.bar(player, pet.name() + " can't train right now: " + training.focusReason(pet));
                    return true;
                }
                training.beginTraining(player, pet, entity);
                return true;
            }
        }
        Double gain = type.foodGain(hand);
        if (gain != null) {
            if (careActions.feed(player, pet, entity, hand, gain, favorite)) {
                return true;
            }
            if (owner && favorite) {
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
        if (type.acceptsToy(hand)) {
            if (!type.behaves(net.tfminecraft.companionpets.config.PetBehavior.FETCH)) return false;
            PetFx.bar(player, "Throw it into the air for nearby pets to chase");
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
        PetFx.look(entity, player);
        if (!ownerNearby(pet)) {
            PetFx.bar(player, PetTexts.missesOwner(pet.name(), pet.sex()));
            runtime.voice().sad(entity);
            return;
        }
        if (pet.illness() != Illness.NONE) {
            String medicine = type.medicines().isEmpty() ? "medicine" : String.join(" or ", type.medicines().stream().map(ItemRef::name).toList());
            cheer(pet, now, runtime.config().care().playMoodGain());
            PetFx.bar(player, PetTexts.illnessCheck(pet.name(), pet.sex(), pet.illness(), medicine)
                    + ". A little attention still cheers " + PetTexts.him(pet.sex()) + " up");
            runtime.voice().sad(entity);
            PetFx.hearts(entity, 1);
            return;
        }
        Need need = DominantNeed.select(pet);
        if (need != null) {
            PetFx.bar(player, PetTexts.needCheck(pet.name(), pet.sex(), need, NeedBand.of(pet.need(need)) == NeedBand.CRITICAL));
            runtime.voice().sad(entity);
            return;
        }
        comfort(pet, now);
        cheer(pet, now, PET_MOOD_GAIN);
        if (runtime.sessions().resting(pet.id(), now)) {
            PetFx.bar(player, PetTexts.restingCheck(pet.name(), pet.sex()));
            runtime.voice().happy(entity, false);
            PetFx.hearts(entity, 1);
            return;
        }
        boolean devoted = pet.bond() >= 85.0;
        runtime.recordCare(player, pet, 4, now);
        if (entity instanceof Mob mob && moments.petBelly(pet, mob, player, now)) {
            social.cancel(pet);
            roaming.cancel(pet);
            return;
        }
        PetFx.bar(player, PetTexts.petted(pet.name(), pet.sex(), pet.typeId(), devoted));
        if (!runtime.behaves(pet, net.tfminecraft.companionpets.config.PetBehavior.AFFECTION)) return;
        boolean animatedPet = runtime.visual().play(entity, type, "PET");
        runtime.voice().happy(entity, runtime.random().nextInt(3) == 0);
        PetFx.hearts(entity, devoted ? 4 : 2);
        if (!animatedPet && !runtime.visual().holdsMovement(entity)
                && devoted && runtime.behaves(pet, net.tfminecraft.companionpets.config.PetBehavior.AFFECTION_JUMPS)
                && pet.order() == PetOrder.FOLLOW && !pet.staying()) {
            PetFx.jump(entity, true);
        }
    }

    public void useWorld(Player player, ItemStack hand, Block clicked, BlockFace face, boolean sneaking, boolean air) {
        if (clicked != null && isKennel(clicked) && !sneaking) {
            UUID owner = runtime.store().kennelOwner(PetStore.kennelKey(
                    clicked.getWorld().getName(), clicked.getX(), clicked.getY(), clicked.getZ()));
            if (owner != null && !owner.equals(player.getUniqueId())) {
                PetFx.bar(player, "This Pet House belongs to someone else");
                return;
            }
            menus.openKennel(player);
            return;
        }
        if (sneaking && runtime.config().kennelFurniture() == null && runtime.config().kennel() != null && runtime.config().kennel().matches(hand) && clicked != null && face != null) {
            placeKennel(player, hand, clicked, face);
            return;
        }
        PetTypeDef egg = runtime.config().byEgg(hand);
        if (egg != null && hand != null) {
            hatching.begin(player, egg);
            return;
        }
        if (air && hand != null && isToy(hand)) {
            fetchActions.throwToy(player, hand);
        }
    }

    public boolean handledWorld(Player player, ItemStack hand, Block clicked, BlockFace face, boolean sneaking, boolean air) {
        if (clicked != null && isKennel(clicked) && !sneaking) {
            return true;
        }
        if (sneaking && runtime.config().kennelFurniture() == null && runtime.config().kennel() != null && runtime.config().kennel().matches(hand) && clicked != null && face != null) {
            return true;
        }
        if (runtime.config().byEgg(hand) != null) {
            return true;
        }
        return air && isToy(hand);
    }

    public void clickMenu(Player player, MenuHolder holder, int slot, ItemStack current, boolean rightClick, boolean shift, boolean lettingGo) {
        if (holder.kind() == MenuHolder.Kind.KENNEL) {
            if (slot == 53) {
                if (net.tfminecraft.companionpets.gui.MenuNavigation.turn(player, holder.page(), holder.pages(), rightClick))
                    menus.openKennel(player, holder.page() + (rightClick ? -1 : 1));
                return;
            }
        }
        if ((holder.kind() == MenuHolder.Kind.TRICK || holder.kind() == MenuHolder.Kind.LEARNED)
                && slot == PetMenus.TRICKS_NEXT_SLOT) {
            Pet pet = runtime.store().get(holder.petId());
            if (pet != null && (holder.kind() == MenuHolder.Kind.LEARNED || pet.ownerId().equals(player.getUniqueId()))) {
                if (!net.tfminecraft.companionpets.gui.MenuNavigation.turn(player, holder.page(), holder.pages(), rightClick)) return;
                int page = holder.page() + (rightClick ? -1 : 1);
                holder.navigating(true);
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
                if (pet != null) {
                    menus.openCare(player, pet, holder.petHouseBack(), holder.petHousePage());
                }
            }
            return;
        }
        if (holder.kind() == MenuHolder.Kind.TRICK) {
            if (slot == PetMenus.TRICKS_BACK_SLOT) {
                Pet pet = runtime.store().get(holder.petId());
                if (pet != null && pet.ownerId().equals(player.getUniqueId())) menus.openCare(player, pet, holder.petHouseBack(), holder.petHousePage());
            } else clickTrick(player, holder, slot);
            return;
        }
        if (current == null || slot >= 45) {
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
        menus.openCare(player, pet, true, holder.page());
    }

    private void clickCare(Player player, MenuHolder holder, int slot) {
        Pet pet = runtime.store().get(holder.petId());
        if (pet == null) return;
        boolean owner = pet.ownerId().equals(player.getUniqueId());
        int tricksSlot = owner ? PetMenus.careSlot(PetMenus.TRICKS_SLOT, holder.petHouseBack()) : PetMenus.TRICKS_SLOT;
        if (slot == tricksSlot) {
            menus.openLearned(player, pet);
            return;
        }
        if (!owner) return;
        if (slot == PetMenus.NAME_SLOT) {
            beginRename(player, pet);
            player.closeInventory();
            return;
        }
        if (slot == PetMenus.BACK_SLOT) {
            if (holder.petHouseBack()) menus.openKennel(player, holder.petHousePage());
            return;
        }
        if (slot == PetMenus.careSlot(PetMenus.RELEASE_SLOT, holder.petHouseBack())) {
            beginRelease(player, pet);
            return;
        }
        if (slot == PetMenus.careSlot(PetMenus.CALL_SLOT, holder.petHouseBack())) {
            if (pet.stored()) {
                takeOut(player, pet);
            } else {
                call(player, pet);
            }
            menus.openCare(player, pet, holder.petHouseBack(), holder.petHousePage());
            return;
        }
        if (slot == PetMenus.careSlot(PetMenus.STORE_SLOT, holder.petHouseBack()) && !pet.stored()) {
            storePet(player, pet);
            player.closeInventory();
        }

    }

    public void onChat(Player player, String text) {
        long now = System.currentTimeMillis();
        if (handleReleaseChat(player, text, now) || hatching.chat(player, text, now) || handleRenameChat(player, text, now)) {
            return;
        }
        if (respondToName(player, text, now) || addressedOrder(player, text, now)) return;
        Pet looked = runtime.byEntity(lookingAt(player, 6.0));
        TrainingSession session = runtime.sessions().training(player.getUniqueId());
        if (looked != null && looked.ownerId().equals(player.getUniqueId()) && !looked.stored()
                && (looked.trickFor(SpokenOrder.key(text)) != null
                        || session != null && session.petId().equals(looked.id()))) {
            training.handleTrainingChat(player, text, now, looked, runtime.entity(looked));
            return;
        }
        training.handleTrainingChat(player, text, now);
    }

    private boolean audible(Player player, Pet pet) {
        Entity body = runtime.entity(pet);
        return pet.ownerId().equals(player.getUniqueId()) && !pet.stored() && !pet.dead()
                && body instanceof Mob && body.getWorld().equals(player.getWorld())
                && body.getLocation().distanceSquared(player.getLocation()) <= Math.pow(runtime.config().hearingRadius(), 2);
    }

    private boolean addressedOrder(Player player, String text, long now) {
        var matches = runtime.store().of(player.getUniqueId()).stream().filter(p -> audible(player, p))
                .filter(p -> SpokenOrder.addressedCommand(text, p.name()) != null).toList();
        if (matches.isEmpty()) return false;
        int longest = matches.stream().mapToInt(p -> SpokenOrder.key(p.name()).length()).max().orElse(0);
        matches = matches.stream().filter(p -> SpokenOrder.key(p.name()).length() == longest).toList();
        if (matches.size() > 1) {
            PetFx.tell(player, "More than one nearby pet matches that name. Look at the intended pet and say its learned word."); return true;
        }
        Pet pet = matches.getFirst(); String word = SpokenOrder.addressedCommand(text, pet.name());
        TrainingSession session = runtime.sessions().training(player.getUniqueId());
        if (pet.trickFor(word) == null && (session == null || !session.petId().equals(pet.id()))) {
            PetFx.bar(player, pet.name() + " has not learned that word"); return true;
        }
        training.handleTrainingChat(player, word, now, pet, runtime.entity(pet));
        return true;
    }

    private boolean respondToName(Player player, String text, long now) {
        boolean answered = false;
        for (Pet pet : runtime.store().active()) {
            if (!audible(player, pet)
                    || !SpokenOrder.matches(text, pet.name())) continue;
            Entity entity = runtime.entity(pet);
            if (!(entity instanceof Mob mob) || !mob.getWorld().equals(player.getWorld())) continue;
            if (pet.order() == PetOrder.FOLLOW && !pet.staying() && pet.activity() != Activity.SLEEPING) {
                boolean coming = roaming.coming(pet); PetOrder previous = roaming.returnOrder(pet);
                clearInteractions(pet); releaseFetch(pet, player, true);
                if (coming) roaming.come(pet, player, now, previous);
                else roaming.attend(pet, player, now);
            } else roaming.listen(pet, player, now);
            PetFx.bar(player, pet.name() + " heard its name. You can say \"" + pet.name() + " <command>\"");
            answered = true;
        }
        return answered;
    }

    public void reattach(Entity entity) {
        UUID id = runtime.bodies().readId(entity);
        if (id == null) {
            return;
        }
        Pet pet = runtime.store().get(id);
        if (pet == null) {
            if (runtime.store().isDeleted(id)) {
                runtime.visual().removeBody(entity);
            }
            return;
        }
        if (pet.stored()) {
            runtime.visual().removeBody(entity);
            return;
        }
        if (pet.entityId() != null && !pet.entityId().equals(entity.getUniqueId()) && runtime.entity(pet) != null) {
            runtime.visual().removeBody(entity);
            return;
        }
        PetTypeDef type = runtime.config().type(pet.typeId());
        if (type == null) {
            runtime.remember(pet, entity);
            if (entity instanceof Mob mob) net.tfminecraft.companionpets.integration.PetMotion.hold(mob);
            runtime.plugin().getLogger().warning("Pet " + pet.id() + " retains its saved body and data, but its type "
                    + pet.typeId() + " is unavailable; correct its configuration before taking it out");
            return;
        }
        if (!runtime.bodies().compatible(entity, type)) {
            // Prepare a replacement before removing the old body. Pet identity and saved care never change.
            Entity replacement = runtime.bodies().spawn(pet, type, entity.getLocation(), Bukkit.getPlayer(pet.ownerId()));
            if (replacement == null) {
                runtime.remember(pet, entity);
                if (entity instanceof Mob mob) net.tfminecraft.companionpets.integration.PetMotion.hold(mob);
                return;
            }
            clearInteractions(pet);
            runtime.remember(pet, replacement);
            runtime.visual().removeBody(entity);
            entity = replacement;
            runtime.store().requestSave();
        }
        runtime.remember(pet, entity);
        runtime.bodies().reattach(entity, pet, type);
        pauseRestoredFollowing(pet, entity);
    }

    public Entity restoreBody(Pet pet) {
        if (pet.stored() || pet.dead() || !runtime.store().canRestoreBodies()) return null;
        Entity body = runtime.entity(pet);
        if (body != null) return body;
        World world = Bukkit.getWorld(pet.worldName());
        if (world == null) return null;
        int chunkX = ((int) Math.floor(pet.x())) >> 4;
        int chunkZ = ((int) Math.floor(pet.z())) >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) return null;
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        if (!chunk.isEntitiesLoaded()) return null;
        // Adopt an existing tagged body before spawning, even if its saved entity
        // UUID is stale. Late-loading duplicates are removed by reattach().
        for (Entity candidate : chunk.getEntities()) {
            if (candidate.isValid() && !candidate.isDead() && pet.id().equals(runtime.bodies().readId(candidate))) {
                reattach(candidate);
                runtime.store().requestSave();
                return runtime.entity(pet);
            }
        }
        Location location = new Location(world, pet.x(), pet.y(), pet.z(), pet.yaw(), 0);
        body = runtime.bodies().spawn(pet, runtime.config().type(pet.typeId()), location, Bukkit.getPlayer(pet.ownerId()));
        if (body != null) {
            pet.clearRuntimeMotion();
            runtime.remember(pet, body);
            pauseRestoredFollowing(pet, body);
            runtime.store().requestSave();
            runtime.plugin().getLogger().info("Restored missing body for pet " + pet.id() + " (" + pet.name() + ")");
        }
        return body;
    }

    public void stashLooseToys() {
        fetchActions.stashLooseToys();
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

    private void perform(Player player, Pet pet, Entity entity, Trick trick, boolean partial) {
        if (!training.checkTrick(player, pet, trick)) return;
        long now = System.currentTimeMillis();
        if (pet.activity() == Activity.SLEEPING && trick != Trick.LAY && trick != Trick.FOLLOW && trick != Trick.COME && trick != Trick.SIT && trick != Trick.STAY) {
            PetFx.bar(player, pet.name() + " is resting. Tell " + PetTexts.him(pet.sex()) + " to follow");
            return;
        }
        if (partial && trick.kind() == Trick.Kind.CUSTOM) return;
        runtime.visual().cancelAction(entity);
        if (trick.kind() == Trick.Kind.CUSTOM) {
            var definition = runtime.config().customTrick(trick);
            if (definition == null) return;
            boolean played = runtime.visual().playClip(entity, runtime.config().type(pet.typeId()), definition.animation(), definition.duration());
            if (!played && !definition.fallbackText().isBlank())
                training.hologram(pet, definition.fallbackText().replace("{pet}", pet.name()).replace("{owner}", player.getName()), NamedTextColor.WHITE,
                        Math.round(definition.duration() * 20));
            PetFx.bar(player, pet.name() + " performs " + definition.displayName());
            return;
        }
        switch (trick.kind()) {
            case SIT -> {
                if (partial) {
                    clearInteractions(pet);
                    pet.forcedSitUntilMillis(now + 800L);
                    if (entity instanceof Mob mob) PostureNavigationGoal.hold(runtime, pet, mob);
                } else {
                    clearInteractions(pet); releaseFetch(pet, player, true); wakeToFollow(pet, now);
                    pet.order(PetOrder.SIT);
                    pet.staying(false);
                    if (entity instanceof Mob mob) { PostureNavigationGoal.hold(runtime, pet, mob); PetFx.sit(mob, true); }
                }
            }
            case FOLLOW -> {
                resumeFollowing(player, pet, now);
            }
            case COME -> {
                PetOrder previous = roaming.returnOrder(pet);
                clearInteractions(pet); releaseFetch(pet, player, true);
                pet.order(PetOrder.FOLLOW); pet.staying(false); pet.forcedSitUntilMillis(0);
                wakeToFollow(pet, now);
                markSleep(entity, false); PetFx.sit(entity, false); PetFx.lie(entity, false);
                roaming.come(pet, player, now, previous);
            }
            case STAY -> {
                clearInteractions(pet); releaseFetch(pet, player, true); wakeToFollow(pet, now);
                pet.staying(true);
                pet.order(PetOrder.STAY);
                pet.forcedSitUntilMillis(0);
                if (entity instanceof Mob mob) { PostureNavigationGoal.hold(runtime, pet, mob); PetFx.sit(mob, false); PetFx.lie(mob, false); }
            }
            case SPEAK -> {
                if (entity != null) {
                    runtime.voice().ambient(entity);
                }
            }
            case JUMP -> {
                if (entity != null) {
                    standForAction(pet, entity);
                    PetFx.jump(entity, partial);
                }
            }
            case LAY -> {
                clearInteractions(pet);
                releaseFetch(pet, player, true);
                pet.order(PetOrder.LAY);
                wakeToFollow(pet, now);
                pet.staying(false);
                pet.forcedSitUntilMillis(0L);
                PetFx.lie(entity, true);
                if (entity instanceof Mob mob) PostureNavigationGoal.hold(runtime, pet, mob);
                markSleep(entity, false);
            }
            case PAW -> {
                if (entity != null) {
                    PetFx.look(entity, player);
                    PetFx.particle(entity, Particle.HEART, 2);
                }
            }
            default -> {
            }
        }
        if (trick == Trick.FOLLOW) {
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
            if (trick == Trick.COME) {
                runtime.visual().update(entity, type, net.tfminecraft.companionpets.visual.PetAnimation.IDLE);
            } else if (trick == Trick.STAY) {
                runtime.visual().update(entity, type, net.tfminecraft.companionpets.visual.PetAnimation.IDLE);
            } else if (trick == Trick.LAY) {
                runtime.visual().update(entity, type, net.tfminecraft.companionpets.visual.PetAnimation.LIE);
            } else runtime.visual().play(entity, type, trick.name());
        }
    }

    private void standForAction(Pet pet, Entity entity) {
        pet.order(PetOrder.FOLLOW);
        pet.staying(false);
        pet.forcedSitUntilMillis(0L);
        PetFx.sit(entity, false);
        PetFx.lie(entity, false);
        if (entity instanceof Mob mob) mob.setAware(true);
    }

    private void wakeToFollow(Pet pet, long now) {
        if (pet.activity() == Activity.SLEEPING) {
            long wait = Math.round(runtime.config().care().restAgainSeconds() * 1000.0);
            pet.refuseRestUntilMillis(now + wait);
        }
        pet.activity(Activity.NONE);
    }

    public void lostBody(Pet pet) {
        releaseFetch(pet, Bukkit.getPlayer(pet.ownerId()), true);
        Entity dying = pet.entityId() == null ? null : Bukkit.getEntity(pet.entityId());
        if (pet.carriedToy() != null && dying != null) {
            dropPlain(dying.getLocation(), pet.carriedToy());
            pet.carriedToy(null);
        }
        // An observed death cannot be rolled back if disk writes fail. Freeze the
        // record and retain the session guard so a stale save cannot revive it.
        if (!runtime.store().remove(pet.id())) pet.dead(true);
        clearInteractions(pet);
        runtime.sessions().clearPet(pet.id());
        Entity body = pet.entityId() == null ? null : Bukkit.getEntity(pet.entityId());
        if (body != null) {
            markSleep(body, false);
        }
        runtime.store().requestSave();
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

    private void takeOut(Player player, Pet pet) {
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
        // Time in the Pet House is not an absence that earns a welcome.
        pet.lastOwnerNearbyMillis(System.currentTimeMillis());
        runtime.remember(pet, entity);
        runtime.resumeFollowing(pet);
        runtime.store().requestSave();
    }

    private void releasePet(Player player, Pet pet) {
        if (!pet.ownerId().equals(player.getUniqueId())) return;
        if (!runtime.store().remove(pet.id())) {
            PetFx.tell(player, "Could not save the release. Your pet is still with you; contact an administrator.");
            return;
        }
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
            runtime.visual().removeBody(entity);
        }
        runtime.store().requestSave();
        PetFx.tell(player, pet.name() + " is gone. " + PetTexts.He(pet.sex()) + " is no longer with you");
    }

    private void beginRelease(Player player, Pet pet) {
        if (!pet.ownerId().equals(player.getUniqueId())) return;
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

    public boolean spawnTestPet(Player player, String typeId, String name) {
        if (!runtime.store().canRestoreBodies()) {
            PetFx.tell(player, "Pet persistence is unavailable; creation refused.");
            return false;
        }
        PetTypeDef type = runtime.config().type(typeId);
        if (type == null) {
            PetFx.tell(player, "Unknown pet type: " + typeId + ". Configured types: "
                    + String.join(", ", runtime.config().types().keySet().stream().sorted().toList()));
            return false;
        }
        if (!Quota.canBringOut(runtime.store().countOut(player.getUniqueId()), runtime.config().limits().maxOut())) {
            PetFx.tell(player, "You have reached the active pet limit. Send one to the Pet House first.");
            return false;
        }
        String safeName = Names.sanitize(name);
        if (safeName.isBlank()) {
            safeName = Names.sanitize(type.id());
        }
        Pet pet = new Pet(UUID.randomUUID(), player.getUniqueId(), type.id(), safeName, PetSex.MALE);
        net.tfminecraft.companionpets.training.DefaultTricks.apply(runtime.config(), pet);
        pet.bornAt(System.currentTimeMillis());
        pet.favoriteToy(FavoriteToy.reconcile(null, toyNames(type), runtime.random()).toy());
        Entity entity = runtime.bodies().spawn(pet, type, PetRuntime.beside(player), player);
        if (entity == null) {
            PetFx.tell(player, "Could not spawn the pet of type " + type.id() + ".");
            return false;
        }
        runtime.remember(pet, entity);
        var learned = new java.util.ArrayList<String>();
        for (Trick trick : runtime.config().tricks()) {
            if (!net.tfminecraft.companionpets.training.TrickAvailability.allows(runtime, pet, trick)) continue;
            String word = trick.name().toLowerCase(Locale.ROOT);
            pet.bindWord(word, trick);
            pet.progress(trick, 100.0);
            learned.add(word);
        }
        pet.stored(false);
        runtime.store().add(pet);
        if (!runtime.store().save()) {
            if (runtime.store().remove(pet.id())) {
                runtime.visual().removeBody(entity);
                runtime.store().requestSave();
            } else {
                runtime.plugin().getLogger().severe("Could not roll back unsaved pet " + pet.id());
            }
            PetFx.tell(player, "The pet could not be saved. Check the server log.");
            return false;
        }
        PetFx.tell(player, safeName + " (" + type.id() + ") spawned with every compatible trick learned.");
        if (!learned.isEmpty()) {
            PetFx.tell(player, "Look at the pet and say a trick word, or say its name and word: " + String.join(", ", learned));
        }
        return true;
    }

    private void storePet(Player player, Pet pet) {
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
            runtime.visual().removeBody(entity);
        }
        pet.entityId(null);
        pet.stored(true);
        pet.clearRuntimeMotion();
        runtime.store().requestSave();
    }

    private void call(Player player, Pet pet) {
        World world = Bukkit.getWorld(pet.worldName());
        if (world != null) {
            int chunkX = ((int) Math.floor(pet.x())) >> 4;
            int chunkZ = ((int) Math.floor(pet.z())) >> 4;
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            chunk.load(true);
            chunk.getEntities();
        }
        Entity entity = restoreBody(pet);
        if (entity == null) {
            PetFx.bar(player, pet.name() + " can't be found. Send " + PetTexts.him(pet.sex()) + " to the Pet House to bring "
                    + PetTexts.him(pet.sex()) + " back");
            return;
        }
        clearInteractions(pet);
        pet.order(PetOrder.FOLLOW);
        pet.staying(false);
        wakeToFollow(pet, System.currentTimeMillis());
        runtime.resumeFollowing(pet);
        entity.teleport(PetRuntime.beside(player));
        runtime.remember(pet, entity);
        runtime.store().requestSave();
        PetFx.bar(player, pet.name() + " comes running to your side");
    }

    private void placeKennel(Player player, ItemStack hand, Block clicked, BlockFace face) {
        Block place = clicked.getRelative(face);
        if (!place.getType().isAir() && !place.isReplaceable()) {
            PetFx.bar(player, "There isn't enough room for a Pet House there");
            return;
        }
        // Protection listeners expect the proposed block and the original snapshot,
        // as with a vanilla placement. Do not consume or register it until accepted.
        BlockState replaced = place.getState();
        place.setType(runtime.config().kennelBlock(), false);
        boolean accepted = false;
        try {
            BlockPlaceEvent event = new BlockPlaceEvent(place, replaced, clicked, hand.clone(), player, true, EquipmentSlot.HAND);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled() || !event.canBuild() || !consumeHand(player, hand)) return;
            accepted = true;
        } finally {
            if (!accepted) replaced.update(true, false);
        }
        place.getState().update(true, true);
        runtime.store().kennel(PetStore.kennelKey(place.getWorld().getName(), place.getX(), place.getY(), place.getZ()), player.getUniqueId());
        runtime.store().requestSave();
        PetFx.bar(player, "Pet House placed. Right-click it to look after your pets");
    }

    private boolean isKennel(Block block) {
        if (block.getType() != runtime.config().kennelBlock()) {
            return false;
        }
        return runtime.store().kennelOwner(PetStore.kennelKey(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ())) != null;
    }

    private boolean isToy(ItemStack item) {
        for (PetTypeDef type : runtime.config().types().values()) {
            if (type.acceptsToy(item)) {
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
        for (ItemRef toy : type.toys()) {
            names.add(toy.key());
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
