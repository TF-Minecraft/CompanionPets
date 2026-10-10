package net.tfminecraft.companionpets.runtime;

import java.util.Locale;
import java.util.UUID;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;


import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.management.Quota;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetSex;
import net.tfminecraft.companionpets.pet.SexMode;
import net.tfminecraft.companionpets.play.FavoriteToy;
import net.tfminecraft.companionpets.session.HatchPrompt;
import net.tfminecraft.companionpets.text.Names;
import net.tfminecraft.companionpets.text.PetTexts;

final class PetHatching {
    private static final long PROMPT_MILLIS = 60_000L;
    private static final String NOT_ALLOWED = "You don't know how to hatch this egg";
    private final PetRuntime runtime;
    PetHatching(PetRuntime runtime) { this.runtime = runtime; }
    void begin(Player player, PetTypeDef type) {
        if (!type.canHatch(player)) {
            PetFx.bar(player, NOT_ALLOWED);
            return;
        }
        if (!Quota.canAdopt(runtime.store().countPets(player.getUniqueId()), runtime.config().limits().maxPets())) {
            PetFx.bar(player, PetTexts.refusal("", PetSex.FEMALE, "full-total"));
            return;
        }
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

    boolean chat(Player player, String text, long now) {
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
        // Permissions can be revoked or the config reloaded while the player answers in chat.
        if (!type.canHatch(player)) {
            PetFx.tell(player, NOT_ALLOWED + ". Your egg is safe.");
            return;
        }
        if (!Quota.canAdopt(runtime.store().countPets(player.getUniqueId()), runtime.config().limits().maxPets())) {
            PetFx.tell(player, PetTexts.refusal(name, sex, "full-total"));
            return;
        }
        if (!Quota.canBringOut(runtime.store().countOut(player.getUniqueId()), runtime.config().limits().maxOut())) {
            PetFx.tell(player, PetTexts.refusal(name, sex, "full-out"));
            return;
        }
        Pet pet = new Pet(UUID.randomUUID(), player.getUniqueId(), type.id(), name, sex);
        net.tfminecraft.companionpets.training.DefaultTricks.apply(runtime.config(), pet);
        pet.bornAt(System.currentTimeMillis());
        FavoriteToy.Result favorite = FavoriteToy.reconcile(null, PetActions.toyNames(type), runtime.random());
        pet.favoriteToy(favorite.toy());
        // Use the egg before placing the pet: teleport listeners run during the spawn and may move the held stack.
        if (!net.tfminecraft.companionpets.item.HandItems.consume(player, hand)) return;
        Entity entity = runtime.bodies().spawn(pet, type, PetRuntime.beside(player), player);
        runtime.store().add(pet);
        PetFx.tell(player, name + " has hatched! Welcome to the family.");
        if (!type.defaultTricks().isEmpty()) PetFx.tell(player, "Already learned: "
                + type.defaultTricks().stream().map(t -> net.tfminecraft.companionpets.training.TrickAvailability.name(runtime, t)).collect(java.util.stream.Collectors.joining(", "))
                + ". Open the Tricks page to see the command words.");
        if (entity == null) {
            pet.stored(true);
            PetFx.tell(player, "There was no room out here, so " + name + " is waiting for you in the Pet House.");
        } else {
            pet.stored(false);
            runtime.remember(pet, entity);
        }
        runtime.store().requestSave();
    }

}
