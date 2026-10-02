package net.tfminecraft.companionpets.runtime;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import net.tfminecraft.companionpets.item.ItemRef;
import net.tfminecraft.companionpets.item.ToyItems;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;


import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.pet.Activity;
import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.play.FetchJob;
import net.tfminecraft.companionpets.play.ThrowSpeed;
import net.tfminecraft.companionpets.text.PetTexts;

final class PetFetchActions {
    private final PetRuntime runtime;
    PetFetchActions(PetRuntime runtime) { this.runtime = runtime; }
    private static boolean sick(Pet pet) { return pet.illness() == Illness.SICK || pet.illness() == Illness.WEAKENED; }
    private void remove(UUID id) {
        if (id == null) return;
        Entity entity = Bukkit.getEntity(id);
        if (entity != null) entity.remove();
    }
    void throwToy(Player player, ItemStack hand) {
        Pet pet = nearestToyPet(player, hand);
        if (pet == null) {
            PetFx.bar(player, "None of your pets nearby wants to play with that");
            return;
        }
        if (sick(pet) || pet.illness() == Illness.WEAKENED || pet.need(Need.ENERGY) < 25.0) {
            PetFx.bar(player, PetTexts.refusal(pet.name(), pet.sex(), sick(pet) || pet.illness() == Illness.WEAKENED ? "sick" : "tired"));
            return;
        }
        ItemStack thrown = hand.clone();
        thrown.setAmount(1);
        String saved = ToyItems.encode(thrown);
        PetTypeDef type = runtime.config().type(pet.typeId());
        ItemRef toy = type == null ? null : type.toy(thrown);
        boolean favorite = toy != null && toy.key().equals(pet.favoriteToy());
        releaseFetch(pet, player, true);
        if (!net.tfminecraft.companionpets.item.HandItems.consume(player, hand)) {
            return;
        }
        FetchJob job = new FetchJob(saved, favorite);
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
                snowball.setItem(thrown.clone());
                snowball.setVelocity(direction.multiply(speed));
                snowball.getPersistentDataContainer().set(
                        runtime.toyKey(),
                        PersistentDataType.STRING,
                        pet.id() + "|" + saved);
            });
        } catch (RuntimeException ex) {
            if (player.getGameMode() != GameMode.CREATIVE) {
                player.getInventory().addItem(thrown).values().forEach(leftover ->
                        player.getWorld().dropItem(player.getLocation(), leftover));
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
        ItemStack stack = restoreToy(toy);
        if (stack == null) return;
        location.getWorld().dropItem(location, stack).setPickupDelay(0);
    }

    public void dropToy(Location location, Pet pet, String toy) {
        if (location.getWorld() == null) {
            return;
        }
        ItemStack stack = restoreToy(toy);
        if (stack == null) return;
        org.bukkit.entity.Item item = location.getWorld().dropItem(location, stack);
        item.setPickupDelay(Integer.MAX_VALUE);
        item.getPersistentDataContainer().set(runtime.toyKey(), PersistentDataType.STRING, pet.id().toString());
        FetchJob job = pet.fetch();
        if (job != null) {
            job.itemId(item.getUniqueId());
        }
    }

    private ItemStack restoreToy(String saved) {
        try { return ToyItems.decode(saved); }
        catch (RuntimeException ex) {
            Bukkit.getLogger().warning("[CompanionPets] Could not restore saved toy: " + ex.getMessage());
            return null;
        }
    }

    private Pet nearestToyPet(Player player, ItemStack item) {
        Pet best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Pet pet : runtime.store().of(player.getUniqueId())) {
            if (pet.stored() || pet.dead()) {
                continue;
            }
            PetTypeDef type = runtime.config().type(pet.typeId());
            if (type == null || !type.acceptsToy(item)) {
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


}
