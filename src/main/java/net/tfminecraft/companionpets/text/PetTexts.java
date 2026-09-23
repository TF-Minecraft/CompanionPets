package net.tfminecraft.companionpets.text;

import net.tfminecraft.companionpets.pet.Illness;
import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.Pet;
import net.tfminecraft.companionpets.pet.PetSex;
import net.tfminecraft.companionpets.pet.Trick;

public final class PetTexts {
    private PetTexts() {
    }

    public static String lowNeed(String name, PetSex sex, Need need) {
        return switch (need) {
            case HUNGER -> name + " tiene hambre";
            case MOOD -> name + " quiere atención";
            case ENERGY -> name + (sex == PetSex.FEMALE ? " está cansada" : " está cansado");
            case CLEANLINESS -> name + (sex == PetSex.FEMALE ? " está sucia" : " está sucio");
            case HEALTH -> name + (sex == PetSex.FEMALE ? " está enferma" : " está enfermo");
        };
    }

    public static String illness(String name, PetSex sex, Illness illness) {
        return switch (illness) {
            case UNWELL -> name + " se encuentra mal";
            case SICK -> name + (sex == PetSex.FEMALE ? " está enferma" : " está enfermo");
            case WEAKENED -> name + (sex == PetSex.FEMALE ? " está débil" : " está débil");
            case NONE -> name;
        };
    }

    public static String summary(Pet pet) {
        return "Hambre " + Math.round(pet.need(Need.HUNGER))
                + "  Ánimo " + Math.round(pet.need(Need.MOOD))
                + "  Energía " + Math.round(pet.need(Need.ENERGY))
                + "  Limpieza " + Math.round(pet.need(Need.CLEANLINESS))
                + "  Salud " + Math.round(pet.need(Need.HEALTH));
    }

    public static String trickName(Trick trick) {
        return switch (trick) {
            case SIT -> "Sentarse";
            case COME -> "Venir";
            case STAY -> "Quieto";
            case SPEAK -> "Hablar";
            case JUMP -> "Saltar";
            case SPIN -> "Girar";
            case BEG -> "Pedir";
            case PAW -> "Dar la pata";
        };
    }

    public static String reaction(String name, PetSex sex, Trick trick) {
        return switch (trick) {
            case SIT -> name + " se sienta";
            case COME -> name + " viene";
            case STAY -> name + (sex == PetSex.FEMALE ? " se queda quieta" : " se queda quieto");
            case SPEAK -> name + " habla";
            case JUMP -> name + " salta";
            case SPIN -> name + " gira";
            case BEG -> name + " pide";
            case PAW -> name + " da la pata";
        };
    }

    public static String refusal(String name, PetSex sex, String reason) {
        return switch (reason) {
            case "sick" -> name + (sex == PetSex.FEMALE ? " está enferma y no quiere jugar" : " está enfermo y no quiere jugar");
            case "tired" -> name + (sex == PetSex.FEMALE ? " está demasiado cansada" : " está demasiado cansado");
            case "sleepy" -> name + " no tiene sueño";
            case "food" -> "Eso no se lo come";
            case "medicine" -> name + (sex == PetSex.FEMALE ? " no está enferma" : " no está enfermo");
            case "attention" -> name + " no presta atención";
            case "owner" -> "Solo su dueño puede hacer eso";
            case "full-out" -> "El cupo de mascotas fuera está lleno";
            case "full-stored" -> "La caseta está llena";
            case "bored" -> name + " se aburre";
            default -> reason;
        };
    }
}
