
package me.hitsync;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HitSync extends JavaPlugin implements Listener {

    private volatile boolean ghostHitEnabled = true;

    private final Map<AttackKey, AttackPacket> attackPackets =
            new ConcurrentHashMap<>();

    private final Map<AttackKey, DamageRecord> damageEvents =
            new ConcurrentHashMap<>();

    private record AttackKey(UUID attackerId, int targetEntityId) {}

    private record AttackPacket(long timestamp) {}

    private record DamageRecord(long timestamp, boolean cancelled) {}

    @Override
    public void onEnable() {

        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().severe("ProtocolLib is required!");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(this, PacketType.Play.Client.USE_ENTITY) {

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

                            int targetEntityId =
                                    event.getPacket().getIntegers().read(0);

                            AttackKey key = new AttackKey(
                                    attacker.getUniqueId(),
                                    targetEntityId
                            );

                            long timestamp = System.nanoTime();

                            attackPackets.put(
                                    key,
                                    new AttackPacket(timestamp)
                            );

                            Bukkit.getScheduler().runTaskLater(
                                    HitSync.this,
                                    () -> inspectAttack(
                                            attacker,
                                            key,
                                            timestamp
                                    ),
                                    2L
                            );

                        } catch (Exception exception) {
                            getLogger().warning(
                                    "Packet error: " + exception.getMessage()
                            );
                        }
                    }
                }
        );

        Bukkit.getScheduler().runTaskTimer(
                this,
                this::cleanOldRecords,
                20L,
                20L
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

        getLogger().info("HitSync disabled.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDamage(EntityDamageByEntityEvent event) {

        if (!ghostHitEnabled) {
            return;
        }

        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }

        if (!(event.getEntity() instanceof Player target)) {
            return;
        }

        AttackKey key = new AttackKey(
                attacker.getUniqueId(),
                target.getEntityId()
        );

        damageEvents.put(
                key,
                new DamageRecord(
                        System.nanoTime(),
                        event.isCancelled()
                )
        );
    }

    private void inspectAttack(
            Player attacker,
            AttackKey key,
            long packetTimestamp
    ) {

        if (!ghostHitEnabled || !attacker.isOnline()) {
            return;
        }

        AttackPacket packet = attackPackets.get(key);

        if (packet == null || packet.timestamp() != packetTimestamp) {
            return;
        }

        DamageRecord damage = damageEvents.get(key);

        Player target = findTarget(
                attacker.getWorld(),
                key.targetEntityId()
        );

        if (target == null) {

            getLogger().info(
                    "[HitSync] Target not found during inspection. "
                            + "Attacker: " + attacker.getName()
                            + " | Entity ID: " + key.targetEntityId()
            );

            return;
        }

        double distance = attacker.getLocation()
                .distance(target.getLocation());

        Vector velocity = target.getVelocity();

        boolean gliding = target.isGliding();

        if (damage == null) {

            getLogger().info(
                    "[HitSync] No matching damage event."
                            + " Attacker: " + attacker.getName()
                            + " | Target: " + target.getName()
                            + " | Elytra: " + gliding
                            + " | Distance: " + String.format("%.2f", distance)
                            + " | Target velocity: "
                            + String.format(
                                    "%.2f, %.2f, %.2f",
                                    velocity.getX(),
                                    velocity.getY(),
                                    velocity.getZ()
                            )
            );

            return;
        }

        long difference = Math.abs(
                damage.timestamp() - packetTimestamp
        );

        if (difference > 1_500_000_000L) {

            getLogger().info(
                    "[HitSync] Damage event timing mismatch."
                            + " Attacker: " + attacker.getName()
                            + " | Target: " + target.getName()
            );

            return;
        }

        if (damage.cancelled()) {

            getLogger().info(
                    "[HitSync] Damage event was cancelled."
                            + " Attacker: " + attacker.getName()
                            + " | Target: " + target.getName()
                            + " | Elytra: " + gliding
            );

            return;
        }

        getLogger().info(
                "[HitSync] Attack and damage event matched."
                        + " Attacker: " + attacker.getName()
                        + " | Target: " + target.getName()
                        + " | Elytra: " + gliding
                        + " | Distance: " + String.format("%.2f", distance)
        );
    }

    private Player findTarget(World world, int entityId) {

        for (Player player : world.getPlayers()) {

            if (player.getEntityId() == entityId) {
                return player;
            }
        }

        return null;
    }

    private void cleanOldRecords() {

        long now = System.nanoTime();
        long expiration = 5_000_000_000L;

        attackPackets.entrySet().removeIf(
                entry -> now - entry.getValue().timestamp() > expiration
        );

        damageEvents.entrySet().removeIf(
                entry -> now - entry.getValue().timestamp() > expiration
        );
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {

        UUID playerId = event.getPlayer().getUniqueId();

        attackPackets.keySet().removeIf(
                key -> key.attackerId().equals(playerId)
        );

        damageEvents.keySet().removeIf(
                key -> key.attackerId().equals(playerId)
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
