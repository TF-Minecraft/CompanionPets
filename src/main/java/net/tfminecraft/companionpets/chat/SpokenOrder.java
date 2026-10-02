package net.tfminecraft.companionpets.chat;

import java.util.Locale;

public final class SpokenOrder {
    private SpokenOrder() {
    }

    public static String line(String message) {
        if (message == null) {
            return "";
        }
        return message.trim();
    }

    public static String key(String message) {
        return line(message)
                .toLowerCase(Locale.ROOT)
                .replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "")
                .replaceAll("\\s+", " ");
    }

    public static boolean matches(String message, String word) {
        if (word == null || word.isBlank()) {
            return false;
        }
        return key(message).equals(key(word));
    }

    /** A pet's whole name can precede or follow a command, including multiword names/words. */
    public static String addressedCommand(String message, String name) {
        String line = key(message), pet = key(name);
        if (pet.isEmpty() || line.equals(pet)) return null;
        if (line.startsWith(pet) && line.length() > pet.length() && separator(line.charAt(pet.length())))
            return key(line.substring(pet.length()));
        int start = line.length() - pet.length();
        if (start > 0 && line.endsWith(pet) && separator(line.charAt(start - 1)))
            return key(line.substring(0, start));
        return null;
    }
    private static boolean separator(char value) { return Character.isWhitespace(value) || value == ',' || value == ':'; }
}
