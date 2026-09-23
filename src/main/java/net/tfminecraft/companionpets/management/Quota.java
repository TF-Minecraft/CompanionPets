package net.tfminecraft.companionpets.management;

public final class Quota {
    private Quota() {
    }

    public static boolean canBringOut(int currentlyOut, int maxOut) {
        return currentlyOut < maxOut;
    }

    public static boolean canStore(int currentlyStored, int maxStored) {
        return currentlyStored < maxStored;
    }
}
