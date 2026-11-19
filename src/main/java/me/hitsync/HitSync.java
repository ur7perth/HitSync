
package me.hitsync;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HitSync extends JavaPlugin implements Listener {

    private volatile boolean ghostHitEnabled = true;

    private static final double MAX_RECOVERY_DISTANCE = 3.2;
    private static final double MIN_ATTACK_COOLDOWN = 0.85;
    private static final long RECOVERY_DELAY_TICKS = 2L;
    private static final long RECOVERY_COOLDOWN_NS = 500_000_000L;
    private static final long RECORD_EXPIRATION_NS = 5_000_000_000L;

    private final Map<AttackKey, AttackPacket> attackPackets =
            new ConcurrentHashMap<>();

    private final Map<AttackKey, DamageRecord> damageEvents =
            new ConcurrentHashMap<>();

    private final Map<AttackKey, Long> recoveryCooldowns =
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

                            damageEvents.remove(key);

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
                                    RECOVERY_DELAY_TICKS
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

        getLogger().info("HitSync GhostHit Recovery enabled.");
    }

    @Override
    public void onDisable() {

        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
            ProtocolLibrary.getProtocolManager()
                    .removePacketListeners(this);
        }

        attackPackets.clear();
        damageEvents.clear();
        recoveryCooldowns.clear();

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

        attackPackets.remove(key, packet);

        DamageRecord damage = damageEvents.remove(key);

        if (damage != null) {
            if (damage.cancelled()) {
                getLogger().fine(
                        "[HitSync] Original damage was cancelled. "
                                + "Recovery skipped."
                );
            }

            return;
        }

        Player target = findTarget(
                attacker.getWorld(),
                key.targetEntityId()
        );

        if (target == null) {
            return;
        }

        if (!target.isGliding()) {
            return;
        }

        if (!isValidRecoveryTarget(attacker, target)) {
            return;
        }

        long now = System.nanoTime();

        Long lastRecovery = recoveryCooldowns.get(key);

        if (lastRecovery != null
                && now - lastRecovery < RECOVERY_COOLDOWN_NS) {
            return;
        }

        // Paper 1.20.1: getAttackCooldown() takes no arguments.
        double attackCooldown = attacker.getAttackCooldown();

        if (attackCooldown < MIN_ATTACK_COOLDOWN) {
            return;
        }

        double damageAmount = calculateRecoveryDamage(
                attacker,
                attackCooldown
        );

        if (damageAmount <= 0.0) {
            return;
        }

        recoveryCooldowns.put(key, now);

        getLogger().info(
                "[HitSync] Elytra GhostHit recovery attempted. "
                        + "Attacker: " + attacker.getName()
                        + " | Target: " + target.getName()
                        + " | Damage: " + String.format("%.2f", damageAmount)
        );

        target.damage(damageAmount, attacker);
    }

    private boolean isValidRecoveryTarget(
            Player attacker,
            Player target
    ) {

        if (!attacker.isOnline()
                || !target.isOnline()
                || attacker.isDead()
                || target.isDead()
                || target.isInvulnerable()) {
            return false;
        }

        if (attacker.getWorld() != target.getWorld()) {
            return false;
        }

        if (!attacker.getWorld().getPVP()) {
            return false;
        }

        if (target.getNoDamageTicks() > 0) {
            return false;
        }

        double distanceSquared = attacker.getLocation()
                .distanceSquared(target.getLocation());

        return distanceSquared <=
                MAX_RECOVERY_DISTANCE * MAX_RECOVERY_DISTANCE;
    }

    private double calculateRecoveryDamage(
            Player attacker,
            double attackCooldown
    ) {

        AttributeInstance attackAttribute =
                attacker.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);

        if (attackAttribute == null) {
            return 0.0;
        }

        ItemStack weapon = attacker.getInventory().getItemInMainHand();

        if (weapon == null || weapon.getType().isAir()) {
            return 0.0;
        }

        double baseDamage = attackAttribute.getValue();

        double cooldownMultiplier =
                attackCooldown * attackCooldown * 0.5 + 0.5;

        double damage = baseDamage * cooldownMultiplier;

        int sharpnessLevel =
                weapon.getEnchantmentLevel(Enchantment.DAMAGE_ALL);

        if (sharpnessLevel > 0) {
            double sharpnessBonus = 0.5 * sharpnessLevel + 0.5;
            damage += sharpnessBonus * attackCooldown;
        }

        return Math.max(0.0, damage);
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

        attackPackets.entrySet().removeIf(
                entry -> now - entry.getValue().timestamp()
                        > RECORD_EXPIRATION_NS
        );

        damageEvents.entrySet().removeIf(
                entry -> now - entry.getValue().timestamp()
                        > RECORD_EXPIRATION_NS
        );

        recoveryCooldowns.entrySet().removeIf(
                entry -> now - entry.getValue()
                        > RECORD_EXPIRATION_NS
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

        recoveryCooldowns.keySet().removeIf(
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
