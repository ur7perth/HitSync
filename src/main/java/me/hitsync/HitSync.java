
package me.hitsync;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HitSync extends JavaPlugin implements Listener {

    private volatile boolean ghostHitEnabled = true;

    private final Map<UUID, Long> attackPackets =
            new ConcurrentHashMap<>();

    private final Map<UUID, Long> damageEvents =
            new ConcurrentHashMap<>();

    private final Map<UUID, Boolean> cancelledEvents =
            new ConcurrentHashMap<>();

    private long currentTick;

    @Override
    public void onEnable() {

        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().severe("ProtocolLib is required!");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(
                        this,
                        PacketType.Play.Client.USE_ENTITY
                ) {
                    @Override
                    public void onPacketReceiving(PacketEvent event) {

                        if (!ghostHitEnabled) {
                            return;
                        }

                        try {
                            EnumWrappers.EntityUseAction action =
                                    event.getPacket()
                                            .getEnumEntityUseActions()
                                            .read(0)
                                            .getAction();

                            if (action != EnumWrappers.EntityUseAction.ATTACK) {
                                return;
                            }

                            Player attacker = event.getPlayer();

                            long packetTime = System.nanoTime();

                            attackPackets.put(
                                    attacker.getUniqueId(),
                                    packetTime
                            );

                            Bukkit.getScheduler().runTask(
                                    HitSync.this,
                                    () -> inspectAttack(attacker, packetTime)
                            );

                        } catch (Exception exception) {
                            getLogger().warning(
                                    "Attack packet error: "
                                            + exception.getMessage()
                            );
                        }
                    }
                }
        );

        Bukkit.getScheduler().runTaskTimer(
                this,
                () -> {
                    currentTick++;

                    long now = System.nanoTime();

                    attackPackets.entrySet().removeIf(
                            entry -> now - entry.getValue() > 2_000_000_000L
                    );

                    damageEvents.entrySet().removeIf(
                            entry -> now - entry.getValue() > 2_000_000_000L
                    );

                    cancelledEvents.entrySet().removeIf(
                            entry -> !damageEvents.containsKey(entry.getKey())
                    );
                },
                1L,
                1L
        );

        getLogger().info("HitSync diagnostic system enabled.");
    }

    @Override
    public void onDisable() {

        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
            ProtocolLibrary.getProtocolManager()
                    .removePacketListeners(this);
        }

        attackPackets.clear();
        damageEvents.clear();
        cancelledEvents.clear();

        getLogger().info("HitSync disabled.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDamage(EntityDamageByEntityEvent event) {

        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }

        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        UUID attackerId = attacker.getUniqueId();

        damageEvents.put(attackerId, System.nanoTime());

        cancelledEvents.put(
                attackerId,
                event.isCancelled()
        );
    }

    private void inspectAttack(Player attacker, long packetTime) {

        if (!ghostHitEnabled || !attacker.isOnline()) {
            return;
        }

        Long latestPacket = attackPackets.get(attacker.getUniqueId());

        if (latestPacket == null || latestPacket != packetTime) {
            return;
        }

        Long damageTime = damageEvents.get(attacker.getUniqueId());

        if (damageTime == null || damageTime < packetTime) {

            getLogger().fine(
                    "Attack packet received without a matching damage event. "
                            + "Attacker: " + attacker.getName()
            );

            return;
        }

        Boolean wasCancelled =
                cancelledEvents.get(attacker.getUniqueId());

        if (Boolean.TRUE.equals(wasCancelled)) {

            getLogger().fine(
                    "Attack damage event was cancelled. Attacker: "
                            + attacker.getName()
            );

            return;
        }

        getLogger().fine(
                "Attack and damage event detected for "
                        + attacker.getName()
        );
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {

        if (!command.getName().equalsIgnoreCase("GhostHit")) {
            return false;
        }

        if (!sender.hasPermission("hitsync.admin")) {
            sender.sendMessage("§cYou don't have permission.");
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage("§cUsage: /GhostHit <On|Off>");
            return true;
        }

        if (args[0].equalsIgnoreCase("on")) {

            ghostHitEnabled = true;
            sender.sendMessage("§aHitSync enabled.");

        } else if (args[0].equalsIgnoreCase("off")) {

            ghostHitEnabled = false;
            sender.sendMessage("§cHitSync disabled.");

        } else {

            sender.sendMessage("§cUsage: /GhostHit <On|Off>");
        }

        return true;
    }
}
