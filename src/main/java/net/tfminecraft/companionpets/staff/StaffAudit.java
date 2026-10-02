package net.tfminecraft.companionpets.staff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.runtime.PetRuntime;

/** Append-only administrative history; an unavailable log refuses new interventions. */
public final class StaffAudit {
    private final PetRuntime runtime;
    public StaffAudit(PetRuntime runtime) { this.runtime = runtime; }

    public boolean append(CommandSender actor, String action, Pet pet, String before, String after) {
        var yaml = new YamlConfiguration();
        yaml.set("time", Instant.now().toString());
        yaml.set("actor", actor.getName());
        yaml.set("actor-id", actor instanceof Player player ? player.getUniqueId().toString() : "console");
        yaml.set("action", action);
        yaml.set("pet", pet == null ? null : pet.id().toString());
        yaml.set("before", before);
        yaml.set("after", after);
        Path file = runtime.plugin().getDataFolder().toPath().resolve("staff-audit.yml.log");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "---\n" + yaml.saveToString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException ex) {
            runtime.plugin().getLogger().log(java.util.logging.Level.SEVERE, "Could not append staff audit", ex);
            actor.sendMessage("The staff audit could not be written. Check the server log.");
            return false;
        }
    }

    public static String snapshot(Pet pet) {
        return "owner=" + pet.ownerId() + ";type=" + pet.typeId() + ";name=" + pet.name()
                + ";stored=" + pet.stored() + ";entity=" + pet.entityId() + ";illness=" + pet.illness()
                + ";treated=" + pet.treated() + ";order=" + pet.order() + ";activity=" + pet.activity()
                + ";needs=" + java.util.Arrays.stream(Need.values()).map(n -> n + "=" + pet.need(n)).toList()
                + ";sex=" + pet.sex() + ";personality=" + pet.personality() + ";bond=" + pet.bond() + ";born-at=" + pet.bornAt()
                + ";progress=" + pet.progressView() + ";words=" + pet.words();
    }
}
