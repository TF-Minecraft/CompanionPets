package net.tfminecraft.companionpets.session;

import net.tfminecraft.companionpets.pet.PetSex;

public final class HatchPrompt {
    private final String typeId;
    private final long expiresAt;
    private PetSex sex;
    private String name;
    private boolean confirming;

    public HatchPrompt(String typeId, long expiresAt) {
        this.typeId = typeId;
        this.expiresAt = expiresAt;
    }

    public String typeId() {
        return typeId;
    }

    public long expiresAt() {
        return expiresAt;
    }

    public PetSex sex() {
        return sex;
    }

    public void sex(PetSex sex) {
        this.sex = sex;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    public boolean confirming() {
        return confirming;
    }

    public void confirming(boolean confirming) {
        this.confirming = confirming;
    }
}
