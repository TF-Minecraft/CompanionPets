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

    public static boolean matches(String message, String word) {
        if (word == null || word.isBlank()) {
            return false;
        }
        return line(message).toLowerCase(Locale.ROOT).equals(word.trim().toLowerCase(Locale.ROOT));
    }
}
