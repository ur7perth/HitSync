package com.nerith.hitsync;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
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
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Part 1: detects ghost hits using lag-compensated (rewound + interpolated) target positions.
 * Part 2: re-applies the hit with the real vanilla attack (attacker.attack(target)).
 */
public final class HitTracker implements Listener {

    private record Attempt(int tick) {}

    private record Snap(int tick, BoundingBox box) {}

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
    private final Map<UUID, Integer> lastSwing = new HashMap<>();
    private final Map<UUID, Deque<Snap>> history = new HashMap<>();
    private final Set<UUID> mutedAlerts = new HashSet<>();

    public HitTracker(NerithMcHitSync plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::snapshot, 1L, 1L);
    }

    // ------------------------------------------------------------------
    // Position history (for lag compensation)
    // ------------------------------------------------------------------
    private void snapshot() {
        int now = Bukkit.getCurrentTick();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Deque<Snap> d = history.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
            d.addLast(new Snap(now, p.getBoundingBox()));
            while (d.size() > 20) d.pollFirst();
        }
    }

    /** Interpolated bounding box of a player at a (fractional) past tick. */
    private BoundingBox boxAt(UUID id, double t) {
        Deque<Snap> d = history.get(id);
        if (d == null || d.isEmpty()) return null;
        Snap prev = null;
        Snap next = null;
        for (Snap s : d) {
            if (s.tick() <= t) {
                prev = s;
            } else {
                next = s;
                break;
            }
        }
        if (prev == null) return d.peekFirst().box();
        if (next == null) return prev.box();
        double f = (t - prev.tick()) / (double) (next.tick() - prev.tick());
        BoundingBox a = prev.box();
        BoundingBox b = next.box();
        return BoundingBox.of(
                new Vector(lerp(a.getMinX(), b.getMinX(), f), lerp(a.getMinY(), b.getMinY(), f), lerp(a.getMinZ(), b.getMinZ(), f)),
                new Vector(lerp(a.getMaxX(), b.getMaxX(), f), lerp(a.getMaxY(), b.getMaxY(), f), lerp(a.getMaxZ(), b.getMaxZ(), f)));
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
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
        attempts.put(aId, new Attempt(now));
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
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent e) {
        if (!plugin.enabled("detector")) return;
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;

        Player attacker = e.getPlayer();
        if (attacker.isDead() || !isPvpMode(attacker)) return;

        int now = Bukkit.getCurrentTick();
        UUID aId = attacker.getUniqueId();

        // one swing per tick
        Integer prevSwing = lastSwing.put(aId, now);
        if (prevSwing != null && prevSwing == now) return;

        FileConfiguration cfg = plugin.getConfig();
        if (attacker.getCooledAttackStrength(0f) < cfg.getDouble("detector.min-attack-strength", 0.0)) return;

        double reach = cfg.getDouble("detector.reach", 3.5);
        double size = cfg.getDouble("detector.aim-leniency", 0.10);
        if (hasActiveCombo(attacker)) {
            size += cfg.getDouble("detector.combo-leniency-bonus", 0.10);
        }

        Location eye = attacker.getEyeLocation();
        Vector start = eye.toVector();
        Vector dir = eye.getDirection();

        double maxRewindMs = cfg.getDouble("detector.max-rewind-ms", 300);
        double pingMs = Math.min(Math.max(attacker.getPing(), 0), maxRewindMs);
        double rewindTicks = pingMs / 50.0 + cfg.getDouble("detector.client-interp-ticks", 1.0);
        double jitter = cfg.getDouble("detector.rewind-jitter-ticks", 1.0);

        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player pl : attacker.getWorld().getPlayers()) {
            if (pl.equals(attacker) || pl.getGameMode() == GameMode.SPECTATOR || !attacker.canSee(pl)) continue;
            if (pl.getLocation().distanceSquared(eye) > 144) continue;
            double d = rayDistance(start, dir, pl, now - rewindTicks, jitter, size, reach);
            if (d >= 0 && d < bestDist) {
                bestDist = d;
                best = pl;
            }
        }
        if (best == null) return;

        // A block between the attacker and the target means it was not a real hit attempt.
        if (bestDist > 0.1 && attacker.getWorld().rayTraceBlocks(
                eye, dir, bestDist, FluidCollisionMode.NEVER, true) != null) {
            return;
        }

        final UUID tId = best.getUniqueId();
        final double dist = bestDist;
        long delay = Math.max(1, cfg.getInt("detector.check-delay-ticks", 1));

        Bukkit.getScheduler().runTaskLater(plugin, () -> verify(aId, tId, now, dist), delay);
    }

    /** Distance to the target along the ray (current + rewound positions), or -1 if missed. */
    private double rayDistance(Vector start, Vector dir, Player target, double t,
                               double jitter, double size, double reach) {
        List<BoundingBox> boxes = new ArrayList<>();
        boxes.add(target.getBoundingBox());
        UUID id = target.getUniqueId();
        for (double dt : new double[]{-jitter, 0, jitter}) {
            BoundingBox b = boxAt(id, t + dt);
            if (b != null) boxes.add(b);
        }

        double best = -1;
        for (BoundingBox b : boxes) {
            BoundingBox big = b.clone().expand(size);
            double dist;
            if (big.contains(start)) {
                dist = 0;
            } else {
                RayTraceResult r = big.rayTrace(start, dir, reach);
                if (r == null) continue;
                dist = r.getHitPosition().distance(start);
            }
            if (best < 0 || dist < best) best = dist;
        }
        return best;
    }

    private void verify(UUID aId, UUID tId, int swingTick, double dist) {
        Player attacker = Bukkit.getPlayer(aId);
        Player target = Bukkit.getPlayer(tId);
        if (attacker == null || target == null || !attacker.isOnline() || !target.isOnline()) return;

        // One swing = one attack. If the server processed an attack for this swing, it is not a ghost.
        Attempt at = attempts.get(aId);
        if (at != null && at.tick() >= swingTick - 1) return;

        if (target.isDead() || !isPvpMode(target) || target.isInvulnerable()) return;
        if (!attacker.getWorld().equals(target.getWorld())) return;

        FileConfiguration cfg = plugin.getConfig();
        double tolerance = cfg.getInt("auto-fix.iframe-tolerance-ticks", 2);
        double remaining = target.getNoDamageTicks() - target.getMaximumNoDamageTicks() / 2.0;
        boolean blockedByIframes = remaining > tolerance;
        if (blockedByIframes && !cfg.getBoolean("detector.treat-invulnerability-as-ghost", false)) return;

        boolean fixed = false;
        if (plugin.enabled("auto-fix") && !blockedByIframes) {
            fixed = tryFix(attacker, target, dist);
        }
        alert(attacker, target, fixed, dist);
    }

    // ------------------------------------------------------------------
    // Part 2: real vanilla attack
    // ------------------------------------------------------------------
    private boolean tryFix(Player attacker, Player target, double dist) {
        if (!attacker.hasPermission("nerithmchitsync.autofix")) return false;
        if (attacker.isDead() || !isPvpMode(attacker)) return false;

        FileConfiguration cfg = plugin.getConfig();
        if (dist > cfg.getDouble("auto-fix.max-reach", 3.8)) return false;

        int max = cfg.getInt("auto-fix.max-fixes-per-second", 20);
        Deque<Long> times = fixTimes.computeIfAbsent(attacker.getUniqueId(), k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        while (!times.isEmpty() && now - times.peekFirst() > 1000) times.pollFirst();
        if (times.size() >= max) return false;
        times.addLast(now);

        // The attacker's client already had the hit, so old i-frames are treated as expired.
        if (cfg.getBoolean("auto-fix.reset-iframes", true)) {
            target.setNoDamageTicks(0);
            target.setLastDamage(0);
        }

        double before = target.getHealth() + target.getAbsorptionAmount();

        // Full attack cooldown for this one attack (no damage scaling), removed right after.
        AttributeInstance speed = attacker.getAttribute(Attribute.GENERIC_ATTACK_SPEED);
        AttributeModifier mod = null;
        if (speed != null && cfg.getBoolean("auto-fix.full-cooldown", true)) {
            mod = new AttributeModifier(new NamespacedKey(plugin, "fullcooldown"),
                    500, AttributeModifier.Operation.ADD_NUMBER);
            speed.addTransientModifier(mod);
        }
        try {
            // Real vanilla attack: attribute damage (Strength/Weakness/weapon), Sharpness,
            // crit, Knockback, sprint knockback, Fire Aspect, and every other plugin's events.
            attacker.attack(target);
        } finally {
            if (speed != null && mod != null) speed.removeModifier(mod);
        }

        double after = target.getHealth() + target.getAbsorptionAmount();
        boolean landed = after < before;

        double mult = cfg.getDouble("auto-fix.knockback-multiplier", 1.0);
        if (landed && mult != 1.0) {
            Vector v = target.getVelocity();
            v.setX(v.getX() * mult);
            v.setZ(v.getZ() * mult);
            target.setVelocity(v);
        }

        if (cfg.getBoolean("debug", false)) {
            Bukkit.getConsoleSender().sendMessage(String.format(Locale.ROOT,
                    "[HitSync debug] %s -> %s | hp %.2f ->
