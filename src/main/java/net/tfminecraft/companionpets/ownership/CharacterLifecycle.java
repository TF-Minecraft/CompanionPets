package net.tfminecraft.companionpets.ownership;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import net.tfminecraft.companionpets.gui.MenuHolder;
import net.tfminecraft.companionpets.runtime.PetActions;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.tlibs.character.CharacterSessionEndEvent;
import net.tfminecraft.tlibs.character.CharacterSessionStartEvent;

public final class CharacterLifecycle implements Listener {
    private final PetRuntime runtime;
    private final PetActions actions;
    public CharacterLifecycle(PetRuntime runtime, PetActions actions) { this.runtime = runtime; this.actions = actions; }
    @EventHandler(priority = EventPriority.MONITOR)
    public void end(CharacterSessionEndEvent event) {
        Player player = Bukkit.getPlayer(event.getSession().ref().ownerId());
        if (player != null) {
            actions.ownerSessionChanged(player);
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) player.closeInventory();
        }
        runtime.sessions().clearPlayer(event.getSession().ref().ownerId());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void start(CharacterSessionStartEvent event) {
        var account = event.getSession().ref().ownerId();
        runtime.sessions().refresh(account);
        Player player = Bukkit.getPlayer(account);
        if (player != null) actions.ownerSessionChanged(player);
    }
}
