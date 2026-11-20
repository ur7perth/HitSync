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
                plugin.color(
                        plugin.prefix() + " &7- &f" + text
                )
        );
    }

    private boolean admin(CommandSender sender) {

        if (sender.isOp()
                || sender.hasPermission(
                        "nerithmchitsync.admin"
                )) {
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

            msg(sender, "/hs status");
            msg(sender, "/hs alerts");
            msg(sender, "/hs stats [player]");
            msg(sender, "/hs debug");
            msg(sender, "/hs reload");

            return true;
        }

        switch (args[0].toLowerCase()) {

            case "status" -> {

                if (!admin(sender)) return true;

                msg(sender, "Detector: "
                        + (plugin.enabled("detector")
                        ? "&aON" : "&cOFF"));

                msg(sender, "Alerts: "
                        + (plugin.enabled("alerts")
                        ? "&aON" : "&cOFF"));

                msg(sender, "Auto-Fix: "
                        + (plugin.enabled("auto-fix")
                        ? "&aON" : "&cOFF"));

                msg(sender, "Debug: "
                        + (plugin.getConfig().getBoolean("debug")
                        ? "&aON" : "&cOFF"));
            }

            case "alerts" -> {

                if (!(sender instanceof Player player)) {
                    msg(sender, "&cPlayers only.");
                    return true;
                }

                if (!player.isOp()
                        && !player.hasPermission(
                                "nerithmchitsync.alerts"
                        )) {

                    msg(sender, "&cYou do not have permission.");
                    return true;
                }

                boolean on = plugin.tracker()
                        .toggleAlerts(player);

                msg(sender, "Alerts: "
                        + (on ? "&aON" : "&cOFF"));
            }

            case "stats" -> {

                if (!admin(sender)) return true;

                Player target;

                if (args.length >= 2) {
                    target = Bukkit.getPlayerExact(args[1]);
                } else {
                    target = sender instanceof Player player
                            ? player
                            : null;
                }

                if (target == null) {
                    msg(sender, "&cPlayer not found.");
                    return true;
                }

                HitTracker tracker = plugin.tracker();

                msg(sender, "Player: &e" + target.getName());

                msg(sender, "Ghost candidates: &c"
                        + tracker.ghosts(target.getUniqueId()));

                msg(sender, "Successful retries: &a"
                        + tracker.fixes(target.getUniqueId()));

                msg(sender, "Cancelled damage events: &e"
                        + tracker.cancelled(target.getUniqueId()));

                msg(sender, "Ping: &f"
                        + target.getPing() + "ms");
            }

            case "debug" -> {

                if (!admin(sender)) return true;

                boolean next = !plugin.getConfig()
                        .getBoolean("debug", false);

                plugin.getConfig().set("debug", next);
                plugin.saveConfig();

                msg(sender, "Debug: "
                        + (next ? "&aON" : "&cOFF"));
            }

            case "reload" -> {

                if (!admin(sender)) return true;

                plugin.reloadConfig();

                msg(sender, "&aConfiguration reloaded.");
            }

            default -> msg(sender, "&cUnknown command. Use /hs");
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
