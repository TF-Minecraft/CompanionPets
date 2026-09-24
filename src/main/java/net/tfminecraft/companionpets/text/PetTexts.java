package net.tfminecraft.companionpets.text;

import java.util.Locale;

import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetSex;
import net.tfminecraft.companionpets.pet.Trick;

public final class PetTexts {
    private PetTexts() {
    }

    public static String he(PetSex sex) {
        return sex == PetSex.FEMALE ? "she" : "he";
    }

    public static String He(PetSex sex) {
        return sex == PetSex.FEMALE ? "She" : "He";
    }

    public static String him(PetSex sex) {
        return sex == PetSex.FEMALE ? "her" : "him";
    }

    public static String his(PetSex sex) {
        return sex == PetSex.FEMALE ? "her" : "his";
    }

    public static String sexName(PetSex sex) {
        return sex == PetSex.FEMALE ? "Female" : "Male";
    }

    public static String speciesName(String typeId) {
        if (typeId == null || typeId.isBlank()) {
            return "Pet";
        }
        String clean = typeId.replace('_', ' ').replace('-', ' ').trim();
        return clean.substring(0, 1).toUpperCase(Locale.ROOT) + clean.substring(1).toLowerCase(Locale.ROOT);
    }

    public static String itemName(String material) {
        if (material == null || material.isBlank()) {
            return "nothing";
        }
        return material.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    public static String needName(Need need) {
        return switch (need) {
            case HUNGER -> "Hunger";
            case MOOD -> "Mood";
            case ENERGY -> "Energy";
            case CLEANLINESS -> "Cleanliness";
            case HEALTH -> "Health";
        };
    }

    public static String lowNeed(String name, PetSex sex, Need need) {
        return switch (need) {
            case HUNGER -> name + "'s stomach is rumbling";
            case MOOD -> name + " is craving some attention";
            case ENERGY -> name + " is worn out and needs a rest";
            case CLEANLINESS -> name + " could really use a good brushing";
            case HEALTH -> name + " isn't looking well";
        };
    }

    public static String illness(String name, PetSex sex, Illness illness) {
        return switch (illness) {
            case UNWELL -> name + " seems a little under the weather";
            case SICK -> name + " is sick and needs medicine";
            case WEAKENED -> name + " is too weak to stand";
            case NONE -> name;
        };
    }

    public static String petted(String name, PetSex sex, String typeId, boolean devoted) {
        String reaction = switch (typeId == null ? "" : typeId.toLowerCase(Locale.ROOT)) {
            case "wolf" -> devoted ? " rolls over for belly rubs, tail wagging wildly" : " wags " + his(sex) + " tail happily";
            case "cat" -> devoted ? " purrs loudly and headbutts your hand" : " purrs and rubs against your hand";
            case "fox" -> devoted ? " yips with joy and nuzzles into your hand" : " nuzzles your hand with a happy chirp";
            default -> " leans happily into your hand";
        };
        return "You pet " + name + ". " + He(sex) + reaction;
    }

    public static String illnessCheck(String name, PetSex sex, Illness illness, String medicine) {
        return switch (illness) {
            case UNWELL -> name + " feels warm and sluggish. Look after " + his(sex) + " needs and " + he(sex) + "'ll perk up";
            case SICK -> name + " whimpers weakly. " + He(sex) + " is sick and needs " + medicine;
            case WEAKENED -> name + " is too weak to stand. " + He(sex) + " needs " + medicine + " and care right away";
            case NONE -> name + " seems fine";
        };
    }

    public static String needCheck(String name, PetSex sex, Need need, boolean critical) {
        return switch (need) {
            case HUNGER -> critical
                    ? name + "'s stomach growls loudly. " + He(sex) + " is starving"
                    : name + " sniffs your empty hand, hoping for food";
            case MOOD -> critical
                    ? name + " looks miserable. " + He(sex) + " needs some fun and attention"
                    : name + " barely reacts. " + He(sex) + " is bored and wants to play";
            case ENERGY -> critical
                    ? name + " is exhausted and can barely stand"
                    : name + " yawns and can barely keep " + his(sex) + " eyes open";
            case CLEANLINESS -> critical
                    ? name + " is filthy and itchy. " + He(sex) + " badly needs a brush"
                    : name + "'s coat is getting matted. A brush would help";
            case HEALTH -> name + " doesn't look well";
        };
    }

    public static String restingCheck(String name, PetSex sex) {
        return name + " leans into your hand, worn out from training. Let " + him(sex) + " rest a while";
    }

    public static String trainingPrompt(Pet pet) {
        return "Look at " + pet.name() + " and say a command";
    }

    public static String age(long bornAtMillis, long now) {
        if (bornAtMillis <= 0L || now < bornAtMillis) {
            return "Unknown";
        }
        long minutes = (now - bornAtMillis) / 60_000L;
        if (minutes < 1L) {
            return "Newborn";
        }
        if (minutes < 60L) {
            return count(minutes, "minute");
        }
        long hours = minutes / 60L;
        if (hours < 24L) {
            long rest = minutes % 60L;
            return rest == 0L ? count(hours, "hour") : count(hours, "hour") + " and " + count(rest, "minute");
        }
        long days = hours / 24L;
        long restHours = hours % 24L;
        return restHours == 0L ? count(days, "day") : count(days, "day") + " and " + count(restHours, "hour");
    }

    private static String count(long amount, String unit) {
        return amount + " " + unit + (amount == 1L ? "" : "s");
    }

    public static String trickName(Trick trick) {
        return switch (trick) {
            case SIT -> "Sit";
            case COME -> "Come";
            case STAY -> "Stay";
            case SPEAK -> "Speak";
            case JUMP -> "Jump";
            case SPIN -> "Spin";
            case BEG -> "Beg";
            case PAW -> "Shake Paw";
        };
    }

    public static String trickDescription(Trick trick) {
        return switch (trick) {
            case SIT -> "Sits down and waits for you";
            case COME -> "Runs back to your side";
            case STAY -> "Stays put until you call";
            case SPEAK -> "Barks, meows or yips on cue";
            case JUMP -> "Leaps up into the air";
            case SPIN -> "Twirls around in a circle";
            case BEG -> "Sits up and begs for a treat";
            case PAW -> "Offers you a paw to shake";
        };
    }

    public static String reaction(String name, PetSex sex, Trick trick) {
        return switch (trick) {
            case SIT -> name + " sits down and looks up at you";
            case COME -> name + " comes bounding over";
            case STAY -> name + " stays put, watching you closely";
            case SPEAK -> name + " speaks up proudly";
            case JUMP -> name + " leaps into the air";
            case SPIN -> name + " chases " + his(sex) + " own tail in a circle";
            case BEG -> name + " sits up and begs";
            case PAW -> name + " offers you a paw";
        };
    }

    public static String refusal(String name, PetSex sex, String reason) {
        return switch (reason) {
            case "sick" -> name + " is feeling too poorly to play";
            case "tired" -> name + " is too tired to keep going";
            case "sleepy" -> name + " isn't sleepy right now";
            case "food" -> name + " turns up " + his(sex) + " nose at that";
            case "medicine" -> name + " isn't sick and doesn't need medicine";
            case "attention" -> name + " is too distracted to listen";
            case "owner" -> "Only " + his(sex) + " owner can do that";
            case "full-out" -> "You already have as many pets out as you can look after";
            case "full-stored" -> "Your kennel has no room left";
            case "bored" -> name + " has had enough training for now";
            default -> reason;
        };
    }
}
