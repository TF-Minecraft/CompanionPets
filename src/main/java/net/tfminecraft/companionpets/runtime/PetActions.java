package net.tfminecraft.companionpets.runtime;

import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.gui.MenuHolder;
import net.tfminecraft.companionpets.gui.PetMenus;
import net.tfminecraft.companionpets.management.Quota;
import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetOrder;
import net.tfminecraft.companionpets.pet.PetSex;
import net.tfminecraft.companionpets.pet.SexMode;
import net.tfminecraft.companionpets.pet.Trick;
import net.tfminecraft.companionpets.play.FavoriteToy;
import net.tfminecraft.companionpets.play.FetchJob;
import net.tfminecraft.companionpets.play.ThrowSpeed;
import net.tfminecraft.companionpets.session.HatchPrompt;
import net.tfminecraft.companionpets.session.RenamePrompt;
import net.tfminecraft.companionpets.session.TrainingSession;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.text.Names;
import net.tfminecraft.companionpets.text.PetTexts;
import net.tfminecraft.companionpets.training.TrainingMath;
import net.tfminecraft.companionpets.training.TrainingSettings;

public final class PetActions {
    private static final long PROMPT_MILLIS = 60_000L;

    private final PetRuntime runtime;
    private final PetMenus menus;

    public PetActions(PetRuntime runtime) {
        this.runtime = runtime;
        this.menus = new PetMenus(runtime);
    }

    public PetMenus menus() {
        return menus;
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
            PetFx.bar(player, pet.name() + " empieza a recuperarse");
            return true;
        }
        if (held == Material.BRUSH) {
            pet.need(Need.CLEANLINESS, 100);
            comfort(pet, now);
            PetFx.hearts(entity, 2);
            PetFx.bar(player, pet.name() + (pet.sex() == PetSex.FEMALE ? " queda limpia" : " queda limpio"));
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
                    PetFx.bar(player, PetTexts.refusal(pet.name(), pet.sex(), "attention"));
                    return true;
                }
                beginTraining(player, pet, entity);
                return true;
            }
        }
        Double gain = type.foodGain(held);
        if (gain != null) {
            feed(player, pet, entity, hand, gain, held == type.favoriteFood());
            return true;
        }
        if (held == Material.AIR) {
            menus.openCare(player, pet);
            return true;
        }
        if (type.acceptsToy(held)) {
            PetFx.bar(player, "Lanza el juguete al aire");
            return true;
        }
        return false;
    }

    public void useWorld(Player player, ItemStack hand, Block clicked, BlockFace face, boolean sneaking, boolean air) {
        Material held = hand == null ? Material.AIR : hand.getType();
        if (clicked != null && isKennel(clicked) && !sneaking) {
            UUID owner = runtime.store().kennelOwner(PetStore.kennelKey(
                    clicked.getWorld().getName(), clicked.getX(), clicked.getY(), clicked.getZ()));
            if (owner != null && !owner.equals(player.getUniqueId())) {
                PetFx.bar(player, "Esta caseta no es tuya");
                return;
            }
            menus.openKennel(player);
            return;
        }
        if (sneaking && held == runtime.config().kennel() && clicked != null && face != null) {
            placeKennel(player, hand, clicked, face);
            return;
        }
        if (held == runtime.config().whistle()) {
            menus.openKennel(player);
            return;
        }
        PetTypeDef egg = runtime.config().byEgg(held);
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
        if (held == runtime.config().whistle()) {
            return true;
        }
        if (runtime.config().byEgg(held) != null) {
            return true;
        }
        return air && isToy(held);
    }

    public void clickMenu(Player player, MenuHolder holder, int slot, ItemStack current, boolean rightClick, boolean shift) {
        if (holder.kind() == MenuHolder.Kind.CARE) {
            clickCare(player, holder.petId(), slot);
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
        if (shift) {
            beginRename(player, pet);
            player.closeInventory();
            return;
        }
        if (rightClick) {
            storePet(player, pet);
        } else if (pet.stored()) {
            takeOut(player, pet);
        } else {
            call(player, pet);
        }
        Bukkit.getScheduler().runTask(runtime.plugin(), () -> {
            if (player.isOnline()) {
                menus.openKennel(player);
            }
        });
    }

    public void onChat(Player player, String text) {
        long now = System.currentTimeMillis();
        if (handleHatchChat(player, text, now) || handleRenameChat(player, text, now)) {
            return;
        }
        handleTrainingChat(player, text, now);
    }

    public void bindTrick(Player player, Pet pet, String word, Trick trick) {
        pet.bindWord(word, trick);
        TrainingSession session = runtime.sessions().training(player.getUniqueId());
        if (session != null) {
            session.pendingWord(null);
        }
        player.closeInventory();
        PetFx.bar(player, word + " significa " + PetTexts.trickName(trick).toLowerCase(Locale.ROOT));
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
            entity.remove();
            return;
        }
        if (pet.entityId() != null && !pet.entityId().equals(entity.getUniqueId())) {
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

    private void clickCare(Player player, UUID petId, int slot) {
        Pet pet = runtime.store().get(petId);
        if (pet == null) {
            return;
        }
        boolean owner = pet.ownerId().equals(player.getUniqueId());
        Entity entity = runtime.entity(pet);
        switch (slot) {
            case 19 -> feedFromInventory(player, pet, entity);
            case 20 -> {
                if (!owner) {
                    PetFx.bar(player, PetTexts.refusal(pet.name(), pet.sex(), "owner"));
                    return;
                }
                startPlay(player, pet);
            }
            case 21 -> {
                if (!owner) {
                    PetFx.bar(player, PetTexts.refusal(pet.name(), pet.sex(), "owner"));
                    return;
                }
                toggleSleep(player, pet);
            }
            case 22 -> {
                if (!owner) {
                    PetFx.bar(player, PetTexts.refusal(pet.name(), pet.sex(), "owner"));
                    return;
                }
                toggleSit(player, pet);
            }
            case 23 -> {
                if (!player.getInventory().contains(Material.BRUSH)) {
                    PetFx.bar(player, "Hace falta un cepillo");
                    return;
                }
                pet.need(Need.CLEANLINESS, 100);
                comfort(pet, System.currentTimeMillis());
                if (entity != null) {
                    PetFx.hearts(entity, 2);
                }
            }
            case 24 -> healFromInventory(player, pet, entity);
            default -> {
                return;
            }
        }
        Bukkit.getScheduler().runTask(runtime.plugin(), () -> {
            if (player.isOnline() && runtime.store().get(pet.id()) != null) {
                menus.openCare(player, pet);
            }
        });
    }

    private void clickTrick(Player player, MenuHolder holder, int slot) {
        int index = PetMenus.trickIndex(slot);
        Trick[] tricks = Trick.values();
        if (index < 0 || index >= tricks.length || holder.word() == null || holder.petId() == null) {
            return;
        }
        Pet pet = runtime.store().get(holder.petId());
        if (pet == null || !pet.ownerId().equals(player.getUniqueId())) {
            return;
        }
        bindTrick(player, pet, holder.word(), tricks[index]);
    }

    private void beginHatch(Player player, PetTypeDef type) {
        if (!Quota.canBringOut(runtime.store().countOut(player.getUniqueId()), runtime.config().limits().maxOut())) {
            PetFx.bar(player, PetTexts.refusal("", PetSex.FEMALE, "full-out"));
            return;
        }
        HatchPrompt prompt = new HatchPrompt(type.id(), System.currentTimeMillis() + PROMPT_MILLIS);
        runtime.sessions().hatch(player.getUniqueId(), prompt);
        player.sendMessage("Escribe el nombre de la mascota. Escribe no para cancelar.");
    }

    private boolean handleHatchChat(Player player, String text, long now) {
        HatchPrompt prompt = runtime.sessions().hatch(player.getUniqueId());
        if (prompt == null) {
            return false;
        }
        if (now > prompt.expiresAt()) {
            runtime.sessions().clearHatch(player.getUniqueId());
            player.sendMessage("Se ha cancelado el nombre. El huevo no se ha gastado.");
            return true;
        }
        if (Names.cancels(text) && (prompt.name() != null || prompt.choosingSex())) {
            runtime.sessions().clearHatch(player.getUniqueId());
            player.sendMessage("El huevo no se ha gastado.");
            return true;
        }
        PetTypeDef type = runtime.config().type(prompt.typeId());
        if (type == null) {
            runtime.sessions().clearHatch(player.getUniqueId());
            return true;
        }
        if (prompt.name() == null) {
            String name = Names.sanitize(text);
            if (name.isBlank()) {
                player.sendMessage("El nombre está vacío.");
                return true;
            }
            prompt.name(name);
            prompt.confirming(true);
            player.sendMessage("Confirma el nombre " + name + " escribiendo sí.");
            return true;
        }
        if (prompt.confirming() && !prompt.choosingSex()) {
            if (!Names.confirms(text)) {
                player.sendMessage("Escribe sí para confirmar o no para cancelar.");
                return true;
            }
            prompt.confirming(false);
            if (type.sexMode() == SexMode.CHOOSE) {
                prompt.choosingSex(true);
                player.sendMessage("Escribe macho o hembra.");
                return true;
            }
            finishHatch(player, type, prompt.name(), runtime.random().nextBoolean() ? PetSex.MALE : PetSex.FEMALE);
            return true;
        }
        if (prompt.choosingSex()) {
            String line = text.trim().toLowerCase(Locale.ROOT);
            PetSex sex;
            if (line.equals("macho") || line.equals("male")) {
                sex = PetSex.MALE;
            } else if (line.equals("hembra") || line.equals("female")) {
                sex = PetSex.FEMALE;
            } else {
                player.sendMessage("Escribe macho o hembra.");
                return true;
            }
            finishHatch(player, type, prompt.name(), sex);
        }
        return true;
    }

    private void finishHatch(Player player, PetTypeDef type, String name, PetSex sex) {
        runtime.sessions().clearHatch(player.getUniqueId());
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != type.egg()) {
            player.sendMessage("El huevo ya no está en tu mano.");
            return;
        }
        if (!Quota.canBringOut(runtime.store().countOut(player.getUniqueId()), runtime.config().limits().maxOut())) {
            player.sendMessage(PetTexts.refusal(name, sex, "full-out"));
            return;
        }
        if (!consumeHand(player, hand)) {
            return;
        }
        Pet pet = new Pet(UUID.randomUUID(), player.getUniqueId(), type.id(), name, sex);
        FavoriteToy.Result favorite = FavoriteToy.reconcile(null, toyNames(type), runtime.random());
        pet.favoriteToy(favorite.toy());
        runtime.store().add(pet);
        player.sendMessage("Ha nacido " + name + ". Es " + (sex == PetSex.FEMALE ? "hembra." : "macho."));
        Entity entity = runtime.bodies().spawn(pet, type, PetRuntime.beside(player), player);
        if (entity == null) {
            pet.stored(true);
            player.sendMessage(name + " no ha podido salir. Está en la caseta.");
        } else {
            pet.stored(false);
            runtime.remember(pet, entity);
        }
        runtime.store().save();
    }

    private void beginRename(Player player, Pet pet) {
        runtime.sessions().rename(player.getUniqueId(), new RenamePrompt(pet.id(), System.currentTimeMillis() + PROMPT_MILLIS));
        player.sendMessage("Escribe el nuevo nombre de " + pet.name() + ".");
    }

    private boolean handleRenameChat(Player player, String text, long now) {
        RenamePrompt prompt = runtime.sessions().rename(player.getUniqueId());
        if (prompt == null) {
            return false;
        }
        Pet pet = runtime.store().get(prompt.petId());
        if (pet == null || now > prompt.expiresAt()) {
            runtime.sessions().clearRename(player.getUniqueId());
            return true;
        }
        if (prompt.name() == null) {
            String name = Names.sanitize(text);
            if (name.isBlank()) {
                player.sendMessage("El nombre está vacío.");
                return true;
            }
            prompt.name(name);
            prompt.confirming(true);
            player.sendMessage("Confirma el nombre " + name + " escribiendo sí.");
            return true;
        }
        if (!prompt.confirming()) {
            return true;
        }
        if (Names.cancels(text)) {
            runtime.sessions().clearRename(player.getUniqueId());
            player.sendMessage("Se mantiene el nombre " + pet.name() + ".");
            return true;
        }
        if (!Names.confirms(text)) {
            player.sendMessage("Escribe sí para confirmar o no para cancelar.");
            return true;
        }
        pet.name(prompt.name());
        Entity entity = runtime.entity(pet);
        if (entity != null) {
            runtime.bodies().name(entity, pet.name());
        }
        runtime.sessions().clearRename(player.getUniqueId());
        player.sendMessage("Ahora se llama " + pet.name() + ".");
        return true;
    }

    private void handleTrainingChat(Player player, String text, long now) {
        Entity looked = lookingAt(player, 6.0);
        Pet pet = runtime.byEntity(looked);
        if (pet == null || !pet.ownerId().equals(player.getUniqueId()) || pet.stored()) {
            return;
        }
        String line = text.trim();
        if (line.isEmpty()) {
            return;
        }
        TrainingSession session = runtime.sessions().training(player.getUniqueId());
        if (session != null && session.petId().equals(pet.id())) {
            if (session.pendingWord() != null) {
                return;
            }
            Trick known = pet.trickFor(line);
            if (known == null) {
                session.pendingWord(line);
                menus.openTricks(player, pet, line);
                if (looked != null) {
                    PetFx.particle(looked, Particle.END_ROD, 4);
                }
                PetFx.bar(player, pet.name() + " inclina la cabeza");
                return;
            }
            if (pet.progress(known) >= runtime.config().training().learnedAt()) {
                perform(player, pet, looked, known, false);
                return;
            }
            if (!spendTrainingEffort(player, pet)) {
                return;
            }
            int attempts = session.addAttempt();
            TrainingMath.Attempt result = TrainingMath.attempt(pet.progress(known), runtime.random(), runtime.config().training());
            perform(player, pet, looked, known, result != TrainingMath.Attempt.SUCCESS);
            session.reward(
                    now + Math.round(runtime.config().training().rewardWindowSeconds() * 1000.0),
                    result == TrainingMath.Attempt.SUCCESS,
                    known);
            if (result == TrainingMath.Attempt.SUCCESS) {
                player.sendMessage("Premia a " + pet.name() + ".");
            } else if (result == TrainingMath.Attempt.PARTIAL) {
                player.sendMessage(pet.name() + " lo intenta.");
            } else {
                player.sendMessage(pet.name() + " no entiende.");
            }
            if (attempts >= runtime.config().training().attemptsBeforeBored()) {
                runtime.sessions().clearTraining(player.getUniqueId());
                runtime.sessions().rest(pet.id(), now + Math.round(runtime.config().training().restSeconds() * 1000.0));
                PetFx.bar(player, pet.name() + " se aburre. Energía " + Math.round(pet.need(Need.ENERGY)));
            }
            return;
        }
        Trick known = pet.trickFor(line);
        if (known != null && pet.progress(known) >= runtime.config().training().learnedAt()) {
            perform(player, pet, looked, known, false);
        }
    }

    private void beginTraining(Player player, Pet pet, Entity entity) {
        TrainingSession existing = runtime.sessions().training(player.getUniqueId());
        if (existing != null && existing.petId().equals(pet.id())) {
            return;
        }
        if (runtime.sessions().resting(pet.id(), System.currentTimeMillis())) {
            PetFx.bar(player, PetTexts.refusal(pet.name(), pet.sex(), "bored"));
            return;
        }
        runtime.sessions().training(player.getUniqueId(), new TrainingSession(pet.id()));
        PetFx.look(entity, player.getEyeLocation());
        if (entity instanceof Mob mob) {
            mob.getPathfinder().stopPathfinding();
        }
        PetFx.bar(player, "Di la orden mirando a " + pet.name()
                + ". Energía " + Math.round(pet.need(Need.ENERGY)));
    }

    private boolean spendTrainingEffort(Player player, Pet pet) {
        if (TrainingMath.attentionBlocked(pet.need(Need.HUNGER), pet.need(Need.ENERGY), sick(pet))) {
            runtime.sessions().clearTraining(player.getUniqueId());
            String reason = pet.need(Need.ENERGY) < 25.0 ? "tired" : "attention";
            PetFx.bar(player, PetTexts.refusal(pet.name(), pet.sex(), reason));
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
        if (trick == null || !consumeHand(player, hand)) {
            return;
        }
        boolean success = Boolean.TRUE.equals(session.rewardSuccess());
        pet.progress(trick, TrainingMath.afterReward(
                pet.progress(trick),
                success,
                pet.need(Need.MOOD),
                pet.bond(),
                runtime.config().training()));
        pet.need(Need.HUNGER, pet.need(Need.HUNGER) + runtime.config().training().treatHungerGain());
        session.clearReward();
        Entity entity = runtime.entity(pet);
        if (entity != null) {
            PetFx.eat(entity);
            if (success) {
                PetFx.hearts(entity, 3);
            }
        }
        PetFx.bar(player, success ? pet.name() + " aprende" : "Eso casi no ayuda");
        comfort(pet, System.currentTimeMillis());
    }

    private void perform(Player player, Pet pet, Entity entity, Trick trick, boolean partial) {
        long now = System.currentTimeMillis();
        if (pet.activity() == Activity.SLEEPING) {
            wake(pet, false);
        }
        switch (trick) {
            case SIT -> {
                if (partial) {
                    pet.forcedSitUntilMillis(now + 800L);
                } else {
                    pet.order(PetOrder.SIT);
                    pet.staying(false);
                }
            }
            case COME -> {
                pet.order(PetOrder.FOLLOW);
                pet.staying(false);
                pet.activity(Activity.NONE);
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
                    PetFx.jump(entity, partial);
                }
            }
            case SPIN -> {
                if (entity != null) {
                    spin(entity);
                }
            }
            case BEG -> {
                if (entity != null) {
                    PetFx.beg(entity);
                }
            }
            case PAW -> {
                if (entity != null) {
                    PetFx.look(entity, player.getEyeLocation());
                    PetFx.particle(entity, Particle.HEART, 2);
                }
            }
            default -> {
            }
        }
        PetFx.bar(player, PetTexts.reaction(pet.name(), pet.sex(), trick));
        PetTypeDef type = runtime.config().type(pet.typeId());
        if (entity != null && type != null) {
            runtime.visual().play(entity, type, trick.name());
        }
    }

    private void spin(Entity entity) {
        new BukkitRunnable() {
            private int steps;

            @Override
            public void run() {
                if (!entity.isValid() || steps++ >= 8) {
                    cancel();
                    return;
                }
                entity.setRotation(entity.getLocation().getYaw() + 45.0f, entity.getLocation().getPitch());
            }
        }.runTaskTimer(runtime.plugin(), 0L, 2L);
    }

    private void startPlay(Player player, Pet pet) {
        if (sick(pet)) {
            PetFx.bar(player, PetTexts.refusal(pet.name(), pet.sex(), "sick"));
            return;
        }
        if (pet.need(Need.ENERGY) < 25.0) {
            PetFx.bar(player, PetTexts.refusal(pet.name(), pet.sex(), "tired"));
            return;
        }
        releaseFetch(pet, player, true);
        pet.activity(Activity.PLAYING);
        pet.playUntilMillis(System.currentTimeMillis() + Math.round(runtime.config().care().playSeconds() * 1000.0));
        PetFx.bar(player, pet.name() + " juega");
    }

    private void toggleSleep(Player player, Pet pet) {
        if (pet.activity() == Activity.SLEEPING) {
            wake(pet, pet.need(Need.ENERGY) < 25.0);
            PetFx.bar(player, pet.name() + " se despierta");
            return;
        }
        if (pet.need(Need.ENERGY) >= 60.0 && pet.illness() != Illness.WEAKENED) {
            PetFx.bar(player, PetTexts.refusal(pet.name(), pet.sex(), "sleepy"));
            return;
        }
        releaseFetch(pet, player, true);
        pet.activity(Activity.SLEEPING);
        PetFx.bar(player, pet.name() + " se echa");
    }

    private void toggleSit(Player player, Pet pet) {
        if (pet.activity() == Activity.SLEEPING) {
            wake(pet, pet.need(Need.ENERGY) < 25.0);
        }
        if (pet.order() == PetOrder.SIT) {
            pet.order(PetOrder.FOLLOW);
            pet.staying(false);
            PetFx.bar(player, pet.name() + " te sigue");
        } else {
            pet.order(PetOrder.SIT);
            pet.staying(false);
            PetFx.bar(player, pet.name() + " se sienta");
        }
    }

    private void wake(Pet pet, boolean penalize) {
        pet.activity(Activity.NONE);
        if (penalize) {
            pet.need(Need.MOOD, pet.need(Need.MOOD) - runtime.config().care().wakeMoodPenalty());
        }
    }

    private void feed(Player player, Pet pet, Entity entity, ItemStack hand, double gain, boolean favorite) {
        if (!consumeHand(player, hand)) {
            return;
        }
        pet.need(Need.HUNGER, pet.need(Need.HUNGER) + gain);
        if (favorite) {
            pet.need(Need.MOOD, pet.need(Need.MOOD) + runtime.config().care().favoriteFoodMood());
        }
        comfort(pet, System.currentTimeMillis());
        if (entity != null) {
            PetFx.eat(entity);
            PetFx.hearts(entity, favorite ? 4 : 2);
        }
    }

    private void feedFromInventory(Player player, Pet pet, Entity entity) {
        PetTypeDef type = runtime.config().type(pet.typeId());
        if (type == null) {
            return;
        }
        ItemStack chosen = null;
        int slot = -1;
        for (int index = 0; index < player.getInventory().getSize(); index++) {
            ItemStack stack = player.getInventory().getItem(index);
            if (stack != null && type.foodGain(stack.getType()) != null) {
                chosen = stack;
                slot = index;
                if (stack.getType() == type.favoriteFood()) {
                    break;
                }
            }
        }
        if (chosen == null) {
            PetFx.bar(player, PetTexts.refusal(pet.name(), pet.sex(), "food"));
            return;
        }
        feed(player, pet, entity, chosen, type.foodGain(chosen.getType()), chosen.getType() == type.favoriteFood());
        if (slot >= 0 && chosen.getAmount() <= 0) {
            player.getInventory().setItem(slot, null);
        }
    }

    private void healFromInventory(Player player, Pet pet, Entity entity) {
        PetTypeDef type = runtime.config().type(pet.typeId());
        if (type == null) {
            return;
        }
        if (pet.illness() != Illness.SICK && pet.illness() != Illness.WEAKENED) {
            PetFx.bar(player, PetTexts.refusal(pet.name(), pet.sex(), "medicine"));
            return;
        }
        int slot = player.getInventory().first(type.medicine());
        if (slot < 0) {
            PetFx.bar(player, "No tienes la medicina");
            return;
        }
        ItemStack stack = player.getInventory().getItem(slot);
        if (!consumeHand(player, stack)) {
            return;
        }
        pet.treated(true);
        pet.need(Need.HEALTH, pet.need(Need.HEALTH) + runtime.config().care().medicineHealthBump());
        comfort(pet, System.currentTimeMillis());
        if (entity != null) {
            PetFx.hearts(entity, 3);
        }
        PetFx.bar(player, pet.name() + " empieza a recuperarse");
    }

    private void throwToy(Player player, ItemStack hand) {
        Material material = hand.getType();
        Pet pet = nearestToyPet(player, material);
        if (pet == null) {
            PetFx.bar(player, "No hay una mascota contigo que juegue con eso");
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
            PetFx.bar(player, "No he podido sacar a " + pet.name());
            return;
        }
        pet.stored(false);
        runtime.remember(pet, entity);
    }

    private void storePet(Player player, Pet pet) {
        if (pet.stored()) {
            return;
        }
        if (!Quota.canStore(runtime.store().countStored(player.getUniqueId()), runtime.config().limits().maxStored())) {
            PetFx.bar(player, PetTexts.refusal(pet.name(), pet.sex(), "full-stored"));
            return;
        }
        releaseFetch(pet, player, true);
        if (pet.carriedToy() != null) {
            dropPlain(PetRuntime.inFront(player), pet.carriedToy());
            pet.carriedToy(null);
        }
        Entity entity = runtime.entity(pet);
        if (entity != null) {
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
            world.getChunkAt(chunkX, chunkZ).load(true);
        }
        Entity entity = runtime.entity(pet);
        if (entity == null && pet.entityId() != null) {
            entity = Bukkit.getEntity(pet.entityId());
        }
        if (entity == null || !entity.isValid() || entity.isDead()) {
            PetFx.bar(player, "No encuentro a " + pet.name() + ". Guárdala para recuperarla.");
            return;
        }
        entity.teleport(PetRuntime.beside(player));
        runtime.remember(pet, entity);
        PetFx.bar(player, pet.name() + " viene");
    }

    private void placeKennel(Player player, ItemStack hand, Block clicked, BlockFace face) {
        Block place = clicked.getRelative(face);
        if (!place.getType().isAir() && !place.isReplaceable()) {
            PetFx.bar(player, "Ahí no cabe la caseta");
            return;
        }
        if (!consumeHand(player, hand)) {
            return;
        }
        place.setType(runtime.config().kennel());
        runtime.store().kennel(PetStore.kennelKey(place.getWorld().getName(), place.getX(), place.getY(), place.getZ()), player.getUniqueId());
        PetFx.bar(player, "Has colocado la caseta");
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
            }
        }
    }
}
