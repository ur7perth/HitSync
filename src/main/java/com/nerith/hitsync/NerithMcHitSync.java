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
        getServer().getPluginManager().registerEvents(new WallGuard(this, tracker), this);

        HitSyncCommand command = new HitSyncCommand(this);
        PluginCommand pc = getCommand("hitsync");
        if (pc != null) {
            pc.setExecutor(command);
            pc.setTabCompleter(command);
        }

        getLogger().info("NerithMcHitSync v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("NerithMcHitSync disabled.");
    }

    public HitTracker tracker() {
        return tracker;
    }

    public boolean enabled(String feature) {
        return getConfig().getBoolean("features." + feature, true);
    }

    public String prefix() {
        return getConfig().getString("prefix", "&eHitSync");
    }

    public String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
