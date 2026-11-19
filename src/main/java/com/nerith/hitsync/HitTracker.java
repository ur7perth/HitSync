package com.nerith.hitsync;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
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

public final class HitTracker implements Listener {

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

    private static final String DEFAULT_FORMAT =
            "{prefix} &7- &c{attacker} &7ghost hit &f{target} "
            + "&8[{status}&8] "
            + "&7ping &f{ping}ms "
            + "&7reach &f{reach}";

    private final NerithMcHitSync plugin;
    private final NamespacedKey speedKey;
    private final Map<UUID, Integer> attempts = new HashMap<>();
    private final Map<UUID, Combo> combos = new HashMap<>();
    private final Map<UUID, Deque<Long>> fixTimes = new HashMap<>();
    private final Map<UUID, Long> lastAlert = new HashMap<>();
    private final Map<UUID, Integer> lastSwing = new HashMap<>();
    private final Map<UUID, Deque<Snap>> history = new HashMap<>();
    private final Map<UUID, int[]> stats = new HashMap<>();
    private final Set<UUID> mutedAlerts = new HashSet<>();

    public HitTracker(NerithMcHitSync plugin) {
        this.plugin = plugin;
        this.speedKey = new NamespacedKey(plugin, "fullcooldown");
        Bukkit.getScheduler()
                .runTaskTimer(plugin, this::snapshot, 1L, 1L);
    }

    // ------------------------------------------------------------
    // Position history (lag compensation)
    // ------------------------------------------------------------
    private void snapshot() {
        int now = Bukkit.getCurrentTick();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Deque<Snap> d = history.computeIfAbsent(
                    p.getUniqueId(), k -> new ArrayDeque<>());
            d.addLast(new Snap(now, p.getBoundingBox()));
            while (d.size() > 24) d.pollFirst();
        }
    }

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
        double span = next.tick() - prev.tick();
        double f = (t - prev.tick()) / span;
        BoundingBox a = prev.box();
        BoundingBox b = next.box();
        Vector min = new Vector(
                lerp(a.getMinX(), b.getMinX(), f),
                lerp(a.getMinY(), b.getMinY(), f),
                lerp(a.getMinZ(), b.getMinZ(), f));
        Vector max = new Vector(
                lerp(a.getMaxX(), b.getMaxX(), f),
                lerp(a.getMaxY(), b.getMaxY(), f),
                lerp(a.getMaxZ(), b.getMaxZ(), f));
        return BoundingBox.of(min, max);
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    // ------------------------------------------------------------
    // Records every attack the server really received
    // ------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreAttack(PrePlayerAttackEntityEvent e) {
        attempts.put(e.getPlayer().getUniqueId(),
                Bukkit.getCurrentTick());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (e.getCause() != DamageCause.ENTITY_ATTACK) return;

        int now = Bukkit.getCurrentTick();
        UUID aId = attacker.getUniqueId();
        attempts.put(aId, now);

        if (e.isCancelled()) return;
        if (!(e.getEntity() instanceof Player victim)) return;

        UUID vId = victim.getUniqueId();
        int window = plugin.getConfig().getInt("combo.window-ticks", 30);
        Combo c = combos.get(aId);
        if (c != null && c.target.equals(vId)
                && now - c.last <= window) {
            c.count++;
            c.last = now;
        } else {
            combos.put(aId, new Combo(vId, 1, now));
        }
    }

    // ------------------------------------------------------------
    // Reads every swing and checks what the crosshair is on
    // ------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent e) {
        if (!plugin.enabled("detector")) return;
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }

        Player attacker = e.getPlayer();
        if (attacker.isDead() || !isPvpMode(attacker)) return;

        int now = Bukkit.getCurrentTick();
        UUID aId = attacker.getUniqueId();

        Integer prevSwing = lastSwing.put(aId, now);
        if (prevSwing != null && prevSwing == now) return;

        FileConfiguration cfg = plugin.getConfig();
        double minStrength =
                cfg.getDouble("detector.min-attack-strength", 0.0);
        if (attacker.getCooledAttackStrength(0f) < minStrength) return;

        double reach = cfg.getDouble("detector.reach", 3.6);
        double size = cfg.getDouble("detector.aim-leniency", 0.12);
        if (hasActiveCombo(attacker)) {
            size += cfg.getDouble(
                    "detector.combo-leniency-bonus", 0.08);
        }

        Location eye = attacker.getEyeLocation();
        Vector start = eye.toVector();
        Vector dir = eye.getDirection();

        double maxMs = cfg.getDouble("detector.max-rewind-ms", 350);
        double pingMs = Math.min(Math.max(attacker.getPing(), 0), maxMs);
        double interp = cfg.getDouble("detector.client-interp-ticks", 1.0);
        double rewind = pingMs / 50.0 + interp;
        double jitter = cfg.getDouble("detector.rewind-jitter-ticks", 1.5);

        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player pl : attacker.getWorld().getPlayers()) {
            if (pl.equals(attacker)) continue;
            if (pl.getGameMode() == GameMode.SPECTATOR) continue;
            if (!attacker.canSee(pl)) continue;
            if (pl.getLocation().distanceSquared(eye) > 144) continue;
            double d = rayDistance(start, dir, pl, now - rewind,
                    jitter, size, reach);
            if (d >= 0 && d < bestDist) {
                bestDist = d;
                best = pl;
            }
        }
        if (best == null) return;

        if (cfg.getBoolean("detector.block-check", true)
                && bestDist > 0.1) {
            Object block = attacker.getWorld().rayTraceBlocks(
                    eye, dir, bestDist,
                    FluidCollisionMode.NEVER, true);
            if (block != null) return;
        }

        final UUID tId = best.getUniqueId();
        final double dist = bestDist;
        long delay = Math.max(1,
                cfg.getInt("detector.check-delay-ticks", 1));

        Bukkit.getScheduler().runTaskLater(plugin,
                () -> verify(aId, tId, now, dist), delay);
    }

    private double rayDistance(Vector start, Vector dir, Player target,
                               double t, double jitter, double size,
                               double reach) {
        List<BoundingBox> boxes = new ArrayList<>();
        boxes.add(target.getBoundingBox());
        UUID id = target.getUniqueId();
        double[] offsets = {
            -jitter, -jitter / 2.0, 0, jitter / 2.0, jitter
        };
        for (double dt : offsets) {
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
        if (attacker == null || target == null) return;
        if (!attacker.isOnline() || !target.isOnline()) return;

        FileConfiguration cfg = plugin.getConfig();
        int window = cfg.getInt("detector.processed-window-ticks", 1);
        Integer at = attempts.get(aId);
        if (at != null && at >= swingTick - window) return;

        if (target.isDead() || !isPvpMode(target)) return;
        if (target.isInvulnerable()) return;
        if (!attacker.getWorld().equals(target.getWorld())) return;

        double tolerance =
                cfg.getInt("auto-fix.iframe-tolerance-ticks", 2);
        double half = target.getMaximumNoDamageTicks() / 2.0;
        double remaining = target.getNoDamageTicks() - half;
        boolean blocked = remaining > tolerance;
        boolean asGhost = cfg.getBoolean(
                "detector.treat-invulnerability-as-ghost", false);
        if (blocked && !asGhost) return;

        boolean fixed = false;
        if (plugin.enabled("auto-fix") && !blocked) {
            fixed = tryFix(attacker, target, dist);
        }

        int[] st = stats.computeIfAbsent(aId, k -> new int[2]);
        st[0]++;
        if (fixed) st[1]++;
        alert(attacker, target, fixed, dist);
    }

    // ------------------------------------------------------------
    // Auto fix: real vanilla attack + knockback guarantee
    // ------------------------------------------------------------
    private boolean tryFix(Player attacker, Player target, double dist) {
        if (!attacker.hasPermission("nerithmchitsync.autofix")) {
            return false;
        }
        if (attacker.isDead() || !isPvpMode(attacker)) return false;

        FileConfiguration cfg = plugin.getConfig();
        if (dist > cfg.getDouble("auto-fix.max-reach", 3.9)) {
            return false;
        }

        int max = cfg.getInt("auto-fix.max-fixes-per-second", 20);
        Deque<Long> times = fixTimes.computeIfAbsent(
                attacker.getUniqueId(), k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        while (!times.isEmpty() && now - times.peekFirst() > 1000) {
            times.pollFirst();
        }
        if (times.size() >= max) return false;
        times.addLast(now);

        if (cfg.getBoolean("auto-fix.reset-iframes", true)) {
            target.setNoDamageTicks(0);
            target.setLastDamage(0);
        }

        double before = target.getHealth() + target.getAbsorptionAmount();

        AttributeInstance speed =
                attacker.getAttribute(Attribute.GENERIC_ATTACK_SPEED);
        AttributeModifier mod = null;
        if (speed != null && cfg.getBoolean("auto-fix.full-cooldown", true)) {
            for (AttributeModifier old
                    : new ArrayList<>(speed.getModifiers())) {
                if (speedKey.equals(old.getKey())) {
                    speed.removeModifier(old);
                }
            }
            mod = new AttributeModifier(speedKey, 500,
                    AttributeModifier.Operation.ADD_NUMBER);
            speed.addTransientModifier(mod);
        }
        try {
            attacker.attack(target);
        } finally {
            if (speed != null && mod != null) speed.removeModifier(mod);
        }

        double after = target.getHealth() + target.getAbsorptionAmount();
        boolean landed = after < before || target.getNoDamageTicks() > 0;

        if (landed) applyKnockback(attacker, target, cfg);

        if (cfg.getBoolean("debug", false)) {
            String msg = "[HitSync debug] "
                    + attacker.getName() + " -> " + target.getName()
                    + " | hp " + fmt(before) + " -> " + fmt(after)
                    + " | landed=" + landed
                    + " | iframes=" + target.getNoDamageTicks()
                    + " | reach=" + fmt(dist);
            Bukkit.getConsoleSender().sendMessage(msg);
        }
        return landed;
    }

    private void applyKnockback(Player attacker, Player target,
                                FileConfiguration cfg) {
        double mult = cfg.getDouble("auto-fix.knockback-multiplier", 1.0);
        double minH = cfg.getDouble("auto-fix.min-horizontal-kb", 0.32);
        double minV = cfg.getDouble("auto-fix.min-vertical-kb", 0.30);

        Vector v = target.getVelocity();
        v.setX(v.getX() * mult);
        v.setZ(v.getZ() * mult);

        double h = Math.hypot(v.getX(), v.getZ());
        if (minH > 0 && h < minH) {
            Vector dir = target.getLocation().toVector()
                    .subtract(attacker.getLocation().toVector());
            dir.setY(0);
            if (dir.lengthSquared() > 1.0E-6) {
                dir.normalize().multiply(minH);
                v.setX(dir.getX());
                v.setZ(dir.getZ());
            }
        }
        if (minV > 0 && v.getY() < minV) v.setY(minV);
        target.setVelocity(v);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // ------------------------------------------------------------
    // Alerts
    // ------------------------------------------------------------
    private void alert(Player attacker, Player target,
                       boolean fixed, double dist) {
        if (!plugin.enabled("alerts")) return;

        FileConfiguration cfg = plugin.getConfig();
        long now = System.currentTimeMillis();
        long cooldown = cfg.getLong("detector.alert-cooldown-ms", 0);
        Long last = lastAlert.get(attacker.getUniqueId());
        if (cooldown > 0 && last != null && now - last < cooldown) {
            return;
        }
        lastAlert.put(attacker.getUniqueId(), now);

        String format = cfg.getString("alert-format", DEFAULT_FORMAT);
        String status = fixed ? "&aAuto-fixed" : "&cNot fixed";
        String text = format
                .replace("{prefix}", plugin.prefix())
                .replace("{attacker}", attacker.getName())
                .replace("{target}", target.getName())
                .replace("{status}", status)
                .replace("{ping}", String.valueOf(attacker.getPing()))
                .replace("{reach}", fmt(dist));
        String message = plugin.color(text);

        for (Player online : Bukkit.getOnlinePlayers()) {
            boolean allowed = online.isOp()
                    || online.hasPermission("nerithmchitsync.alerts");
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

    public int ghosts(UUID id) {
        int[] s = stats.get(id);
        return s == null ? 0 : s[0];
    }

    public int fixes(UUID id) {
        int[] s = stats.get(id);
        return s == null ? 0 : s[1];
    }

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------
    public boolean hasActiveCombo(Player attacker) {
        Combo c = combos.get(attacker.getUniqueId());
        int window = plugin.getConfig().getInt("combo.window-ticks", 30);
        return c != null && Bukkit.getCurrentTick() - c.last <= window;
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
