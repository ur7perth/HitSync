package com.nerith.hitsync;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class HitSyncCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of(
            "status",
            "alerts",
            "stats",
            "debug",
            "reload"
    );

    private final NerithMcHitSync plugin;

    public HitSyncCommand(NerithMcHitSync plugin) {
        this.plugin = plugin;
    }

    private void msg(CommandSender sender, String text) {
        sender.sendMessage(
                plugin.color(plugin.prefix() + " &7- &f" + text)
        );
    }

    private boolean admin(CommandSender sender) {
        if (sender.isOp()
                || sender.hasPermission("nerithmchitsync.admin")) {
            return true;
        }

        msg(sender, "&cYou do not have permission.");
        return false;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (args.length == 0) {
            msg(sender, "/hs status | stats [player] | alerts | debug | reload");
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "status" -> {
                if (!admin(sender)) return true;

                msg(sender, "Detector: "
                        + (plugin.enabled("detector") ? "&aON" : "&cOFF"));

                msg(sender, "Alerts: "
                        + (plugin.enabled("alerts") ? "&aON" : "&cOFF"));

                msg(sender, "Debug: "
                        + (plugin.getConfig().getBoolean("debug")
                        ? "&aON" : "&cOFF"));

                msg(sender, "&7Mode: exact-target tracking; no synthetic damage.");
            }

            case "reload" -> {
                if (!admin(sender)) return true;

                plugin.reloadConfig();
                msg(sender, "&aConfig reloaded.");
            }

            case "debug" -> {
                if (!admin(sender)) return true;

                boolean value = !plugin.getConfig().getBoolean("debug", false);

                plugin.getConfig().set("debug", value);
                plugin.saveConfig();

                msg(sender, "Debug: " + (value ? "&aON" : "&cOFF"));
            }

            case "stats" -> {
                if (!admin(sender)) return true;

                Player target = args.length >= 2
                        ? Bukkit.getPlayerExact(args[1])
                        : sender instanceof Player self ? self : null;

                if (target == null) {
                    msg(sender, "&cPlayer not found.");
                    return true;
                }

                HitTracker tracker = plugin.tracker();

                msg(sender,
                        target.getName()
                                + " | unregistered candidates: &c"
                                + tracker.ghosts(target.getUniqueId())
                                + " &7| vanilla iframe blocks: &e"
                                + tracker.blocked(target.getUniqueId()));
            }

            case "alerts" -> {
                if (!(sender instanceof Player player)) {
                    msg(sender, "&cPlayers only.");
                    return true;
                }

                if (!player.isOp()
                        && !player.hasPermission("nerithmchitsync.alerts")) {
                    msg(sender, "&cYou do not have permission.");
                    return true;
                }

                boolean on = plugin.tracker().toggleAlerts(player);

                msg(sender, "Alerts: " + (on ? "&aON" : "&cOFF"));
            }

            default -> msg(sender, "&cUnknown subcommand. Use /hs");
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        List<String> output = new ArrayList<>();

        if (args.length == 1) {
            for (String sub : SUBS) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    output.add(sub);
                }
            }
        } else if (args.length == 2
                && args[0].equalsIgnoreCase("stats")) {

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase()
                        .startsWith(args[1].toLowerCase())) {
                    output.add(player.getName());
                }
            }
        }

        return output;
    }
}
