package net.tfminecraft.companionpets.pet;

public enum NeedBand {
    STABLE,
    LOW,
    CRITICAL;

    public static NeedBand of(double value) {
        if (value >= 60.0) {
            return STABLE;
        }
        if (value >= 25.0) {
            return LOW;
        }
        return CRITICAL;
    }
}
