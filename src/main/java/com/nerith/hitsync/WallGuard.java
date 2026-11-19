package com.nerith.hitsync;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Wall combo protection: a player who is inside a block or pinned against
 * a wall (in the knockback direction) cannot be hit.
 */
public final class WallGuard implements Listener {

    private final NerithMcHitSync plugin;
    private final HitTracker tracker;

    public WallGuard(NerithMcHitSync plugin, HitTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!plugin.enabled("wall-protection")) return;
        if (!(e.getEntity() instanceof Player victim) || !(e.getDamager() instanceof Player attacker)) return;

        FileConfiguration cfg = plugin.getConfig();
        if (cfg.getBoolean("wall-protection.require-combo", false)
                && !tracker.isComboHit(attacker, victim)) {
            return;
        }

        if (isInWall(victim, attacker)) {
            e.setCancelled(true);
        }
    }

    private boolean isInWall(Player victim, Player attacker) {
        FileConfiguration cfg = plugin.getConfig();
        World world = victim.getWorld();
        BoundingBox box = victim.getBoundingBox().expand(-0.02);

        // 1) Player is inside a block
        if (cfg.getBoolean("wall-protection.inside-blocks", true) && collides(world, box)) {
            return true;
        }

        // 2) Player is pinned against a wall in the knockback direction
        if (cfg.getBoolean("wall-protection.pinned-against-wall", true)) {
            Vector dir = victim.getLocation().toVector()
                    .subtract(attacker.getLocation().toVector())
                    .setY(0);
            if (dir.lengthSquared() < 1.0E-6) return false;
            dir.normalize();

            double dist = cfg.getDouble("wall-protection.pin-distance", 0.5);

            if (Math.abs(dir.getX()) > 0.3
                    && collides(world, box.clone().shift(Math.signum(dir.getX()) * dist, 0, 0))) {
                return true;
            }
            if (Math.abs(dir.getZ()) > 0.3
                    && collides(world, box.clone().shift(0, 0, Math.signum(dir.getZ()) * dist))) {
                return true;
            }
        }
        return false;
    }

    private boolean collides(World world, BoundingBox box) {
        int minX = (int) Math.floor(box.getMinX());
        int maxX = (int) Math.floor(box.getMaxX());
        int minY = (int) Math.floor(box.getMinY());
        int maxY = (int) Math.floor(box.getMaxY());
        int minZ = (int) Math.floor(box.getMinZ());
        int maxZ = (int) Math.floor(box.getMaxZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.isPassable() || b.isLiquid()) continue;
                    for (BoundingBox part : b.getCollisionShape().getBoundingBoxes()) {
                        if (part.clone().shift(x, y, z).overlaps(box)) return true;
                    }
                }
            }
        }
        return false;
    }
}
