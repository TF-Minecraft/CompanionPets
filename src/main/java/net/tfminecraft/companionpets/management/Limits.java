package net.tfminecraft.companionpets.management;

public record Limits(int maxStored, int maxOut) {
    public static Limits defaults() {
        return new Limits(20, 4);
    }
}
