package net.tfminecraft.companionpets.runtime;


import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;


import net.tfminecraft.companionpets.care.Feeding;
import net.tfminecraft.companionpets.care.HealthRecovery;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.text.PetTexts;

final class PetCareActions {
    private final PetRuntime runtime;
    PetCareActions(PetRuntime runtime) { this.runtime = runtime; }
    private void comfort(Pet pet, long now) { pet.nextCryAtMillis(now + Math.round(runtime.config().cryIntervalSeconds() * 1000)); }
    boolean groom(Player player, Pet pet, Entity entity, ItemStack hand, PetTypeDef type, long now) {
        if (type.items().isMedicine(hand) && (pet.illness() == Illness.SICK || pet.illness() == Illness.WEAKENED)) {
            if (!net.tfminecraft.companionpets.item.HandItems.consume(player, hand)) {
                return true;
            }
            pet.treated(true);
            runtime.recordCare(player, pet, 8, now);
            pet.need(Need.HEALTH, pet.need(Need.HEALTH) + runtime.config().care().medicineHealthBump());
            comfort(pet, now);
            PetFx.hearts(entity, 3);
            PetFx.bar(player, pet.name() + " is already starting to feel better");
            return true;
        }
        if (type.items().isBrush(hand)) {
            HealthRecovery.improve(pet, Need.CLEANLINESS, 100);
            runtime.recordCare(player, pet, 6, now);
            comfort(pet, now);
            PetFx.hearts(entity, 2);
            PetFx.bar(player, pet.name() + "'s coat is clean and shiny again");
            return true;
        }
        return false;
    }
    boolean feed(Player player, Pet pet, Entity entity, ItemStack hand, double gain, boolean favorite) {
        if (!net.tfminecraft.companionpets.item.HandItems.consume(player, hand)) {
            return true;
        }
        if (Feeding.outcome(pet.need(Need.HUNGER)) == Feeding.Outcome.OVERATE) {
            pet.need(Need.MOOD, pet.need(Need.MOOD) - runtime.config().care().overfeedMoodPenalty());
            pet.need(Need.HEALTH, pet.need(Need.HEALTH) - runtime.config().care().overfeedHealthPenalty());
            if (entity != null) {
                runtime.voice().eat(entity);
                runtime.visual().play(entity, runtime.config().type(pet.typeId()), "EAT");
                runtime.voice().sad(entity);
                PetFx.particle(entity, Particle.SMOKE, 4);
            }
            PetFx.bar(player, PetTexts.overfed(pet.name(), pet.sex()));
            return true;
        }
        HealthRecovery.improve(pet, Need.HUNGER, pet.need(Need.HUNGER) + gain);
        runtime.recordCare(player, pet, 4, System.currentTimeMillis());
        if (favorite) {
            pet.need(Need.MOOD, pet.need(Need.MOOD) + runtime.config().care().favoriteFoodMood());
        }
        comfort(pet, System.currentTimeMillis());
        if (entity != null) {
            runtime.voice().eat(entity);
            runtime.visual().play(entity, runtime.config().type(pet.typeId()), "EAT");
            PetFx.hearts(entity, favorite ? 4 : 2);
        }
        return false;
    }


}
