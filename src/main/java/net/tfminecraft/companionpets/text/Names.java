package net.tfminecraft.companionpets.text;

import java.util.Locale;

public final class Names {
    private Names() {
    }

    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replace('§', ' ').trim().replaceAll("\\s+", " ");
        if (cleaned.length() > 32) {
            cleaned = cleaned.substring(0, 32).trim();
        }
        return cleaned;
    }

    public static boolean confirms(String text) {
        String line = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        return line.equals("si") || line.equals("sí") || line.equals("yes") || line.equals("vale");
    }

    public static boolean cancels(String text) {
        String line = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        return line.equals("no") || line.equals("cancelar");
    }
}
