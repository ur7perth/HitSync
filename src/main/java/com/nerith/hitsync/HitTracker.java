package com.nerith.hitsync;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
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
 * Part 2: re-applies the hit with real damage (attribute + sharpness + strength) and knockback.
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
    // Part 2: real damage + knockback
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

        double damage = computeDamage(attacker, cfg);

        // The player's client already had the hit, so the old i-frames are treated as expired.
        if (cfg.getBoolean("auto-fix.reset-iframes", true)) {
            target.setNoDamageTicks(0);
        }

        // Fires EntityDamageByEntityEvent (other plugins, wall guard and the combo counter all see it).
        target.damage(damage, attacker);

        boolean landed = target.getNoDamageTicks() > target.getMaximumNoDamageTicks() / 2.0;
        if (!landed) return false;

        applyExtras(attacker, target, cfg);
        return true;
    }

    /** Damage from the attack attribute (includes Strength / Weakness / weapon) + Sharpness + crit. */
    private double computeDamage(Player attacker, FileConfiguration cfg) {
        AttributeInstance attr = attacker.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);
        double base = attr != null ? attr.getValue() : 1.0;

        ItemStack item = attacker.getInventory().getItemInMainHand();
        double enchant = 0;
        if (item != null && !item.getType().isAir()) {
            int sharp = item.getEnchantmentLevel(Enchantment.SHARPNESS);
            if (sharp > 0) enchant += 0.5 * sharp + 0.5;
        }

        double scale = 1.0;
        if (cfg.getBoolean("auto-fix.use-cooldown", false)) {
            scale = attacker.getCooledAttackStrength(0.5f);
            base *= 0.2 + scale * scale * 0.8;
            enchant *= scale;
        }

        if (cfg.getBoolean("auto-fix.crits", true) && scale > 0.9 && isCrit(attacker)) {
            base *= 1.5;
        }
        return Math.max(0.0, base + enchant);
    }

    private boolean isCrit(Player p) {
        return p.getFallDistance() > 0.0f
                && !p.isOnGround()
                && !p.isClimbing()
                && !p.isInWater()
                && !p.isSprinting()
                && !p.isInsideVehicle()
                && !p.hasPotionEffect(PotionEffectType.BLINDNESS);
    }

    /** Knockback enchant, sprint knockback, attack-knockback attribute and Fire Aspect. */
    private void applyExtras(Player attacker, Player target, FileConfiguration cfg) {
        ItemStack item = attacker.getInventory().getItemInMainHand();

        double kb = 0;
        AttributeInstance kbAttr = attacker.getAttribute(Attribute.GENERIC_ATTACK_KNOCKBACK);
        if (kbAttr != null) kb += kbAttr.getValue();
        if (item != null && !item.getType().isAir()) {
            kb += item.getEnchantmentLevel(Enchantment.KNOCKBACK);
            int fire = item.getEnchantmentLevel(Enchantment.FIRE_ASPECT);
            if (fire > 0) target.setFireTicks(Math.max(target.getFireTicks(), fire * 80));
        }
        if (attacker.isSprinting()) kb += 1;

        double mult = cfg.getDouble("auto-fix.knockback-multiplier", 1.0);
        if (kb > 0 && cfg.getBoolean("auto-fix.extra-knockback", true)) {
            Vector dir = target.getLocation().toVector()
                    .subtract(attacker.getLocation().toVector())
                    .setY(0);
            if (dir.lengthSquared() > 1.0E-6) {
                dir.normalize().multiply(kb * 0.5 * mult);
                Vector v = target.getVelocity().add(dir);
                v.setY(Math.min(0.4, Math.max(v.getY(), 0.1)));
                target.setVelocity(v);
            }
        }
    }

    // ------------------------------------------------------------------
    // Alerts (ops / permission holders)
    // ------------------------------------------------------------------
    private void alert(Player attacker, Player target, boolean fixed, double dist) {
        if (!plugin.enabled("alerts")) return;

        FileConfiguration cfg = plugin.getConfig();
        long now = System.currentTimeMillis();
        long cooldown = cfg.getLong("detector.alert-cooldown-ms", 0);
        Long last = lastAlert.get(attacker.getUniqueId());
        if (cooldown > 0 && last != null && now - last < cooldown) return;
        lastAlert.put(attacker.getUniqueId(), now);

        String format = cfg.getString("alert-format",
                "{prefix} &7» &c{attacker} &7ghost hit &f{target} &8[{status}&8] &7ping &f{ping}ms &7reach &f{reach}");
        String message = plugin.color(format
                .replace("{prefix}", plugin.prefix())
                .replace("{attacker}", attacker.getName())
                .replace("{target}", target.getName())
                .replace("{status}", fixed ? "&aAuto-fixed" : "&cNot fixed")
                .replace("{ping}", String.valueOf(attacker.getPing()))
                .replace("{reach}", String.format(Locale.ROOT, "%.2f", dist)));

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
        return Bukkit.getCurrentTick() - c.last <= window && c.count >= 1;
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
        lastSwing.remove(id);
        history.remove(id);
    }
}
