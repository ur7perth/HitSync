
package me.hitsync;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HitSync extends JavaPlugin implements Listener {

    private static final String PREFIX = "§eHitSync §8» ";

    private static final double MAX_REACH = 3.2;
    private static final double MIN_COOLDOWN = 0.90;
    private static final long CHECK_DELAY = 2L;
    private static final long ATTACK_TIMEOUT = 2_000_000_000L;

    private volatile boolean enabled = true;
    private volatile boolean detectorEnabled = true;
    private volatile boolean recoveryEnabled = true;
    private volatile boolean alertsEnabled = true;
    private volatile boolean wallEnabled = true;

    private final Map<AttackKey, AttackAttempt> attacks =
            new ConcurrentHashMap<>();

    private final Map<AttackKey, DamageRecord> damageEvents =
            new ConcurrentHashMap<>();

    private final Map<AttackKey, Long> recoveredAttacks =
            new ConcurrentHashMap<>();

    private record AttackKey(UUID attacker, int targetId) {}

    private record AttackAttempt(long timestamp) {}

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

                        if (!enabled || !detectorEnabled) {
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

                            int targetId =
                                    event.getPacket().getIntegers().read(0);

                            AttackKey key = new AttackKey(
                                    attacker.getUniqueId(),
                                    targetId
                            );

                            long timestamp = System.nanoTime();

                            attacks.put(
                                    key,
                                    new AttackAttempt(timestamp)
                            );

                            Bukkit.getScheduler().runTaskLater(
                                    HitSync.this,
                                    () -> inspectAttack(
                                            attacker,
                                            key,
                                            timestamp
                                    ),
                                    CHECK_DELAY
                            );

                        } catch (Exception exception) {
                            getLogger().warning(
                                    "Packet processing error: "
                                            + exception.getMessage()
                            );
                        }
                    }
                }
        );

        Bukkit.getScheduler().runTaskTimer(
                this,
                this::cleanup,
                20L,
                20L
        );

        getLogger().info("HitSync 3.0 enabled.");
    }

    @Override
    public void onDisable() {

        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
            ProtocolLibrary.getProtocolManager()
                    .removePacketListeners(this);
        }

        attacks.clear();
        damageEvents.clear();
        recoveredAttacks.clear();

        getLogger().info("HitSync disabled.");
    }

    /*
     * DETECTOR
     */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageByEntityEvent event) {

        if (!enabled || !detectorEnabled) {
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
            long timestamp
    ) {

        if (!enabled || !detectorEnabled || !attacker.isOnline()) {
            return;
        }

        AttackAttempt attempt = attacks.get(key);

        if (attempt == null || attempt.timestamp() != timestamp) {
            return;
        }

        attacks.remove(key, attempt);

        DamageRecord damage = damageEvents.remove(key);

        if (damage != null) {

            if (damage.cancelled()) {
                sendDebug("Damage event was cancelled for "
                        + attacker.getName());
            }

            return;
        }

        Player target = findTarget(
                attacker.getWorld(),
                key.targetId()
        );

        if (target == null) {
            sendDebug("Target not found for " + attacker.getName());
            return;
        }

        sendAlert(attacker, target);

        if (recoveryEnabled) {
            recoverAttack(attacker, target, key, timestamp);
        }
    }

    private Player findTarget(World world, int entityId) {

        for (Player player : world.getPlayers()) {
            if (player.getEntityId() == entityId) {
                return player;
            }
        }

        return null;
    }

    /*
     * RECOVERY
     */

    private void recoverAttack(
            Player attacker,
            Player target,
            AttackKey key,
            long timestamp
    ) {

        if (!enabled || !recoveryEnabled) {
            return;
        }

        if (!isValidTarget(attacker, target)) {
            return;
        }

        if (!hasLineOfSightToTarget(attacker, target)) {
            return;
        }

        long now = System.nanoTime();

        Long previous = recoveredAttacks.get(key);

        if (previous != null
                && now - previous < ATTACK_TIMEOUT) {
            return;
        }

        double cooldown = attacker.getAttackCooldown();

        if (cooldown < MIN_COOLDOWN) {
            return;
        }

        double damage = calculateDamage(attacker, cooldown);

        if (damage <= 0.0) {
            return;
        }

        recoveredAttacks.put(key, now);

        getLogger().info(
                "[Recovery] Attempting recovery: "
                        + attacker.getName()
                        + " -> " + target.getName()
        );

        /*
         * This uses Bukkit's normal damage event.
         * Protection plugins such as WorldGuard can cancel it.
         */
        target.damage(damage, attacker);
    }

    private boolean isValidTarget(
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

        return attacker.getLocation().distanceSquared(
                target.getLocation()
        ) <= MAX_REACH * MAX_REACH;
    }

    private boolean hasLineOfSightToTarget(
            Player attacker,
            Player target
    ) {

        Location eye = attacker.getEyeLocation();
        Vector direction = eye.getDirection();

        return target.getBoundingBox()
                .rayTrace(eye.toVector(), direction, MAX_REACH)
                .isPresent()
                && attacker.hasLineOfSight(target);
    }

    private double calculateDamage(
            Player attacker,
            double cooldown
    ) {

        AttributeInstance attribute =
                attacker.getAttribute(Attribute.ATTACK_DAMAGE);

        if (attribute == null) {
            return 0.0;
        }

        ItemStack weapon =
                attacker.getInventory().getItemInMainHand();

        if (weapon.getType().isAir()) {
            return 0.0;
        }

        double multiplier =
                0.2 + 0.8 * cooldown * cooldown;

        double damage = attribute.getValue() * multiplier;

        int sharpness =
                weapon.getEnchantmentLevel(Enchantment.SHARPNESS);

        if (sharpness > 0) {
            damage += (0.5 * sharpness + 0.5) * cooldown;
        }

        return Math.max(0.0, damage);
    }

    /*
     * WALL PROTECTION
     */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {

        if (!enabled || !wallEnabled) {
            return;
        }

        if (event.getTo() == null) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();

        if (from.getWorld() != to.getWorld()) {
            return;
        }

        if (from.getX() == to.getX()
                && from.getY() == to.getY()
                && from.getZ() == to.getZ()) {
            return;
        }

        Player player = event.getPlayer();

        Vector movement = to.toVector().subtract(from.toVector());

        BoundingBox destination =
                player.getBoundingBox().shift(movement);

        if (intersectsSolidBlock(to.getWorld(), destination)) {
            event.setTo(from);
        }
    }

    private boolean intersectsSolidBlock(
            World world,
            BoundingBox box
    ) {

        int minX = box.getMin().getBlockX();
        int minY = box.getMin().getBlockY();
        int minZ = box.getMin().getBlockZ();

        int maxX = box.getMax().getBlockX();
        int maxY = box.getMax().getBlockY();
        int maxZ = box.getMax().getBlockZ();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {

                    Block block = world.getBlockAt(x, y, z);

                    if (!block.getType().isSolid()) {
                        continue;
                    }

                    if (block.getBoundingBox().overlaps(box)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /*
     * ALERTS
     */

    private void sendAlert(Player attacker, Player target) {

        if (!alertsEnabled) {
            return;
        }

        String message = PREFIX
                + "§c" + attacker.getName()
                + " §7made a possible GhostHit against §f"
                + target.getName();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOp()) {
                player.sendMessage(message);
            }
        }
    }

    private void sendDebug(String message) {
        getLogger().info("[Detector] " + message);
    }

    /*
     * CLEANUP
     */

    private void cleanup() {

        long now = System.nanoTime();

        attacks.entrySet().removeIf(
                entry -> now - entry.getValue().timestamp()
                        > ATTACK_TIMEOUT
        );

        damageEvents.entrySet().removeIf(
                entry -> now - entry.getValue().timestamp()
                        > ATTACK_TIMEOUT
        );

        recoveredAttacks.entrySet().removeIf(
                entry -> now - entry.getValue()
                        > ATTACK_TIMEOUT
        );
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {

        UUID id = event.getPlayer().getUniqueId();

        attacks.keySet().removeIf(
                key -> key.attacker().equals(id)
        );

        damageEvents.keySet().removeIf(
                key -> key.attacker().equals(id)
        );

        recoveredAttacks.keySet().removeIf(
                key -> key.attacker().equals(id)
        );
    }

    /*
     * COMMANDS
     */

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {

        if (!command.getName().equalsIgnoreCase("HitSync")) {
            return false;
        }

        if (!sender.hasPermission("hitsync.admin")) {
            sender.sendMessage(PREFIX + "§cYou don't have permission.");
            return true;
        }

        if (args.length == 1) {

            if (args[0].equalsIgnoreCase("on")) {
                enabled = true;
                sender.sendMessage(PREFIX + "§aHitSync enabled.");
                return true;
            }

            if (args[0].equalsIgnoreCase("off")) {
                enabled = false;
                sender.sendMessage(PREFIX + "§cHitSync disabled.");
                return true;
            }
        }

        if (args.length == 2) {

            boolean state;

            if (args[1].equalsIgnoreCase("on")) {
                state = true;
            } else if (args[1].equalsIgnoreCase("off")) {
                state = false;
            } else {
                sendUsage(sender);
                return true;
            }

            switch (args[0].toLowerCase()) {

                case "detector" -> {
                    detectorEnabled = state;
                    sender.sendMessage(PREFIX
                            + "§7Detector: "
                            + (state ? "§aON" : "§cOFF"));
                }

                case "recovery" -> {
                    recoveryEnabled = state;
                    sender.sendMessage(PREFIX
                            + "§7Recovery: "
                            + (state ? "§aON" : "§cOFF"));
                }

                case "alerts" -> {
                    alertsEnabled = state;
                    sender.sendMessage(PREFIX
                            + "§7Alerts: "
                            + (state ? "§aON" : "§cOFF"));
                }

                case "wall" -> {
                    wallEnabled = state;
                    sender.sendMessage(PREFIX
                            + "§7Wall Protection: "
                            + (state ? "§aON" : "§cOFF"));
                }

                default -> sendUsage(sender);
            }

            return true;
        }

        sendUsage(sender);
        return true;
    }

    private void sendUsage(CommandSender sender) {

        sender.sendMessage("§e----- HitSync Commands -----");
        sender.sendMessage("§7/HitSync On");
        sender.sendMessage("§7/HitSync Off");
        sender.sendMessage("§7/HitSync Detector On/Off");
        sender.sendMessage("§7/HitSync Recovery On/Off");
        sender.sendMessage("§7/HitSync Alerts On/Off");
        sender.sendMessage("§7/HitSync Wall On/Off");
    }
}
