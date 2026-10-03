package net.tfminecraft.companionpets.staff;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Bukkit;
import net.tfminecraft.companionpets.config.PetTypeDef;
import net.tfminecraft.companionpets.item.ItemRef;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.runtime.PetRuntime;

public final class PetDiagnostics {
    private final PetRuntime runtime;
    public PetDiagnostics(PetRuntime runtime) { this.runtime = runtime; }

    public List<String> validate() {
        List<String> issues = new ArrayList<>();
        String furnitureIssue = net.tfminecraft.companionpets.listen.FurniturePetHouses.configurationIssue(runtime);
        if (furnitureIssue != null) issues.add(furnitureIssue);
        Set<ItemRef> items = new LinkedHashSet<>();
        if (runtime.config().kennel() != null) items.add(runtime.config().kennel());
        items.addAll(runtime.config().moments().digLoot().keySet());
        for (var type : runtime.config().types().values()) {
            items.add(type.egg());
            items.addAll(type.items().foods().keySet());
            items.addAll(type.treats());
            items.addAll(type.medicines());
            items.addAll(type.brushes());
            items.addAll(type.toys());
            if (!runtime.visual().modelAvailable(type)) issues.add(type.id() + ": model unavailable: " + type.appearance().model());
            if (type.appearance().modeled() && runtime.visual().modelAvailable(type)) {
                Set<String> clips = runtime.visual().clips(type);
                for (var pose : List.of(net.tfminecraft.companionpets.visual.PetAnimation.IDLE,
                        net.tfminecraft.companionpets.visual.PetAnimation.WALK)) {
                    var configured = type.appearance().animations().get(pose);
                    if (configured == null || !clips.contains(configured.name())) issues.add(type.id() + ": no configured " + pose + " clip");
                }
                for (var trick : runtime.config().tricks()) {
                    var definition = runtime.config().customTrick(trick);
                    if (definition != null && type.allowsTrick(trick) && !definition.animation().isBlank()
                            && !clips.contains(definition.animation()) && definition.fallbackText().isBlank()) {
                        issues.add(type.id() + ": unavailable custom animation " + definition.animation());
                    }
                }
            }
            if (type.mythicMob() != null && !net.tfminecraft.companionpets.integration.MythicSpawn.registered(type.mythicMob()))
                issues.add(type.id() + ": MythicMob unavailable: " + type.mythicMob());
        }
        for (ItemRef item : items) if (item.create() == null) issues.add("Item unavailable: " + item.configToken());
        for (Pet pet : runtime.store().all()) if (runtime.config().type(pet.typeId()) == null)
            issues.add("Pet " + pet.id() + ": undefined type " + pet.typeId());
        return List.copyOf(issues);
    }

    public List<String> explain(Pet pet) {
        List<String> reasons = new ArrayList<>();
        if (pet.stored()) reasons.add("In Pet House");
        if (runtime.entity(pet) == null && !pet.stored()) reasons.add("Body missing or its chunk is unloaded");
        if (pet.dead()) reasons.add("Dead; cannot be recovered");
        if (pet.illness() != net.tfminecraft.companionpets.pet.Illness.NONE) reasons.add("Illness: " + pet.illness());
        if (pet.need(Need.HUNGER) < 25) reasons.add("Too hungry to train");
        if (pet.need(Need.ENERGY) < 25) reasons.add("Too tired to train");
        if (runtime.sessions().resting(pet.id(), System.currentTimeMillis())) reasons.add("Resting between training sessions");
        return List.copyOf(reasons);
    }
}
