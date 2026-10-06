package net.tfminecraft.companionpets.staff;

import java.util.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.runtime.PetActions;
import net.tfminecraft.companionpets.runtime.PetRuntime;

/** Staff shortcuts for a normal pet with learned tricks and its spontaneous moments. */
public final class TestCommands {
    private final PetRuntime runtime;
    private final PetActions actions;
    private final StaffAudit audit;
    public TestCommands(PetRuntime runtime, PetActions actions, StaffCommands staff) {
        this.runtime = runtime; this.actions = actions; audit = new StaffAudit(runtime);
    }

    public boolean execute(CommandSender sender, String... args) {
        try {
            StaffCommands.require(sender.hasPermission("companionpets.test"), "You do not have permission to use these staff commands.");
            StaffCommands.require(args.length > 0 && List.of("spawn", "moment").contains(args[0].toLowerCase(Locale.ROOT)),
                    "Use /companionpets testpet <type> [name] or /companionpets moment <kind>.");
            StaffCommands.require(sender instanceof Player, "This command can only be used in game.");
            Player player = (Player) sender;
            if (args[0].equalsIgnoreCase("spawn")) {
                StaffCommands.require(args.length >= 2, "Usage: /companionpets testpet <configured-type> [name]");
                if (!audit.append(sender, "spawn-learned-requested", null, "none", args[1])) return true;
                Set<UUID> before = new HashSet<>(); runtime.store().all().forEach(p -> before.add(p.id()));
                if (actions.spawnTestPet(player, args[1], args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : ""))
                    runtime.store().all().stream().filter(p -> !before.contains(p.id()))
                            .forEach(p -> audit.append(sender, "spawn-learned", p, "none", StaffAudit.snapshot(p)));
            } else {
                StaffCommands.require(args.length == 2 && List.of("affection", "bark", "mischief", "dig", "belly", "greeting", "pet-greeting").contains(args[1].toLowerCase(Locale.ROOT)),
                        "Usage: /companionpets moment <affection|bark|mischief|dig|belly|greeting|pet-greeting>, while looking at your pet.");
                Pet pet = runtime.byEntity(PetActions.lookingAt(player, 6));
                StaffCommands.require(pet != null && pet.ownerId().equals(player.getUniqueId()), "Look at one of your pets first.");
                if (!audit.append(sender, "moment-requested", pet, StaffAudit.snapshot(pet), args[1])) return true;
                if (actions.triggerTestMoment(player, args[1])) audit.append(sender, "moment", pet, "requested", StaffAudit.snapshot(pet));
            }
        } catch (IllegalArgumentException ex) { sender.sendMessage(ex.getMessage()); }
        return true;
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("companionpets.test") || args.length != 2) return List.of();
        if (args[0].equalsIgnoreCase("spawn")) return StaffCommands.filter(runtime.config().types().keySet(), args[1]);
        if (args[0].equalsIgnoreCase("moment")) return StaffCommands.filter(List.of("affection", "bark", "mischief", "dig", "belly", "greeting", "pet-greeting"), args[1]);
        return List.of();
    }
}
