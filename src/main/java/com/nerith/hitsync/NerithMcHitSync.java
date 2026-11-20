package com.nerith.hitsync;

import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class NerithMcHitSync extends JavaPlugin {
    private HitTracker tracker;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        tracker = new HitTracker(this);
        getServer().getPluginManager().registerEvents(tracker, this);

        PluginCommand command = getCommand("hitsync");

        if (command != null) {
            HitSyncCommand executor = new HitSyncCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().severe("Command 'hitsync' is missing from plugin.yml");
        }

        getLogger().info("NerithMcHitSync 2.0.0 enabled.");
    }

    @Override
    public void onDisable() {
        if (tracker != null) {
            tracker.shutdown();
        }

        getLogger().info("NerithMcHitSync disabled.");
    }

    public HitTracker tracker() {
        return tracker;
    }

    public boolean enabled(String feature) {
        return getConfig().getBoolean("features." + feature, true);
    }

    public String prefix() {
        return getConfig().getString("prefix", "&eNerithHitSync");
    }

    public String color(String text) {
        return ChatColor.translateAlternateColorCodes(
                '&',
                text == null ? "" : text
        );
    }
}
