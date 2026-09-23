package net.tfminecraft.companionpets.session;

public final class HatchPrompt {
    private final String typeId;
    private final long expiresAt;
    private String name;
    private boolean confirming;
    private boolean choosingSex;

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

    public boolean choosingSex() {
        return choosingSex;
    }

    public void choosingSex(boolean choosingSex) {
        this.choosingSex = choosingSex;
    }
}
