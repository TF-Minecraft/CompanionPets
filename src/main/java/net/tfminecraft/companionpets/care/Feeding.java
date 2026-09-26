package net.tfminecraft.companionpets.care;

public final class Feeding {
    private Feeding() {
    }

    public enum Outcome {
        ATE,
        OVERATE
    }

    public static Outcome outcome(double hunger) {
        return hunger >= 100.0 ? Outcome.OVERATE : Outcome.ATE;
    }
}
