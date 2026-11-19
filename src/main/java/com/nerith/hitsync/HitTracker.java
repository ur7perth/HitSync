package com.nerith.hitsync;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Part 1: detects ghost hits (swing aimed at a player that the server never counted).
 * Part 2: instantly re-applies the hit on the server and keeps the combo alive.
 */
public final class HitTracker implements Listener {

    private record Attempt(UUID target, int tick) {}

    private static final class Combo {
        UUID target;
        int count;
        int last;

        Combo(UUID target, int count, int last) {
            this.target = target;
            this.count = count;
            this.last = last;
        }
    }

    private final NerithMcHitSync plugin;
    private final Map<UUID, Attempt> attempts = new HashMap<>();
    private final Map<UUID, Combo> combos = new HashMap<>();
    private final Map<UUID, Deque<Long>> fixTimes = new HashMap<>();
    private final Map<UUID, Long> lastAlert = new HashMap<>();
    private final Set<UUID> mutedAlerts = new HashSet<>();

    public HitTracker(NerithMcHitSync plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // Records every hit the server really processed
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker) || !(e.getEntity() instanceof Player victim)) return;
        if (e.getCause() != DamageCause.ENTITY_ATTACK) return;

        int now = Bukkit.getCurrentTick();
        UUID aId = attacker.getUniqueId();
        UUID vId = victim.getUniqueId();

        // Even a cancelled hit (WorldGuard, wall guard...) counts as "handled", never a ghost.
        attempts.put(aId, new Attempt(vId, now));
        if (e.isCancelled()) return;

        int window = plugin.getConfig().getInt("combo.window-ticks", 30);
        Combo c = combos.get(aId);
        if (c != null && c.target.equals(vId) && now - c.last <= window) {
            c.count++;
            c.last = now;
        } else {
            combos.put(aId, new Combo(vId, 1, now));
        }
    }

    // ------------------------------------------------------------------
    // Reads every swing and checks what the crosshair is on
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent e) {
        if (!plugin.enabled("detector")) return;
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;

        Player attacker = e.getPlayer();
        if (attacker.isDead() || !isPvpMode(attacker)) return;

        FileConfiguration cfg = plugin.getConfig();
        double reach = cfg.getDouble("detector.reach", 3.1);
        double size = cfg.getDouble("detector.aim-leniency", 0.10);
        if (hasActiveCombo(attacker)) {
            size += cfg.getDouble("detector.combo-leniency-bonus", 0.15);
        }

        Location eye = attacker.getEyeLocation();
        RayTraceResult result = attacker.getWorld().rayTrace(
                eye, eye.getDirection(), reach, FluidCollisionMode.NEVER, true, size,
                en -> en instanceof Player pl
                        && !pl.equals(attacker)
                        && pl.getGameMode() != GameMode.SPECTATOR
                        && attacker.canSee(pl));

        if (result == null || !(result.getHitEntity() instanceof Player target)) return;

        final UUID aId = attacker.getUniqueId();
        final UUID tId = target.getUniqueId();
        final int swingTick = Bukkit.getCurrentTick();
        long delay = Math.max(1, cfg.getInt("detector.check-delay-ticks", 2));

        Bukkit.getScheduler().runTaskLater(plugin, () -> verify(aId, tId, swingTick), delay);
    }

    private void verify(UUID aId, UUID tId, int swingTick) {
        Player attacker = Bukkit.getPlayer(aId);
        Player target = Bukkit.getPlayer(tId);
        if (attacker == null || target == null || !attacker.isOnline() || !target.isOnline()) return;

        // Did the server process this hit? Then it is not a ghost.
        Attempt at = attempts.get(aId);
        if (at != null && at.tick() >= swingTick - 1 && at.target().equals(tId)) return;

        if (target.isDead() || !isPvpMode(target) || target.isInvulnerable()) return;

        FileConfiguration cfg = plugin.getConfig();
        boolean iframes = target.getNoDamageTicks() > target.getMaximumNoDamageTicks() / 2.0;
        if (iframes && !cfg.getBoolean("detector.treat-invulnerability-as-ghost", false)) return;

        boolean fixed = false;
        if (plugin.enabled("auto-fix")) {
            fixed = tryFix(attacker, target);
        }
        alert(attacker, target, fixed);
    }

    // ------------------------------------------------------------------
    // Part 2: instant server-side fix
    // ------------------------------------------------------------------
    private boolean tryFix(Player attacker, Player target) {
        if (!attacker.hasPermission("nerithmchitsync.autofix")) return false;
        if (attacker.isDead() || !isPvpMode(attacker)) return false;
        if (!attacker.getWorld().equals(target.getWorld())) return false;

        FileConfiguration cfg = plugin.getConfig();
        if (reachTo(attacker, target) > cfg.getDouble("auto-fix.max-reach", 3.4)) return false;

        int max = cfg.getInt("auto-fix.max-fixes-per-second", 10);
        Deque<Long> times = fixTimes.computeIfAbsent(attacker.getUniqueId(), k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        while (!times.isEmpty() && now - times.peekFirst() > 1000) times.pollFirst();
        if (times.size() >= max) return false;
        times.addLast(now);

        // Real attack: fires EntityDamageByEntityEvent, so knockback, enchants,
        // other plugins and the combo counter all behave normally.
        attacker.attack(target);
        return true;
    }

    private double reachTo(Player attacker, Player target) {
        Vector eye = attacker.getEyeLocation().toVector();
        BoundingBox bb = target.getBoundingBox();
        double x = Math.max(bb.getMinX(), Math.min(eye.getX(), bb.getMaxX()));
        double y = Math.max(bb.getMinY(), Math.min(eye.getY(), bb.getMaxY()));
        double z = Math.max(bb.getMinZ(), Math.min(eye.getZ(), bb.getMaxZ()));
        return eye.distance(new Vector(x, y, z));
    }

    // ------------------------------------------------------------------
    // Alerts (ops / permission holders)
    // ------------------------------------------------------------------
    private void alert(Player attacker, Player target, boolean fixed) {
        if (!plugin.enabled("alerts")) return;

        FileConfiguration cfg = plugin.getConfig();
        long now = System.currentTimeMillis();
        long cooldown = cfg.getLong("detector.alert-cooldown-ms", 1000);
        Long last = lastAlert.get(attacker.getUniqueId());
        if (last != null && now - last < cooldown) return;
        lastAlert.put(attacker.getUniqueId(), now);

        String format = cfg.getString("alert-format",
                "{prefix} &7» &c{attacker} &7ghost hit &f{target} &8[{status}&8] &7ping &f{ping}ms &7reach &f{reach}");
        String message = plugin.color(format
                .replace("{prefix}", plugin.prefix())
                .replace("{attacker}", attacker.getName())
                .replace("{target}", target.getName())
                .replace("{status}", fixed ? "&aAuto-fixed" : "&cNot fixed")
                .replace("{ping}", String.valueOf(attacker.getPing()))
                .replace("{reach}", String.format(Locale.ROOT, "%.2f", reachTo(attacker, target))));

        for (Player online : Bukkit.getOnlinePlayers()) {
            boolean allowed = online.isOp() || online.hasPermission("nerithmchitsync.alerts");
            if (allowed && !mutedAlerts.contains(online.getUniqueId())) {
                online.sendMessage(message);
            }
        }
        Bukkit.getConsoleSender().sendMessage(message);
    }

    /** @return true if alerts are now ON for this player */
    public boolean toggleAlerts(Player p) {
        if (mutedAlerts.remove(p.getUniqueId())) return true;
        mutedAlerts.add(p.getUniqueId());
        return false;
    }

    // ------------------------------------------------------------------
    // Combo helpers (used by WallGuard too)
    // ------------------------------------------------------------------
    public boolean hasActiveCombo(Player attacker) {
        Combo c = combos.get(attacker.getUniqueId());
        int window = plugin.getConfig().getInt("combo.window-ticks", 30);
        return c != null && Bukkit.getCurrentTick() - c.last <= window;
    }

    /** True if this hit would be a follow-up hit of an already running combo. */
    public boolean isComboHit(Player attacker, Player victim) {
        Combo c = combos.get(attacker.getUniqueId());
        if (c == null || !c.target.equals(victim.getUniqueId())) return false;
        int window = plugin.getConfig().getInt("combo.window-ticks", 30);
        int min = plugin.getConfig().getInt("wall-protection.min-combo-hits", 1);
        return Bukkit.getCurrentTick() - c.last <= window && c.count >= min;
    }

    private boolean isPvpMode(Player p) {
        GameMode g = p.getGameMode();
        return g == GameMode.SURVIVAL || g == GameMode.ADVENTURE;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        attempts.remove(id);
        combos.remove(id);
        fixTimes.remove(id);
        lastAlert.remove(id);
    }
}
