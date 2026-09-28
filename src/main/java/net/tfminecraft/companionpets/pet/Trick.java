package net.tfminecraft.companionpets.pet;

/** Stable persisted ID; custom definitions belong to configuration. */
public record Trick(String name) {
    public enum Kind { SIT, COME, STAY, SPEAK, JUMP, SPIN, SLEEP, PAW, BEG, CUSTOM }
    public static final Trick SIT = new Trick("SIT"), COME = new Trick("COME"), STAY = new Trick("STAY"),
            SPEAK = new Trick("SPEAK"), JUMP = new Trick("JUMP"), SPIN = new Trick("SPIN"),
            SLEEP = new Trick("SLEEP"), PAW = new Trick("PAW"), BEG = new Trick("BEG");
    public Trick {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_-]*")) throw new IllegalArgumentException("Invalid trick ID: " + name);
        name = name.toUpperCase(java.util.Locale.ROOT);
    }
    public Kind kind() {
        try { return Kind.valueOf(name); } catch (IllegalArgumentException ex) { return Kind.CUSTOM; }
    }
    public static Trick[] values() { return new Trick[]{SIT, COME, STAY, SPEAK, JUMP, SPIN, SLEEP, PAW, BEG}; }
    public static Trick valueOf(String name) {
        Trick parsed = new Trick(name);
        for (Trick base : values()) if (base.equals(parsed)) return base;
        return parsed;
    }
}
