package net.tfminecraft.companionpets.management;

public record Limits(int maxStored, int maxOut) {
    public Limits {
        if (maxStored < 0 || maxOut < 0) throw new IllegalArgumentException("Pet limits must be nonnegative");
    }
    public static Limits defaults() {
        return new Limits(20, 4);
    }
}
