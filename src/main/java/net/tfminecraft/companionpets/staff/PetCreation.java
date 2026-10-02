package net.tfminecraft.companionpets.staff;

import java.util.*;
import net.tfminecraft.companionpets.pet.*;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.companionpets.text.Names;
import net.tfminecraft.companionpets.training.DefaultTricks;
import net.tfminecraft.companionpets.training.TrickAvailability;

/** Validate the entire replacement before adding a saved pet or creating a body. */
final class PetCreation {
    static final List<String> OPTIONS = List.of("type=", "name=", "tricks=", "hunger=", "mood=", "energy=", "cleanliness=", "health=", "bond=", "sex=", "personality=", "agehours=");

    static Pet parse(PetRuntime runtime, UUID owner, String[] args) {
        StaffCommands.require(args.length >= 3, usage());
        Map<String, String> options = readOptions(args, false);
        String typeId = options.get("type");
        StaffCommands.require(typeId != null && !typeId.isBlank(), "Choose the pet type with type=<type>. Example: /companionpets create " + args[1] + " type=beagle name=Toby");
        var type = runtime.config().type(typeId);
        StaffCommands.require(type != null, "Unknown configured pet type: " + typeId + ". Use Tab after type=.");
        String name = Names.sanitize(options.getOrDefault("name", "").replace("\"", ""));
        StaffCommands.require(!name.isBlank(), "Add name=<pet name>. Example: name=Toby");
        PetSex sex = Names.sex(options.getOrDefault("sex", "male"));
        StaffCommands.require(sex != null, "sex must be male or female.");
        var pet = new Pet(UUID.randomUUID(), owner, type.id(), name, sex);
        pet.stored(true);
        pet.bornAt(System.currentTimeMillis());
        DefaultTricks.apply(runtime.config(), pet);
        for (Need need : Need.values()) {
            String key = need.name().toLowerCase(Locale.ROOT);
            if (options.containsKey(key)) pet.need(need, number(options, key, 100));
        }
        if (options.containsKey("bond")) pet.bond(number(options, "bond", 100));
        if (options.containsKey("agehours")) pet.bornAt(Math.max(0, System.currentTimeMillis() - Math.round(number(options, "agehours", 876_000) * 3_600_000)));
        if (options.containsKey("personality")) {
            try { pet.personality(PetPersonality.valueOf(options.get("personality").toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException ex) { throw new IllegalArgumentException("personality must be friendly, playful, shy, territorial or grumpy."); }
        }
        String tricks = options.getOrDefault("tricks", "none");
        List<String> selected = tricks.equalsIgnoreCase("all")
                ? compatible(runtime, type.id()).stream().map(t -> t.name().toLowerCase(Locale.ROOT)).toList()
                : tricks.equalsIgnoreCase("none") ? List.of() : Arrays.asList(tricks.split(",", -1));
        for (String token : selected) {
            String[] parts = token.split(":", 2);
            Trick trick = Trick.valueOf(parts[0]);
            StaffCommands.require(TrickAvailability.allows(runtime, pet, trick), "Unavailable trick for " + type.id() + ": " + parts[0]);
            String word = Names.sanitize(parts.length == 2 ? parts[1] : trick.name().toLowerCase(Locale.ROOT));
            StaffCommands.require(!word.isBlank(), "A trick word cannot be empty.");
            var previous = pet.trickFor(word);
            StaffCommands.require(previous == null || previous.equals(trick), "Word already assigned to another trick: " + word);
            pet.progress(trick, 100); pet.bindWord(word, trick);
        }
        pet.favoriteToy(net.tfminecraft.companionpets.play.FavoriteToy.reconcile(null,
                net.tfminecraft.companionpets.runtime.PetActions.toyNames(type), runtime.random()).toy());
        return pet;
    }

    static String usage() {
        return "Usage: /companionpets create <player> type=<type> name=<name> [tricks=all|none|follow,sit] [hunger=100 ...]. Use Tab after each space or '='.";
    }

    /** Keep the previous positional syntax accepted, but complete named parameters. */
    private static Map<String, String> readOptions(String[] args, boolean partial) {
        Map<String, String> result = new LinkedHashMap<>();
        int start = 2;
        if (args.length > 2 && !args[2].contains("=") && !args[2].isBlank()) {
            result.put("type", args[2]);
            start = 3;
            int end = start;
            while (end < args.length && !args[end].contains("=")) end++;
            if (end > start) result.put("name", String.join(" ", Arrays.copyOfRange(args, start, end)).trim());
            start = end;
        }
        for (int index = start; index < args.length; index++) {
            if (args[index].isBlank()) continue;
            String[] pair = args[index].split("=", 2);
            StaffCommands.require(pair.length == 2, "Expected parameter=value: " + args[index] + ". " + usage());
            String key = pair[0].toLowerCase(Locale.ROOT);
            StaffCommands.require(OPTIONS.contains(key + "="), "Unknown creation parameter: " + key);
            String value = pair[1];
            if (key.equals("name")) {
                while (index + 1 < args.length && !args[index + 1].contains("=")) value += " " + args[++index];
                value = value.trim();
            }
            StaffCommands.require(partial || !value.isBlank(), "Add a value after " + key + "=.");
            StaffCommands.require(result.putIfAbsent(key, value) == null, "Repeated creation parameter: " + key);
        }
        return result;
    }

    private static List<Trick> compatible(PetRuntime runtime, String type) {
        if (type == null || runtime.config().type(type) == null) return List.of();
        Pet pet = new Pet(UUID.randomUUID(), UUID.randomUUID(), type, "Preview", PetSex.MALE);
        return runtime.config().tricks().stream().filter(t -> TrickAvailability.allows(runtime, pet, t)).toList();
    }

    static List<String> complete(PetRuntime runtime, String[] args) {
        if (args.length < 3) return List.of();
        String current = args[args.length - 1];
        Map<String, String> previous;
        try { previous = readOptions(Arrays.copyOf(args, args.length - 1), true); }
        catch (IllegalArgumentException ex) { return List.of(); }
        List<String> choices = new ArrayList<>();
        if (!current.contains("=")) {
            for (String option : OPTIONS) {
                String key = option.substring(0, option.length() - 1);
                if (!previous.containsKey(key)) choices.addAll(values(runtime, previous.get("type"), key, ""));
            }
            // The previous type-only form remains discoverable if explicitly typed.
            if (args.length == 3 && !current.isBlank() && !current.contains("=")) choices.addAll(runtime.config().types().keySet());
        } else {
            String[] pair = current.split("=", 2);
            String key = pair[0].toLowerCase(Locale.ROOT);
            if (!previous.containsKey(key)) choices.addAll(values(runtime, previous.get("type"), key, pair[1]));
        }
        return StaffCommands.filter(choices, current);
    }

    private static List<String> values(PetRuntime runtime, String type, String key, String current) {
        List<String> values = switch (key) {
            case "type" -> runtime.config().types().keySet().stream().toList();
            case "name" -> List.of("", "Toby", "Luna");
            case "sex" -> List.of("male", "female");
            case "personality" -> Arrays.stream(PetPersonality.values()).map(p -> p.name().toLowerCase(Locale.ROOT)).toList();
            case "agehours" -> List.of("0", "24", "48", "168");
            case "hunger", "mood", "energy", "cleanliness", "health", "bond" -> List.of("0", "25", "50", "75", "100");
            case "tricks" -> {
                String beginning = current.contains(",") ? current.substring(0, current.lastIndexOf(',') + 1) : "";
                Set<String> used = new HashSet<>();
                for (String token : beginning.split(",")) used.add(token.split(":")[0].toLowerCase(Locale.ROOT));
                List<String> tricks = new ArrayList<>();
                if (beginning.isEmpty()) tricks.addAll(List.of("all", "none"));
                compatible(runtime, type).stream().map(t -> t.name().toLowerCase(Locale.ROOT))
                        .filter(t -> !used.contains(t)).forEach(t -> tricks.add(beginning + t));
                yield tricks;
            }
            default -> List.of();
        };
        return values.stream().map(value -> key + "=" + value).toList();
    }
    private static double number(Map<String, String> options, String key, double max) {
        double value;
        try { value = Double.parseDouble(options.get(key)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(key + " must be a number."); }
        StaffCommands.require(Double.isFinite(value) && value >= 0 && value <= max, key + " must be between 0 and " + max + ".");
        return value;
    }
}
