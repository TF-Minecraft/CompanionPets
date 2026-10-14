package net.tfminecraft.companionpets.estate;

public final class EstateProbe {
    private EstateProbe() { }
    public static boolean available() {
        try {
            Class.forName("net.tfminecraft.tlibs.character.estate.Estates");
            Class.forName("net.tfminecraft.tlibs.character.estate.EstateParticipant");
            return true;
        } catch (ReflectiveOperationException | LinkageError ex) { return false; }
    }
}
