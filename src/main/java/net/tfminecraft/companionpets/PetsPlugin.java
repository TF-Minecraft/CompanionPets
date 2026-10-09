package net.tfminecraft.companionpets;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.companionpets.body.Bodies;
import net.tfminecraft.companionpets.fx.PetFx;
import net.tfminecraft.companionpets.config.CompanionConfig;
import net.tfminecraft.companionpets.integration.ModelHook;
import net.tfminecraft.companionpets.gui.MenuHolder;
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

public class PetsPlugin extends JavaPlugin {
    private static final java.util.Map<String, String> TEST_COMMANDS = java.util.Map.of(
            "testpet", "spawn", "moment", "moment");
    private static final long AUTOSAVE_TICKS = 20L * 300;
    /** Pending changes reach pets.yml within this time, written off the main thread. */
    private static final long FLUSH_TICKS = 20L * 5;

    private PetStore store;
    private PetActions actions;
    private BukkitTask ticker;
    private BukkitTask autosave;
    private BukkitTask flusher;
    private BukkitTask visualTicker;
    private BukkitTask tailTicker;
    private PetVisual visual;
    private PetRuntime runtime;
    private PetListener petListener;
    private net.tfminecraft.companionpets.staff.StaffCommands staff;
    private net.tfminecraft.companionpets.staff.TestCommands tests;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        final CompanionConfig config;
        try {
            config = CompanionConfig.load(this);
        } catch (IllegalArgumentException ex) {
            getLogger().log(Level.SEVERE, "Invalid CompanionPets config.yml; disabling plugin", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        store = new PetStore(this);
        if (!store.load() || !store.beginSession()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        NamespacedKey petKey = new NamespacedKey(this, "pet");
        NamespacedKey toyKey = new NamespacedKey(this, "toy");
        visual = new IdleVisual();
        if (ModelHook.available()) {
            try {
                ModelHook models = new ModelHook(getLogger());
                models.registerInteractions(this, (player, entity) -> {
                    if (petListener != null) petListener.onModelInteract(player, entity);
                });
                visual = models;
            } catch (RuntimeException | LinkageError ex) {
                getLogger().log(java.util.logging.Level.WARNING, "ModelEngine 4 integration unavailable; pets use vanilla bodies", ex);
            }
        } else if (config.types().values().stream().anyMatch(type -> type.appearance().modeled())) {
            getLogger().warning("ModelEngine is not enabled; configured model pets will use their vanilla bodies");
        }
        Bodies bodies = new Bodies(this, petKey, visual);
        runtime = new PetRuntime(this, config, store, new Sessions(), bodies, visual, petKey, toyKey);
        actions = new PetActions(runtime);
        staff = new net.tfminecraft.companionpets.staff.StaffCommands(runtime, actions);
        tests = new net.tfminecraft.companionpets.staff.TestCommands(runtime, actions, staff);
        Bukkit.getPluginManager().registerEvents(staff.menus(), this);
        Bukkit.getScheduler().runTaskLater(this, this::validateProviders, 200L);
        petListener = new PetListener(runtime, actions);
        Bukkit.getPluginManager().registerEvents(petListener, this);
        new net.tfminecraft.companionpets.listen.FurniturePetHouses(runtime).register();
        PetTicker petTicker = new PetTicker(runtime, actions);
        ticker = Bukkit.getScheduler().runTaskTimer(this, petTicker, 10L, 10L);
        visualTicker = Bukkit.getScheduler().runTaskTimer(this, new PetVisualTicker(runtime), 1L, PetVisualTicker.PERIOD_TICKS);
        tailTicker = Bukkit.getScheduler().runTaskTimer(this, visual::animateTails, 1L, 1L);
        // Needs change every tick without marking the store, so they are queued periodically.
        autosave = Bukkit.getScheduler().runTaskTimer(this, store::requestSave, AUTOSAVE_TICKS, AUTOSAVE_TICKS);
        flusher = Bukkit.getScheduler().runTaskTimer(this, store::flush, FLUSH_TICKS, FLUSH_TICKS);
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                actions.reattach(entity);
            }
        }
        getLogger().info("CompanionPets enabled (" + config.types().size() + " pet types)");
    }

    @Override
    public void onDisable() {
        if (runtime != null) {
            for (var pet : runtime.store().all()) {
                Entity body = runtime.entity(pet);
                if (body != null) PetFx.stopLooking(body);
                if (body instanceof org.bukkit.entity.Mob mob) releaseGoals(mob);
            }
        }
        if (visualTicker != null) visualTicker.cancel();
        if (tailTicker != null) tailTicker.cancel();
        if (ticker != null) {
            ticker.cancel();
        }
        if (autosave != null) {
            autosave.cancel();
        }
        if (flusher != null) flusher.cancel();
        if (actions != null) {
            for (var pet : store.all()) {
                Entity body = runtime.entity(pet);
                if (body != null) runtime.remember(pet, body);
            }
            actions.clearInteractions();
            actions.holograms().clear();
            actions.stashLooseToys();
        }
        if (store != null) {
            store.close();
        }
        if (visual != null) visual.close();
        getLogger().info("CompanionPets disabled");
    }

    /**
     * Goals reference this plugin's runtime and classes. Loaded bodies outlive a disable, so a
     * reload would otherwise leave the old goals running beside the new ones.
     */
    private void releaseGoals(org.bukkit.entity.Mob body) {
        String namespace = new NamespacedKey(this, "goal").getNamespace();
        var goals = Bukkit.getMobGoals();
        for (var goal : List.copyOf(goals.getAllGoals(body))) {
            if (goal.getKey().getNamespacedKey().getNamespace().equals(namespace)) goals.removeGoal(body, goal.getKey());
        }
        PetFx.forgetLooking(body);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!staffAccess(sender)) { sender.sendMessage("CompanionPets commands are for staff only."); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (args.length != 1) { sender.sendMessage("Usage: /companionpets reload"); return true; }
            if (!sender.hasPermission("companionpets.reload")) sender.sendMessage("You do not have permission to reload CompanionPets.");
            else reloadSettings(sender);
            return true;
        }
        if (args.length > 0) {
            String action = args[0].toLowerCase(Locale.ROOT);
            if (TEST_COMMANDS.containsKey(action)) {
                String[] forwarded = args.clone(); forwarded[0] = TEST_COMMANDS.get(action);
                return tests.execute(sender, forwarded);
            }
            if (net.tfminecraft.companionpets.staff.StaffCommands.ACTIONS.contains(action)) return staff.execute(sender, args);
        }
        sender.sendMessage("CompanionPets staff commands:");
        for (String available : onTabComplete(sender, command, label, new String[]{""})) {
            String example = switch (available) {
                case "reload" -> "reload - reload configuration";
                case "moment" -> "moment <affection|bark|mischief|dig|belly|greeting> - look at your pet";
                case "testpet" -> "testpet <type> [name] - spawn a pet with all compatible tricks learned";
                case "list" -> "list <player> [pet name] - their pets and read-only pet details";
                case "find" -> "find <player> [pet name] - show pet locations";
                case "create" -> "create <player> type=<type> name=<name> [tricks=all] [hunger=100 ...] - create in Pet House";
                case "egg" -> "egg <type|all> [player] [amount] - give configured eggs";
                default -> available;
            };
            sender.sendMessage("/companionpets " + example);
        }
        return true;
    }

    private static boolean staffAccess(CommandSender sender) {
        return sender.hasPermission("companionpets.test") || net.tfminecraft.companionpets.staff.StaffCommands.ACTIONS.stream()
                .anyMatch(a -> sender.hasPermission(net.tfminecraft.companionpets.staff.StaffCommands.permission(a)));
    }

    private void validateProviders() {
        for (String issue : staff.diagnostics().validate()) getLogger().warning("Configuration validation: " + issue);
    }

    private void reloadSettings(CommandSender sender) {
        File file = new File(getDataFolder(), "config.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        final CompanionConfig next;
        try {
            yaml.load(file);
            next = CompanionConfig.load(this, yaml);
        } catch (IOException | InvalidConfigurationException | RuntimeException ex) {
            getLogger().log(Level.WARNING, "Could not reload CompanionPets config.yml", ex);
            sender.sendMessage("CompanionPets config.yml could not be loaded. See the server log; the previous settings remain active.");
            return;
        }
        if (next.types().isEmpty()) {
            sender.sendMessage("No valid pet types found. The previous settings remain active.");
            return;
        }
        for (var pet : store.all()) {
            if (runtime.config().type(pet.typeId()) != null && next.type(pet.typeId()) == null) {
                sender.sendMessage("Pet type '" + pet.typeId() + "' is still used by saved pets. The previous settings remain active.");
                return;
            }
        }
        try {
            reloadConfig();
        } catch (RuntimeException ex) {
            getLogger().log(Level.WARNING, "Could not activate CompanionPets config.yml", ex);
            sender.sendMessage("CompanionPets config.yml could not be activated. The previous settings remain active.");
            return;
        }
        for (var player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder || player.getOpenInventory().getTopInventory().getHolder() instanceof net.tfminecraft.companionpets.staff.StaffMenuHolder) player.closeInventory();
        }
        actions.clearInteractions();
        actions.holograms().clear();
        runtime.sessions().clearForReload();
        visual.close();
        net.tfminecraft.companionpets.item.ItemBridge.clearCache();
        runtime.config(next);
        Bukkit.getScheduler().runTaskLater(this, this::validateProviders, 1L);
        for (var world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) actions.reattach(entity);
        }
        sender.sendMessage("CompanionPets settings reloaded (" + next.types().size() + " pet types). Active models have been refreshed.");
        getLogger().info("CompanionPets config reloaded (" + next.types().size() + " pet types)");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0 || !staffAccess(sender)) return List.of();
        if (args.length == 1) {
            List<String> choices = new ArrayList<>(staff.complete(sender, args));
            if (sender.hasPermission("companionpets.test")) choices.addAll(TEST_COMMANDS.keySet());
            return net.tfminecraft.companionpets.staff.StaffCommands.filter(choices, args[0]);
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (TEST_COMMANDS.containsKey(action)) {
            String[] forwarded = args.clone(); forwarded[0] = TEST_COMMANDS.get(action);
            return tests.complete(sender, forwarded);
        }
        return net.tfminecraft.companionpets.staff.StaffCommands.ACTIONS.contains(action) ? staff.complete(sender, args) : List.of();
    }
}
