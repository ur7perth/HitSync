package com.nerith.hitsync;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
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
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

public final class HitTracker implements Listener {

    private record Snap(int tick, BoundingBox box) {}
    private record AttackKey(UUID attacker, UUID target) {}

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
            + "&8[{status}&8] &7ping &f{ping}ms "
            + "&7reach &f{reach}";

    private final NerithMcHitSync plugin;

    private final Map<AttackKey, Integer> attempts = new HashMap<>();
    private final Map<AttackKey, Integer> successfulDamage = new HashMap<>();
    private final Map<UUID, Combo> combos = new HashMap<>();
    private final Map<UUID, Long> lastAlert = new HashMap<>();
    private final Map<UUID, Integer> lastSwing = new HashMap<>();
    private final Map<UUID, Deque<Snap>> history = new HashMap<>();
    private final Map<UUID, int[]> stats = new HashMap<>();
    private final Set<UUID> mutedAlerts = new HashSet<>();

    private final BukkitTask snapshotTask;

    public HitTracker(NerithMcHitSync plugin) {
        this.plugin = plugin;
        this.snapshotTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::snapshot, 1L, 1L
        );
    }

    private void snapshot() {
        int now = Bukkit.getCurrentTick();

        for (Player player : Bukkit.getOnlinePlayers()) {
            Deque<Snap> snapshots = history.computeIfAbsent(
                    player.getUniqueId(), k -> new ArrayDeque<>()
            );

            snapshots.addLast(new Snap(now, player.getBoundingBox()));

            while (snapshots.size() > 24) {
                snapshots.pollFirst();
            }
        }

        attempts.entrySet().removeIf(e -> now - e.getValue() > 40);
        successfulDamage.entrySet().removeIf(e -> now - e.getValue() > 40);
    }

    private BoundingBox boxAt(UUID id, double tick) {
        Deque<Snap> snapshots = history.get(id);

        if (snapshots == null || snapshots.isEmpty()) return null;

        Snap previous = null;
        Snap next = null;

        for (Snap snap : snapshots) {
            if (snap.tick() <= tick) {
                previous = snap;
            } else {
                next = snap;
                break;
            }
        }

        if (previous == null) return snapshots.peekFirst().box();
        if (next == null) return previous.box();

        double span = next.tick() - previous.tick();
        if (span <= 0) return previous.box();

        double factor = (tick - previous.tick()) / span;

        BoundingBox a = previous.box();
        BoundingBox b = next.box();

        Vector min = new Vector(
                lerp(a.getMinX(), b.getMinX(), factor),
                lerp(a.getMinY(), b.getMinY(), factor),
                lerp(a.getMinZ(), b.getMinZ(), factor)
        );

        Vector max = new Vector(
                lerp(a.getMaxX(), b.getMaxX(), factor),
                lerp(a.getMaxY(), b.getMaxY(), factor),
                lerp(a.getMaxZ(), b.getMaxZ(), factor)
        );

        return BoundingBox.of(min, max);
    }

    private static double lerp(double a, double b, double factor) {
        return a + (b - a) * factor;
    }

    // Attack tracking

    private void recordAttempt(UUID attackerId, UUID targetId, int tick) {
        AttackKey key = new AttackKey(attackerId, targetId);

        int maxPending = Math.max(1,
                plugin.getConfig().getInt("detector.max-pending-attempts", 20));

        if (!attempts.containsKey(key)) {
            long count = attempts.keySet().stream()
                    .filter(k -> k.attacker().equals(attackerId))
                    .count();

            if (count >= maxPending) {
                AttackKey oldest = attempts.entrySet().stream()
                        .filter(e -> e.getKey().attacker().equals(attackerId))
                        .min(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse(null);

                if (oldest != null) attempts.remove(oldest);
            }
        }

        attempts.put(key, tick);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        if (!plugin.enabled("detector") || !event.willAttack()) return;
        if (!(event.getAttacked() instanceof Player target)) return;

        Player attacker = event.getPlayer();
        if (attacker.equals(target)) return;

        recordAttempt(attacker.getUniqueId(), target.getUniqueId(),
                Bukkit.getCurrentTick());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (event.getCause() != DamageCause.ENTITY_ATTACK) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        int now = Bukkit.getCurrentTick();
        UUID attackerId = attacker.getUniqueId();
        UUID victimId = victim.getUniqueId();

        AttackKey key = new AttackKey(attackerId, victimId);
        recordAttempt(attackerId, victimId, now);

        if (event.isCancelled() || event.getFinalDamage() <= 0) return;

        successfulDamage.put(key, now);

        int window = Math.max(1,
                plugin.getConfig().getInt("combo.window-ticks", 30));

        Combo combo = combos.get(attackerId);

        if (combo != null && combo.target.equals(victimId)
                && now - combo.last <= window) {
            combo.count++;
            combo.last = now;
        } else {
            combos.put(attackerId, new Combo(victimId, 1, now));
        }
    }

    // Left click detection

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent event) {
        if (!plugin.enabled("detector")) return;
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;

        Player attacker = event.getPlayer();
        if (attacker.isDead() || !isPvpMode(attacker)) return;

        int now = Bukkit.getCurrentTick();
        UUID attackerId = attacker.getUniqueId();

        Integer previous = lastSwing.put(attackerId, now);
        if (previous != null && previous == now) return;

        FileConfiguration config = plugin.getConfig();

        double minStrength = config.getDouble(
                "detector.min-attack-strength", 0.0);

        if (attacker.getCooledAttackStrength(0f) < minStrength) return;

        double reach = config.getDouble("detector.reach", 3.6);
        double size = config.getDouble("detector.aim-leniency", 0.12);

        if (hasActiveCombo(attacker)) {
            size += config.getDouble("detector.combo-leniency-bonus", 0.08);
        }

        Location eye = attacker.getEyeLocation();
        Vector start = eye.toVector();
        Vector direction = eye.getDirection();

        double maxPing = config.getDouble("detector.max-rewind-ms", 350);
        double ping = Math.min(Math.max(attacker.getPing(), 0), maxPing);

        double interpolation = config.getDouble(
                "detector.client-interp-ticks", 1.0);

        double rewind = ping / 50.0 + interpolation;
        double jitter = config.getDouble(
                "detector.rewind-jitter-ticks", 1.5);

        Player bestTarget = null;
        double bestDistance = Double.MAX_VALUE;

        for (Player target : attacker.getWorld().getPlayers()) {
            if (target.equals(attacker)) continue;
            if (target.getGameMode() == GameMode.SPECTATOR) continue;
            if (!attacker.canSee(target)) continue;
            if (target.getLocation().distanceSquared(eye) > 144) continue;

            double distance = rayDistance(
                    start, direction, target,
                    now - rewind, jitter, size, reach
            );

            if (distance >= 0 && distance < bestDistance) {
                bestDistance = distance;
                bestTarget = target;
            }
        }

        if (bestTarget == null) return;

        if (config.getBoolean("detector.block-check", true)
                && bestDistance > 0.1) {
            RayTraceResult block = attacker.getWorld().rayTraceBlocks(
                    eye, direction, bestDistance,
                    org.bukkit.FluidCollisionMode.NEVER, true
            );

            if (block != null) return;
        }

        UUID targetId = bestTarget.getUniqueId();
        double distance = bestDistance;

        long delay = Math.max(1,
                config.getInt("detector.check-delay-ticks", 2));

        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> verify(attackerId, targetId, now, distance),
                delay
        );
    }

    // Ray tracing

    private double rayDistance(
            Vector start,
            Vector direction,
            Player target,
            double tick,
            double jitter,
            double size,
            double reach
    ) {
        List<BoundingBox> boxes = new ArrayList<>();
        boxes.add(target.getBoundingBox());

        double[] offsets = {
                -jitter, -jitter / 2.0, 0, jitter / 2.0, jitter
        };

        for (double offset : offsets) {
            BoundingBox box = boxAt(target.getUniqueId(), tick + offset);
            if (box != null) boxes.add(box);
        }

        double best = -1;

        for (BoundingBox box : boxes) {
            BoundingBox expanded = box.clone().expand(size);
            double distance;

            if (expanded.contains(start)) {
                distance = 0;
            } else {
                RayTraceResult result = expanded.rayTrace(
                        start, direction, reach
                );

                if (result == null) continue;
                distance = result.getHitPosition().distance(start);
            }

            if (best < 0 || distance < best) best = distance;
        }

        return best;
    }

    // Verification

    private void verify(
            UUID attackerId,
            UUID targetId,
            int swingTick,
            double distance
    ) {
        Player attacker = Bukkit.getPlayer(attackerId);
        Player target = Bukkit.getPlayer(targetId);

        if (attacker == null || target == null) return;
        if (!attacker.isOnline() || !target.isOnline()) return;
        if (target.isDead() || !isPvpMode(target)) return;
        if (target.isInvulnerable()) return;
        if (!attacker.getWorld().equals(target.getWorld())) return;

        FileConfiguration config = plugin.getConfig();

        int window = Math.max(0,
                config.getInt("detector.processed-window-ticks", 2));

        AttackKey key = new AttackKey(attackerId, targetId);

        Integer damageTick = successfulDamage.get(key);

        if (damageTick != null
                && damageTick >= swingTick - window
                && damageTick <= swingTick + window + 2) {
            return;
        }

        Integer attemptTick = attempts.get(key);

        if (attemptTick != null
                && attemptTick >= swingTick - window
                && attemptTick <= swingTick + window + 2) {

            double tolerance = config.getDouble(
                    "detector.iframe-tolerance-ticks", 2.0);

            double half = target.getMaximumNoDamageTicks() / 2.0;
            double remaining = target.getNoDamageTicks() - half;

            boolean blocked = remaining > tolerance;

            boolean treatAsGhost = config.getBoolean(
                    "detector.treat-invulnerability-as-ghost", false);

            if (!blocked || !treatAsGhost) return;
        }

        int[] stat = stats.computeIfAbsent(attackerId, k -> new int[2]);
        stat[0]++;

        alert(attacker, target, false, distance);
    }

    // Alerts

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private void alert(
            Player attacker,
            Player target,
            boolean fixed,
            double distance
    ) {
        if (!plugin.enabled("alerts")) return;

        FileConfiguration config = plugin.getConfig();
        long now = System.currentTimeMillis();

        long cooldown = config.getLong(
                "detector.alert-cooldown-ms", 500);

        Long last = lastAlert.get(attacker.getUniqueId());

        if (cooldown > 0 && last != null && now - last < cooldown) return;

        lastAlert.put(attacker.getUniqueId(), now);

        String format = config.getString("alert-format", DEFAULT_FORMAT);

        String status = fixed ? "&aAuto-fixed" : "&cDetected";

        String message = format
                .replace("{prefix}", plugin.prefix())
                .replace("{attacker}", attacker.getName())
                .replace("{target}", target.getName())
                .replace("{status}", status)
                .replace("{ping}", String.valueOf(attacker.getPing()))
                .replace("{reach}", fmt(distance))
                .replace("{movement}", "N/A");

        message = plugin.color(message);

        for (Player online : Bukkit.getOnlinePlayers()) {
            boolean allowed = online.isOp()
                    || online.hasPermission("nerithmchitsync.alerts");

            if (allowed && !mutedAlerts.contains(online.getUniqueId())) {
                online.sendMessage(message);
            }
        }

        Bukkit.getConsoleSender().sendMessage(message);
    }

    // Public methods

    public boolean toggleAlerts(Player player) {
        if (mutedAlerts.remove(player.getUniqueId())) return true;
        mutedAlerts.add(player.getUniqueId());
        return false;
    }

    public boolean cancelled(UUID id) {
        return mutedAlerts.contains(id);
    }

    public int ghosts(UUID id) {
        int[] stat = stats.get(id);
        return stat == null ? 0 : stat[0];
    }

    public int fixes(UUID id) {
        int[] stat = stats.get(id);
        return stat == null ? 0 : stat[1];
    }

    public boolean hasActiveCombo(Player attacker) {
        Combo combo = combos.get(attacker.getUniqueId());

        int window = plugin.getConfig().getInt(
                "combo.window-ticks", 30);

        return combo != null
                && Bukkit.getCurrentTick() - combo.last <= window;
    }

    private boolean isPvpMode(Player player) {
        GameMode mode = player.getGameMode();

        return mode == GameMode.SURVIVAL
                || mode == GameMode.ADVENTURE;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();

        attempts.keySet().removeIf(
                key -> key.attacker().equals(id) || key.target().equals(id));

        successfulDamage.keySet().removeIf(
                key -> key.attacker().equals(id) || key.target().equals(id));

        combos.remove(id);
        lastAlert.remove(id);
        lastSwing.remove(id);
        history.remove(id);
        stats.remove(id);
        mutedAlerts.remove(id);
    }

    public void shutdown() {
        snapshotTask.cancel();

        attempts.clear();
        successfulDamage.clear();
        combos.clear();
        lastAlert.clear();
        lastSwing.clear();
        history.clear();
        stats.clear();
        mutedAlerts.clear();
    }
}
