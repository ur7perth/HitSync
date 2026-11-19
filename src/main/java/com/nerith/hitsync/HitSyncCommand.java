package com.nerith.hitsync;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class HitSyncCommand implements CommandExecutor, TabCompleter {

    private static final List<String> FEATURES = List.of("detector", "alerts", "auto-fix", "wall-protection");
    private static final List<String> SUBS = List.of("status", "toggle", "alerts", "reload");

    private final NerithMcHitSync plugin;

    public HitSyncCommand(NerithMcHitSync plugin) {
        this.plugin = plugin;
    }

    private void msg(CommandSender s, String text) {
        s.sendMessage(plugin.color(plugin.prefix() + " &7» &f" + text));
    }

    private boolean admin(CommandSender s) {
        if (s.isOp() || s.hasPermission("nerithmchitsync.admin")) return true;
        msg(s, "&cYou do not have permission.");
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            msg(sender, "Usage: /hitsync <status|toggle <feature>|alerts|reload>");
            msg(sender, "Features: " + String.join(", ", FEATURES));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "status" -> {
                if (!admin(sender)) return true;
                for (String f : FEATURES) {
                    msg(sender, f + ": " + (plugin.enabled(f) ? "&aON" : "&cOFF"));
                }
            }
            case "reload" -> {
                if (!admin(sender)) return true;
                plugin.reloadConfig();
                msg(sender, "&aConfig reloaded.");
            }
            case "alerts" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "Players only.");
                    return true;
                }
                if (!(p.isOp() || p.hasPermission("nerithmchitsync.alerts"))) {
                    msg(sender, "&cYou do not have permission.");
                    return true;
                }
                boolean on = plugin.tracker().toggleAlerts(p);
                msg(sender, "Your alerts are now " + (on ? "&aON" : "&cOFF"));
            }
            case "toggle" -> {
                if (!admin(sender)) return true;
                if (args.length < 2 || !FEATURES.contains(args[1].toLowerCase())) {
                    msg(sender, "Usage: /hitsync toggle <" + String.join("|", FEATURES) + ">");
                    return true;
                }
                String key = args[1].toLowerCase();
                boolean now = !plugin.enabled(key);
                plugin.getConfig().set("features." + key, now);
                plugin.saveConfig();
                msg(sender, key + " is now " + (now ? "&aON" : "&cOFF"));
            }
            default -> msg(sender, "Unknown subcommand. Use /hitsync");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("toggle")) {
            for (String f : FEATURES) if (f.startsWith(args[1].toLowerCase())) out.add(f);
        }
        return out;
    }
}
