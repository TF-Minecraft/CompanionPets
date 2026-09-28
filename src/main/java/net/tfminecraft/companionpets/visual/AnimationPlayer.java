package net.tfminecraft.companionpets.visual;

import net.tfminecraft.companionpets.config.PetAppearance.Clip;

/** A small boundary so animation transitions can be checked without a server. */
public interface AnimationPlayer {
    boolean play(Clip clip, boolean loop);
    void stop(String clip);
    boolean playing(String clip);
    default boolean hold(Clip clip) { return play(clip, true); }
    default double length(String clip) { return 1; }
}
