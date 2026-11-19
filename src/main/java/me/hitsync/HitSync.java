
package me.hitsync;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HitSync extends JavaPlugin implements Listener {

    private boolean enabled = true;

    private final Map<String, Long> registeredHits =
            new ConcurrentHashMap<>();

    private final Map<String, Long> lastRepairs =
            new ConcurrentHashMap<>();

    private long tick = 0;

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

                        if (!enabled) return;

                        try {
                            if (event.getPacket()
                                    .getEnumEntityUseActions()
                                    .read(0)
                                    .getAction()
                                    != EnumWrappers.EntityUseAction.ATTACK) {
                                return;
                            }

                            Player attacker = event.getPlayer();
                            int targetId = event.getPacket()
                                    .getIntegers().read(0);

                            Bukkit.getScheduler().runTask(
                                    HitSync.this,
                                    () -> processAttack(attacker, targetId)
                            );

                        } catch (Exception ex) {
                            getLogger().warning(
                                    "Could not process attack packet: "
                                            + ex.getMessage()
                            );
                        }
                    }
                }
        );

        Bukkit.getScheduler().runTaskTimer(
                this,
                () -> {
                    tick++;
                    registeredHits.entrySet().removeIf(
                            e -> tick - e.getValue() > 5
                    );
                    lastRepairs.entrySet().removeIf(
                            e -> tick - e.getValue() > 5
                    );
                },
                1L,
                1L
        );

        getLogger().info("HitSync enabled successfully!");
    }

    @Override
    public void onDisable() {
        ProtocolLibrary.getProtocolManager()
                .removePacketListeners(this);

        getLogger().info("HitSync disabled!");
    }

    private String hitKey(UUID attacker, UUID target) {
        return attacker + ":" + target;
    }

    /*
     * Detect damage already processed by the server.
     * Cancelled damage events are also recorded.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDamage(EntityDamageByEntityEvent event) {

        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof Player target)) return;

        registeredHits.put(
                hitKey(attacker.getUniqueId(), target.getUniqueId()),
                tick
        );
    }

    private void processAttack(Player attacker, int targetId) {

        if (!enabled || !attacker.isOnline()) return;

        Entity entity = null;

        for (Entity nearby : attacker.getWorld().getNearbyEntities(
                attacker.getLocation(), 4, 4, 4)) {

            if (nearby.getEntityId() == targetId) {
                entity = nearby;
                break;
            }
        }

        if (!(entity instanceof Player target)) return;

        if (!target.isOnline()
                || target.isDead()
                || attacker.equals(target)
                || !target.getWorld().equals(attacker.getWorld())) {
            return;
        }

        if (attacker.getGameMode() == GameMode.SPECTATOR
                || attacker.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        String key = hitKey(
                attacker.getUniqueId(),
                target.getUniqueId()
        );

        Long normalHit = registeredHits.get(key);

        // A normal or cancelled server damage event already occurred.
        if (normalHit != null && tick - normalHit <= 2) return;

        // Do not repair attacks during vanilla invulnerability frames.
        if (target.getNoDamageTicks() > 0 || target.isInvulnerable()) {
            return;
        }

        // Preserve the vanilla attack cooldown.
        float cooldown = attacker.getAttackCooldown(0.5f);

        if (cooldown < 0.95f) return;

        // Basic vanilla melee reach validation.
        if (attacker.getEyeLocation().distanceSquared(
                target.getLocation().add(0, 0.9, 0)
        ) > 9.0) {
            return;
        }

        // The target must actually be in the attacker's line of sight.
        Vector direction = attacker.getEyeLocation().getDirection();

        RayTraceResult ray = attacker.getWorld().rayTraceEntities(
                attacker.getEyeLocation(),
                direction,
                3.0,
                0.3,
                e -> e.getUniqueId().equals(target.getUniqueId())
        );

        if (ray == null || ray.getHitEntity() == null) return;

        // Prevent repeated repairs for the same attacker/target.
        Long lastRepair = lastRepairs.get(key);

        if (lastRepair != null && tick - lastRepair < 2) return;

        double damage = calculateDamage(attacker, cooldown);

        if (damage <= 0) return;

        lastRepairs.put(key, tick);

        /*
         * Uses Bukkit's normal damage pipeline.
         * Other plugins, including WorldGuard, can cancel this event.
         */
        target.damage(damage, attacker);

        getLogger().fine(
                "Repaired a missed hit from "
                        + attacker.getName()
                        + " to " + target.getName()
        );
    }

    private double calculateDamage(Player attacker, float cooldown) {

        if (attacker.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE) == null) {
            return 0;
        }

        double baseDamage = attacker.getAttribute(
                Attribute.GENERIC_ATTACK_DAMAGE
        ).getValue();

        // Vanilla-style attack cooldown scaling.
        double strength = 0.2 + cooldown * cooldown * 0.8;

        double damage = baseDamage * strength;

        // Include the held weapon's Sharpness bonus.
        if (attacker.getInventory().getItemInMainHand().hasItemMeta()) {

            int sharpness = attacker.getInventory()
                    .getItemInMainHand()
                    .getEnchantmentLevel(Enchantment.DAMAGE_ALL);

            if (sharpness > 0) {
                damage += 0.5 * sharpness + 0.5;
            }
        }

        return Math.max(0, damage);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean onCommand(
            org.bukkit.command.CommandSender sender,
            org.bukkit.command.Command command,
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

            enabled = true;
            sender.sendMessage("§aHitSync has been enabled!");

        } else if (args[0].equalsIgnoreCase("off")) {

            enabled = false;
            sender.sendMessage("§cHitSync has been disabled!");

        } else {
            sender.sendMessage("§cUsage: /GhostHit <On|Off>");
        }

        return true;
    }
}
