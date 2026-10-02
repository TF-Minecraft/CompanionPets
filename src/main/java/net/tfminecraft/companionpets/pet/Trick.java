package net.tfminecraft.companionpets.pet;

/** Stable persisted ID; custom definitions belong to configuration. */
public record Trick(String name) {
    public enum Kind { SIT, FOLLOW, COME, STAY, SPEAK, JUMP, SPIN, LAY, PAW, BEG, CUSTOM }
    public static final Trick SIT = new Trick("SIT"), FOLLOW = new Trick("FOLLOW"), COME = new Trick("COME"), STAY = new Trick("STAY"),
            SPEAK = new Trick("SPEAK"), JUMP = new Trick("JUMP"), SPIN = new Trick("SPIN"),
            LAY = new Trick("LAY"), PAW = new Trick("PAW"), BEG = new Trick("BEG");
    public Trick {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_-]*")) throw new IllegalArgumentException("Invalid trick ID: " + name);
        name = name.toUpperCase(java.util.Locale.ROOT);
        // SLEEP was the built-in ID displayed as Rest. Preserve saved words and progress.
        if (name.equals("SLEEP")) name = "LAY";
    }
    public Kind kind() {
        try { return Kind.valueOf(name); } catch (IllegalArgumentException ex) { return Kind.CUSTOM; }
    }
    // SPIN remains parseable only for migrating old saved data.
    public static Trick[] values() { return new Trick[]{FOLLOW, COME, SIT, STAY, LAY, PAW, SPEAK, BEG, JUMP}; }
    public static Trick valueOf(String name) {
        Trick parsed = new Trick(name);
        for (Trick base : values()) if (base.equals(parsed)) return base;
        return parsed;
    }
}
