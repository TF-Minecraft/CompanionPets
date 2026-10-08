package net.tfminecraft.companionpets.management;

public record Limits(int maxPets, int maxOut) {
    public Limits {
        if (maxPets < 0 || maxOut < 0) throw new IllegalArgumentException("Pet limits must be nonnegative");
    }
    public static Limits defaults() {
        return new Limits(20, 4);
    }
}
