package com.nerith.hitsync;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class HitSyncCommand
        implements CommandExecutor, TabCompleter {

    private static final List<String> FEATURES =
            List.of("detector", "alerts", "auto-fix");
    private static final List<String> SUBS = List.of(
            "status", "toggle", "alerts", "stats", "debug", "reload");

    private final NerithMcHitSync plugin;

    public HitSyncCommand(NerithMcHitSync plugin) {
        this.plugin = plugin;
    }

    private void msg(CommandSender s, String text) {
        s.sendMessage(plugin.color(
                plugin.prefix() + " &7- &f" + text));
    }

    private boolean admin(CommandSender s) {
        if (s.isOp() || s.hasPermission("nerithmchitsync.admin")) {
            return true;
        }
        msg(s, "&cYou do not have permission.");
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd,
                             String label, String[] args) {
        if (args.length == 0) {
            msg(sender, "/hs status | toggle <feature> | alerts");
            msg(sender, "/hs stats [player] | debug | reload");
            msg(sender, "Features: " + String.join(", ", FEATURES));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "status" -> {
                if (!admin(sender)) return true;
                for (String f : FEATURES) {
                    msg(sender, f + ": "
                            + (plugin.enabled(f) ? "&aON" : "&cOFF"));
                }
                boolean dbg = plugin.getConfig()
                        .getBoolean("debug", false);
                msg(sender, "debug: " + (dbg ? "&aON" : "&cOFF"));
            }
            case "reload" -> {
                if (!admin(sender)) return true;
                plugin.reloadConfig();
                msg(sender, "&aConfig reloaded.");
            }
            case "debug" -> {
                if (!admin(sender)) return true;
                boolean now = !plugin.getConfig()
                        .getBoolean("debug", false);
                plugin.getConfig().set("debug", now);
                plugin.saveConfig();
                msg(sender, "debug is now "
                        + (now ? "&aON" : "&cOFF"));
            }
            case "stats" -> {
                if (!admin(sender)) return true;
                Player p = null;
                if (args.length >= 2) {
                    p = Bukkit.getPlayerExact(args[1]);
                } else if (sender instanceof Player self) {
                    p = self;
                }
                if (p == null) {
                    msg(sender, "&cPlayer not found.");
                    return true;
                }
                int g = plugin.tracker().ghosts(p.getUniqueId());
                int f = plugin.tracker().fixes(p.getUniqueId());
                msg(sender, p.getName() + ": ghost hits &c" + g
                        + "&f, fixed &a" + f
                        + "&f, ping &e" + p.getPing() + "ms");
            }
            case "alerts" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "Players only.");
                    return true;
                }
                boolean allowed = p.isOp()
                        || p.hasPermission("nerithmchitsync.alerts");
                if (!allowed) {
                    msg(sender, "&cYou do not have permission.");
                    return true;
                }
                boolean on = plugin.tracker().toggleAlerts(p);
                msg(sender, "Your alerts are now "
                        + (on ? "&aON" : "&cOFF"));
            }
            case "toggle" -> {
                if (!admin(sender)) return true;
                if (args.length < 2
                        || !FEATURES.contains(args[1].toLowerCase())) {
                    msg(sender, "Usage: /hs toggle <"
                            + String.join("|", FEATURES) + ">");
                    return true;
                }
                String key = args[1].toLowerCase();
                boolean now = !plugin.enabled(key);
                plugin.getConfig().set("features." + key, now);
                plugin.saveConfig();
                msg(sender, key + " is now "
                        + (now ? "&aON" : "&cOFF"));
            }
            default -> msg(sender, "Unknown subcommand. Use /hs");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd,
                                      String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        } else if (args.length == 2
                && args[0].equalsIgnoreCase("toggle")) {
            for (String f : FEATURES) {
                if (f.startsWith(args[1].toLowerCase())) out.add(f);
            }
        } else if (args.length == 2
                && args[0].equalsIgnoreCase("stats")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                String n = p.getName();
                if (n.toLowerCase().startsWith(args[1].toLowerCase())) {
                    out.add(n);
                }
            }
        }
        return out;
    }
        }
