package net.tfminecraft.companionpets.management;

public final class Quota {
    private Quota() {
    }

    public static boolean canBringOut(int currentlyOut, int maxOut) {
        return currentlyOut < maxOut;
    }

    public static boolean canAdopt(int totalPets, int maxPets) {
        return totalPets < maxPets;
    }
}
