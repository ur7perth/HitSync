package com.nerith.hitsync;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;

/**
 * Wall combo protection: a player who is inside a wall or pinned against one
 * cannot be comboed (follow-up hits are cancelled).
 */
public final class WallGuard implements Listener {

    private final NerithMcHitSync plugin;
    private final HitTracker tracker;

    public WallGuard(NerithMcHitSync plugin, HitTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!plugin.enabled("wall-protection")) return;
        if (!(e.getEntity() instanceof Player victim) || !(e.getDamager() instanceof Player attacker)) return;

        // Only follow-up hits of a combo are blocked, the first hit always lands.
        if (!tracker.isComboHit(attacker, victim)) return;

        if (isInWall(victim, attacker)) {
            e.setCancelled(true);
        }
    }

    private boolean isInWall(Player victim, Player attacker) {
        FileConfiguration cfg = plugin.getConfig();

        if (cfg.getBoolean("wall-protection.inside-blocks", true)) {
            if (solid(victim.getLocation().getBlock()) || solid(victim.getEyeLocation().getBlock())) {
                return true;
            }
        }

        if (cfg.getBoolean("wall-protection.pinned-against-wall", true)) {
            Vector dir = victim.getLocation().toVector()
                    .subtract(attacker.getLocation().toVector())
                    .setY(0);
            if (dir.lengthSquared() > 1.0E-6) {
                dir.normalize().multiply(0.7);
                Location feet = victim.getLocation().add(dir);
                Location head = feet.clone().add(0, 1, 0);
                if (solid(feet.getBlock()) && solid(head.getBlock())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean solid(Block b) {
        return b.getType().isSolid() && !b.isPassable();
    }
}
