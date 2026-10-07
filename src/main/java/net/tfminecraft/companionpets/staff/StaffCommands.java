package net.tfminecraft.companionpets.staff;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.runtime.PetActions;
import net.tfminecraft.companionpets.runtime.PetRuntime;

/** Staff commands select owners and pets by name; inventories retain identity internally. */
public final class StaffCommands {
    public static final List<String> ACTIONS = List.of("reload", "list", "find", "create", "egg");
    private final PetRuntime runtime;
    private final StaffAudit audit;
    private final PetDiagnostics diagnostics;
    private final StaffMenus menus;

    public StaffCommands(PetRuntime runtime, PetActions actions) {
        this.runtime = runtime;
        audit = new StaffAudit(runtime); diagnostics = new PetDiagnostics(runtime);
        menus = new StaffMenus(runtime, this);
    }
    public StaffMenus menus() { return menus; }
    public PetDiagnostics diagnostics() { return diagnostics; }

    public static String permission(String action) {
        return switch (action) {
            case "reload" -> "companionpets.reload";
            case "find" -> "companionpets.admin.locate";
            case "egg" -> "companionpets.admin.giveegg";
            default -> "companionpets.admin." + action;
        };
    }

    public boolean execute(CommandSender sender, String... args) {
        try {
            require(args.length > 0 && ACTIONS.contains(args[0].toLowerCase(Locale.ROOT)),
                    "Use /companionpets to see the available staff commands.");
            String action = args[0].toLowerCase(Locale.ROOT);
            require(sender.hasPermission(permission(action)), "You do not have permission to use " + action + ".");
            switch (action) {
                case "list" -> list(sender, args);
                case "find" -> find(sender, args);
                case "create" -> create(sender, args);
                case "egg" -> giveEgg(sender, args);
            }
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(ex.getMessage() == null ? "Invalid command arguments." : ex.getMessage());
        }
        return true;
    }

    public UUID resolveOwner(String token) {
        Player online = Bukkit.getPlayerExact(token);
        if (online != null) return online.getUniqueId();
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(token);
        if (cached != null) return cached.getUniqueId();
        // Explicit UUIDs remain available only for restoring unknown/offline owners with create.
        try { return UUID.fromString(token); } catch (IllegalArgumentException ignored) { }
        throw new IllegalArgumentException("Unknown player '" + token + "'. Use Tab to select a player.");
    }

    public String ownerName(UUID owner) {
        var player = Bukkit.getOfflinePlayer(owner);
        return player.getName() == null ? "Unknown player" : player.getName();
    }

    public List<UUID> owners() {
        return runtime.store().all().stream().map(Pet::ownerId).distinct()
                .sorted(Comparator.comparing(this::ownerName, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public List<Pet> owned(UUID owner) {
        return runtime.store().of(owner).stream().sorted(Comparator.comparing((Pet p) -> p.name(), String.CASE_INSENSITIVE_ORDER).thenComparing(Pet::id)).toList();
    }

    public Pet resolveNamedPet(String player, String name) {
        var matches = owned(resolveOwner(player)).stream().filter(p -> p.name().equalsIgnoreCase(name)).toList();
        require(!matches.isEmpty(), "No pet named '" + name + "' belongs to " + player + ". Use /companionpets list " + player + ".");
        require(matches.size() == 1, "Several pets have that name. Open /companionpets list " + player + " and select the pet there.");
        return matches.getFirst();
    }

    private void list(CommandSender sender, String[] args) {
        if (args.length == 1) {
            sender.sendMessage("Usage: /companionpets list <player> [pet name]. Use Tab to select a player.");
            return;
        }
        UUID owner = resolveOwner(args[1]);
        if (args.length > 2) {
            Pet pet = resolveNamedPet(args[1], String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
            if (sender instanceof Player player) menus.inspect(player, pet);
            else info(sender, pet);
        } else if (sender instanceof Player player) menus.list(player, owner, 0);
        else {
            sender.sendMessage("Pets belonging to " + ownerName(owner) + ":");
            owned(owner).forEach(p -> sender.sendMessage(p.name() + " (" + p.typeId() + ") - " + (p.stored() ? "in Pet House" : "outside")));
        }
    }

    private void find(CommandSender sender, String[] args) {
        if (args.length == 1) {
            sender.sendMessage("Usage: /companionpets find <player> [pet name]. Example: /companionpets find Nowko Toby");
            return;
        }
        UUID owner = resolveOwner(args[1]);
        String name = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";
        var results = PetSearch.find(runtime, owner, null, name);
        sender.sendMessage("Pets belonging to " + ownerName(owner) + ":");
        if (results.isEmpty()) sender.sendMessage("No matching pets. Use /companionpets list " + ownerName(owner) + ".");
        for (var result : results) {
            Component text = Component.text(result.description(), NamedTextColor.YELLOW);
            if (result.pet() != null)
                text = text.clickEvent(ClickEvent.suggestCommand("/companionpets list " + ownerName(owner) + " " + result.pet().name()));
            sender.sendMessage(text);
        }
    }

    public void info(CommandSender sender, Pet pet) {
        sender.sendMessage(pet.name() + " (" + pet.typeId() + "), owner: " + ownerName(pet.ownerId()));
        sender.sendMessage("State: " + (pet.stored() ? "in Pet House" : "outside") + "; order: " + pet.order() + "; illness: " + pet.illness());
        sender.sendMessage("Needs: " + Arrays.stream(Need.values()).map(n -> n.name().toLowerCase(Locale.ROOT) + "=" + Math.round(pet.need(n))).toList());
        sender.sendMessage("Tricks: " + pet.words().keySet());
    }

    private void create(CommandSender sender, String[] args) {
        require(args.length >= 3, PetCreation.usage());
        require(runtime.store().canRestoreBodies(), "Pet persistence is unavailable; creation refused.");
        UUID owner = resolveOwner(args[1]);
        Pet pet = PetCreation.parse(runtime, owner, args);
        require(runtime.store().countStored(owner) < runtime.config().limits().maxStored(), "The player's Pet House is full.");
        if (!audit.append(sender, "create-requested", pet, "none", StaffAudit.snapshot(pet))) return;
        runtime.store().add(pet);
        if (!runtime.store().save()) {
            boolean removed = runtime.store().remove(pet.id());
            if (removed) runtime.store().save();
            audit.append(sender, "create-save-failed", pet, "none", "rollback=" + removed);
            sender.sendMessage("Creation could not be saved; no body was spawned. Check the server log.");
            return;
        }
        audit.append(sender, "create", pet, "none", StaffAudit.snapshot(pet));
        sender.sendMessage("Created " + pet.name() + " (" + pet.typeId() + ") in " + ownerName(owner) + "'s Pet House.");
    }

    private void giveEgg(CommandSender sender, String[] args) {
        require(args.length >= 2 && args.length <= 4, "Usage: /companionpets egg <type|all> [online-player] [1..64]");
        Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : sender instanceof Player player ? player : null;
        require(target != null, "Specify an online player; a staff player can omit the name to receive the eggs.");
        int amount;
        try {
            amount = args.length == 4 ? Integer.parseInt(args[3]) : 1;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Egg amount must be between 1 and 64.");
        }
        require(amount >= 1 && amount <= 64, "Egg amount must be between 1 and 64.");
        var selected = args[1].equalsIgnoreCase("all") ? new ArrayList<>(runtime.config().types().values())
                : runtime.config().type(args[1]) == null ? List.<net.tfminecraft.companionpets.config.PetTypeDef>of() : List.of(runtime.config().type(args[1]));
        require(!selected.isEmpty(), "Unknown configured pet type: " + args[1]);
        var prepared = new LinkedHashMap<net.tfminecraft.companionpets.config.PetTypeDef, org.bukkit.inventory.ItemStack>();
        for (var type : selected) {
            var item = type.egg().create();
            require(item != null, "Egg unavailable for " + type.id() + "; check its provider and ID.");
            if (type.egg().kind() == net.tfminecraft.companionpets.item.ItemRef.Kind.MMOITEMS)
                require(Bukkit.getPluginCommand("mmoitems:mi") != null, "The MMOItems give command is unavailable.");
            if (type.eggCustomModelData() != null) {
                var meta = item.getItemMeta(); meta.setCustomModelData(type.eggCustomModelData()); item.setItemMeta(meta);
            }
            prepared.put(type, item);
        }
        if (!audit.append(sender, "giveegg-requested", null, "none", target.getUniqueId() + ":" + args[1] + ":" + amount)) return;
        int delivered = 0;
        for (var entry : prepared.entrySet()) {
            boolean success;
            try {
                success = EggDelivery.deliver(entry.getKey().egg(), entry.getValue(), target, amount);
            } catch (org.bukkit.command.CommandException ex) {
                runtime.plugin().getLogger().log(java.util.logging.Level.SEVERE,
                        "Could not deliver eggs for " + entry.getKey().id(), ex);
                success = false;
            }
            if (!success) {
                audit.append(sender, "giveegg-failed", null, "requested", "delivered=" + delivered + ";failed=" + entry.getKey().id());
                sender.sendMessage("Egg delivery failed for " + entry.getKey().id() + ". Already delivered " + delivered + " types; check the server log.");
                return;
            }
            delivered++;
        }
        audit.append(sender, "giveegg", null, "requested", target.getUniqueId() + ":types=" + delivered + ":each=" + amount);
        sender.sendMessage("Gave " + amount + " egg(s) for " + delivered + " configured type(s) to " + target.getName() + ".");
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) return filter(ACTIONS.stream().filter(a -> sender.hasPermission(permission(a))).toList(), args[0]);
        if (args.length < 2 || !sender.hasPermission(permission(args[0].toLowerCase(Locale.ROOT)))) return List.of();
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("create") && args.length >= 3) return PetCreation.complete(runtime, args);
        List<String> choices = new ArrayList<>();
        if (args.length == 2) {
            if (action.equals("egg")) { choices.add("all"); choices.addAll(runtime.config().types().keySet()); }
            else if (List.of("list", "find", "create").contains(action)) {
                owners().forEach(id -> { if (!ownerName(id).equals("Unknown player")) choices.add(ownerName(id)); });
                if (action.equals("create")) {
                    Bukkit.getOnlinePlayers().forEach(p -> choices.add(p.getName()));
                }
            }
        } else if (action.equals("egg")) {
            if (args.length == 3) Bukkit.getOnlinePlayers().forEach(p -> choices.add(p.getName()));
            if (args.length == 4) choices.addAll(List.of("1", "16", "64"));
        } else if (List.of("list", "find").contains(action)) {
            try {
                String before = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length - 1)) + " " : "";
                for (Pet pet : owned(resolveOwner(args[1]))) {
                    if (pet.name().regionMatches(true, 0, before, 0, before.length()))
                        choices.add(pet.name().substring(before.length()));
                }
            } catch (IllegalArgumentException ignored) { }
        }
        return filter(choices, args[args.length - 1]);
    }

    public static List<String> filter(Collection<String> values, String prefix) {
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
    public static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
