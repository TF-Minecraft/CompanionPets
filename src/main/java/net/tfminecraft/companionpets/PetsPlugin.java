package net.tfminecraft.companionpets;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.integration.ModelHook;
import net.tfminecraft.companionpets.listen.PetListener;
import net.tfminecraft.companionpets.runtime.PetActions;
import net.tfminecraft.companionpets.runtime.PetRuntime;
import net.tfminecraft.companionpets.runtime.PetTicker;
import net.tfminecraft.companionpets.session.Sessions;
import net.tfminecraft.companionpets.store.PetStore;
import net.tfminecraft.companionpets.text.Names;
import net.tfminecraft.companionpets.visual.IdleVisual;
import net.tfminecraft.companionpets.visual.PetVisual;
import net.tfminecraft.companionpets.visual.PetVisualTicker;

public final class PetsPlugin extends JavaPlugin {
    private static final long AUTOSAVE_TICKS = 20L * 300;

    private PetStore store;
    private PetActions actions;
    private BukkitTask ticker;
    private BukkitTask statusTicker;
    private BukkitTask autosave;
    private BukkitTask visualTicker;
    private PetVisual visual;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        CompanionConfig config = CompanionConfig.load(this);
        store = new PetStore(this);
        store.load();
        NamespacedKey petKey = new NamespacedKey(this, "pet");
        NamespacedKey toyKey = new NamespacedKey(this, "toy");
        visual = new IdleVisual();
        if (ModelHook.available()) {
            try {
                visual = new ModelHook(getLogger());
            } catch (RuntimeException | LinkageError ex) {
                getLogger().log(java.util.logging.Level.WARNING, "ModelEngine 4 integration unavailable; pets use vanilla bodies", ex);
            }
        } else if (config.types().values().stream().anyMatch(type -> type.appearance().modeled())) {
            getLogger().warning("ModelEngine is not enabled; configured model pets will use their vanilla bodies");
        }
        Bodies bodies = new Bodies(this, petKey, visual);
        PetRuntime runtime = new PetRuntime(this, config, store, new Sessions(), bodies, visual, petKey, toyKey);
        actions = new PetActions(runtime);
        Bukkit.getPluginManager().registerEvents(new PetListener(runtime, actions), this);
        PetTicker petTicker = new PetTicker(runtime, actions);
        ticker = Bukkit.getScheduler().runTaskTimer(this, petTicker, 10L, 10L);
        visualTicker = Bukkit.getScheduler().runTaskTimer(this, new PetVisualTicker(runtime), 2L, 2L);
        statusTicker = Bukkit.getScheduler().runTaskTimer(this, petTicker::lookBars, 1L, 1L);
        autosave = Bukkit.getScheduler().runTaskTimer(this, store::save, AUTOSAVE_TICKS, AUTOSAVE_TICKS);
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                actions.reattach(entity);
            }
        }
        getLogger().info("CompanionPets enabled (" + config.types().size() + " pet types)");
    }

    @Override
    public void onDisable() {
        if (visualTicker != null) visualTicker.cancel();
        if (visual != null) visual.close();
        if (ticker != null) {
            ticker.cancel();
        }
        if (statusTicker != null) {
            statusTicker.cancel();
        }
        if (autosave != null) {
            autosave.cancel();
        }
        if (actions != null) {
            actions.clearInteractions();
            actions.holograms().clear();
            actions.stashLooseToys();
        }
        if (store != null) {
            store.save();
        }
        getLogger().info("CompanionPets disabled");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("order")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command can only be used in game.");
                return true;
            }
            actions.orderLookingAt(player, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("calm")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command can only be used in game.");
                return true;
            }
            actions.calmLookingAt(player);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("owner")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command can only be used in game.");
                return true;
            }
            if (!player.hasPermission("companionpets.test")) {
                player.sendMessage("You do not have permission to use this test command.");
                return true;
            }
            actions.setTestOwner(player, args[1]);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("testdog")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command can only be used in game.");
                return true;
            }
            if (!player.hasPermission("companionpets.test")) {
                player.sendMessage("You do not have permission to use this test command.");
                return true;
            }
            String name = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : "Test Dog";
            actions.spawnTestDog(player, Names.sanitize(name));
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("moment")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command can only be used in game.");
                return true;
            }
            if (!player.hasPermission("companionpets.test")) {
                player.sendMessage("You do not have permission to use this test command.");
                return true;
            }
            actions.triggerTestMoment(player, args[1]);
            return true;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("personality") || args[0].equalsIgnoreCase("social"))) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command can only be used in game.");
                return true;
            }
            if (!player.hasPermission("companionpets.test")) {
                player.sendMessage("You do not have permission to use this test command.");
                return true;
            }
            if (args[0].equalsIgnoreCase("personality")) {
                actions.setTestPersonality(player, args[1]);
            } else {
                actions.triggerTestSocial(player, args[1]);
            }
            return true;
        }
        sender.sendMessage("Usage: /companionpets <order WORD|calm|testdog [name]|moment TYPE|personality TYPE|social TYPE|owner TARGET>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) return List.of();
        boolean test = sender.hasPermission("companionpets.test");
        List<String> choices = new ArrayList<>();
        if (args.length == 1) {
            choices.add("order");
            choices.add("calm");
            if (test) choices.addAll(List.of("testdog", "moment", "personality", "social", "owner"));
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "order" -> {
                    if (sender instanceof Player player) choices.addAll(actions.orderWordsLookingAt(player));
                }
                case "moment" -> { if (test) choices.addAll(List.of("affection", "bark", "mischief", "dig")); }
                case "personality" -> { if (test) choices.addAll(List.of("friendly", "playful", "shy", "territorial", "grumpy")); }
                case "social" -> { if (test) choices.addAll(List.of("sniff", "chase", "bark")); }
                case "owner" -> {
                    if (test) {
                        choices.addAll(List.of("fake", "self"));
                        for (Player player : Bukkit.getOnlinePlayers()) choices.add(player.getName());
                    }
                }
                default -> { }
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).distinct().sorted().toList();
    }
}
