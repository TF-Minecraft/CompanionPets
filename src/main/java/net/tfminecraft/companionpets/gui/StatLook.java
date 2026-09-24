package net.tfminecraft.companionpets.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import net.tfminecraft.companionpets.pet.Need;
import net.tfminecraft.companionpets.pet.NeedBand;
import net.tfminecraft.companionpets.pet.Pet;

public final class StatLook {
    private static final int SEGMENTS = 10;
    private static final int BAR_SEGMENTS_SHORT = 5;
    private static final String SEGMENT = "■";
    private static final TextColor EMPTY = TextColor.color(0x3A3A3A);
    private static final TextColor GOOD = TextColor.color(0x55FF55);
    private static final TextColor LOW = TextColor.color(0xFFD23F);
    private static final TextColor CRITICAL = TextColor.color(0xFF4F4F);
    public static final TextColor BOND = TextColor.color(0xF28DD7);

    private StatLook() {
    }

    public static TextColor theme(Need need) {
        return switch (need) {
            case HUNGER -> TextColor.color(0xF0A04B);
            case MOOD -> TextColor.color(0xFFE066);
            case ENERGY -> TextColor.color(0x6FE3FF);
            case CLEANLINESS -> TextColor.color(0xA7D8FF);
            case HEALTH -> TextColor.color(0xFF6B6B);
        };
    }

    public static TextColor band(double value) {
        return switch (NeedBand.of(value)) {
            case STABLE -> GOOD;
            case LOW -> LOW;
            case CRITICAL -> CRITICAL;
        };
    }

    public static Component bar(double value) {
        return bar(value, band(value), SEGMENTS);
    }

    public static Component bar(double value, TextColor fill) {
        return bar(value, fill, SEGMENTS);
    }

    public static Component bar(double value, TextColor fill, int segments) {
        double clamped = Math.max(0.0, Math.min(100.0, value));
        int filled = (int) Math.round(clamped / 100.0 * segments);
        if (filled == 0 && clamped > 0.0) {
            filled = 1;
        }
        TextComponent.Builder bar = Component.text().decoration(TextDecoration.ITALIC, false);
        bar.append(Component.text(SEGMENT.repeat(filled), fill));
        bar.append(Component.text(SEGMENT.repeat(segments - filled), EMPTY));
        return bar.build();
    }

    public static String state(Need need, double value) {
        NeedBand band = NeedBand.of(value);
        return switch (need) {
            case HUNGER -> pick(band, "Well fed", "Peckish", "Starving");
            case MOOD -> pick(band, "Cheerful", "Restless", "Miserable");
            case ENERGY -> pick(band, "Full of energy", "Tired", "Exhausted");
            case CLEANLINESS -> pick(band, "Spotless", "Scruffy", "Filthy");
            case HEALTH -> pick(band, "Healthy", "Weak", "In danger");
        };
    }

    public static String bondState(double bond) {
        if (bond >= 85.0) {
            return "Devoted";
        }
        if (bond >= 60.0) {
            return "Loyal";
        }
        if (bond >= 30.0) {
            return "Friendly";
        }
        return "Wary";
    }

    public static Component summary(Pet pet, Component tag) {
        TextComponent.Builder line = Component.text();
        if (tag != null) {
            line.append(tag).append(Component.text("  │  ", NamedTextColor.DARK_GRAY));
        }
        boolean first = true;
        for (Need need : Need.values()) {
            double value = pet.need(need);
            if (!first) {
                line.append(Component.text("   "));
            }
            first = false;
            line.append(Component.text(shortName(need) + " ", NamedTextColor.GRAY));
            line.append(bar(value, band(value), BAR_SEGMENTS_SHORT));
        }
        return line.build();
    }

    public static Component tag(String text, TextColor color) {
        return Component.text("● " + text, color);
    }

    private static String shortName(Need need) {
        return switch (need) {
            case HUNGER -> "Food";
            case MOOD -> "Mood";
            case ENERGY -> "Energy";
            case CLEANLINESS -> "Clean";
            case HEALTH -> "Health";
        };
    }

    private static String pick(NeedBand band, String stable, String low, String critical) {
        return switch (band) {
            case STABLE -> stable;
            case LOW -> low;
            case CRITICAL -> critical;
        };
    }
}
