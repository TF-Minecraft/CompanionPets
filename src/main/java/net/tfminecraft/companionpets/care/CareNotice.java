package net.tfminecraft.companionpets.care;

import net.tfminecraft.companionpets.pet.Need;

public record CareNotice(Kind kind, Need need) {
    public enum Kind {
        ENTERED_LOW,
        UNWELL,
        SICK,
        WEAKENED,
        DIED
    }

    public static CareNotice low(Need need) {
        return new CareNotice(Kind.ENTERED_LOW, need);
    }

    public static CareNotice of(Kind kind) {
        return new CareNotice(kind, null);
    }
}
