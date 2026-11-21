
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
    private final Map<UUID, Combo> combos = new HashMap<>();
    private final Map<UUID, Long> lastAlert = new HashMap<>();
    private final Map<UUID, Integer> lastSwing = new HashMap<>();
    private final Map<UUID, Deque<Snap>> history = new HashMap<>();
    private final Map<UUID, int[]> stats = new HashMap<>();
    private final Set<UUID> mutedAlerts = new HashSet<>();

    public HitTracker(NerithMcHitSync plugin) {
        this.plugin = plugin;

        Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::snapshot,
                1L,
                1L
        );
    }

    // --------------------------------------------------
    // Position history
    // --------------------------------------------------

    private void snapshot() {
        int now = Bukkit.getCurrentTick();

        for (Player p : Bukkit.getOnlinePlayers()) {
            Deque<Snap> d = history.computeIfAbsent(
                    p.getUniqueId(),
                    k -> new ArrayDeque<>()
            );

            d.addLast(new Snap(now, p.getBoundingBox()));

            while (d.size() > 24) {
                d.pollFirst();
            }
        }

        attempts.entrySet().removeIf(
                entry -> now - entry.getValue() > 40
        );
    }

    private BoundingBox boxAt(UUID id, double tick) {
        Deque<Snap> d = history.get(id);

        if (d == null || d.isEmpty()) {
            return null;
        }

        Snap prev = null;
        Snap next = null;

        for (Snap s : d) {
            if (s.tick() <= tick) {
                prev = s;
            } else {
                next = s;
                break;
            }
        }

        if (prev == null) {
            return d.peekFirst().box();
        }

        if (next == null) {
            return prev.box();
        }

        double span = next.tick() - prev.tick();
        double f = (tick - prev.tick()) / span;

        BoundingBox a = prev.box();
        BoundingBox b = next.box();

        Vector min = new Vector(
                lerp(a.getMinX(), b.getMinX(), f),
                lerp(a.getMinY(), b.getMinY(), f),
                lerp(a.getMinZ(), b.getMinZ(), f)
        );

        Vector max = new Vector(
                lerp(a.getMaxX(), b.getMaxX(), f),
                lerp(a.getMaxY(), b.getMaxY(), f),
                lerp(a.getMaxZ(), b.getMaxZ(), f)
        );

        return BoundingBox.of(min, max);
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    // --------------------------------------------------
    // Attack tracking
    // --------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreAttack(PrePlayerAttackEntityEvent e) {

        if (!e.willAttack()) {
            return;
        }

        if (!(e.getAttacked() instanceof Player target)) {
            return;
        }

        attempts.put(
                new AttackKey(
                        e.getPlayer().getUniqueId(),
                        target.getUniqueId()
                ),
                Bukkit.getCurrentTick()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageByEntityEvent e) {

        if (!(e.getDamager() instanceof Player attacker)) {
            return;
        }

        if (e.getCause() != DamageCause.ENTITY_ATTACK) {
            return;
        }

        if (!(e.getEntity() instanceof Player victim)) {
            return;
        }

        int now = Bukkit.getCurrentTick();

        UUID attackerId = attacker.getUniqueId();
        UUID victimId = victim.getUniqueId();

        attempts.put(
                new AttackKey(attackerId, victimId),
                now
        );

        if (e.isCancelled()) {
            return;
        }

        int window = plugin.getConfig().getInt(
                "combo.window-ticks", 30
        );

        Combo c = combos.get(attackerId);

        if (c != null
                && c.target.equals(victimId)
                && now - c.last <= window) {

            c.count++;
            c.last = now;

        } else {
            combos.put(
                    attackerId,
                    new Combo(victimId, 1, now)
            );
        }
    }

    // --------------------------------------------------
    // Left click detection
    // --------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent e) {

        if (!plugin.enabled("detector")) {
            return;
        }

        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }

        Player attacker = e.getPlayer();

        if (attacker.isDead() || !isPvpMode(attacker)) {
            return;
        }

        int now = Bukkit.getCurrentTick();
        UUID attackerId = attacker.getUniqueId();

        Integer previous = lastSwing.put(attackerId, now);

        if (previous != null && previous == now) {
            return;
        }

        FileConfiguration cfg = plugin.getConfig();

        double minStrength = cfg.getDouble(
                "detector.min-attack-strength", 0.0
        );

        if (attacker.getCooledAttackStrength(0f) < minStrength) {
            return;
        }

        double reach = cfg.getDouble(
                "detector.reach", 3.6
        );

        double size = cfg.getDouble(
                "detector.aim-leniency", 0.12
        );

        if (hasActiveCombo(attacker)) {
            size += cfg.getDouble(
                    "detector.combo-leniency-bonus", 0.08
            );
        }

        Location eye = attacker.getEyeLocation();

        Vector start = eye.toVector();
        Vector dir = eye.getDirection();

        double maxMs = cfg.getDouble(
                "detector.max-rewind-ms", 350
        );

        double pingMs = Math.min(
                Math.max(attacker.getPing(), 0),
                maxMs
        );

        double interp = cfg.getDouble(
                "detector.client-interp-ticks", 1.0
        );

        double rewind = pingMs / 50.0 + interp;

        double jitter = cfg.getDouble(
                "detector.rewind-jitter-ticks", 1.5
        );

        Player best = null;
        double bestDist = Double.MAX_VALUE;

        for (Player target : attacker.getWorld().getPlayers()) {

            if (target.equals(attacker)) {
                continue;
            }

            if (target.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }

            if (!attacker.canSee(target)) {
                continue;
            }

            if (target.getLocation().distanceSquared(eye) > 144) {
                continue;
            }

            double distance = rayDistance(
                    start,
                    dir,
                    target,
                    now - rewind,
                    jitter,
                    size,
                    reach
            );

            if (distance >= 0 && distance < bestDist) {
                bestDist = distance;
                best = target;
            }
        }

        if (best == null) {
            return;
        }

        if (cfg.getBoolean("detector.block-check", true)) {

            if (bestDist > 0.1) {

                RayTraceResult block = attacker.getWorld().rayTraceBlocks(
                        eye,
                        dir,
                        bestDist,
                        org.bukkit.FluidCollisionMode.NEVER,
                        true
                );

                if (block != null) {
                    return;
                }
            }
        }

        UUID targetId = best.getUniqueId();
        double distance = bestDist;

        long delay = Math.max(
                1,
                cfg.getInt("detector.check-delay-ticks", 1)
        );

        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> verify(attackerId, targetId, now, distance),
                delay
        );
    }

    // --------------------------------------------------
    // Ray tracing
    // --------------------------------------------------

    private double rayDistance(
            Vector start,
            Vector dir,
            Player target,
            double tick,
            double jitter,
            double size,
            double reach
    ) {

        List<BoundingBox> boxes = new ArrayList<>();

        boxes.add(target.getBoundingBox());

        UUID id = target.getUniqueId();

        double[] offsets = {
                -jitter,
                -jitter / 2.0,
                0,
                jitter / 2.0,
                jitter
        };

        for (double offset : offsets) {

            BoundingBox box = boxAt(id, tick + offset);

            if (box != null) {
                boxes.add(box);
            }
        }

        double best = -1;

        for (BoundingBox box : boxes) {

            BoundingBox expanded = box.clone().expand(size);

            double distance;

            if (expanded.contains(start)) {
                distance = 0;
            } else {

                RayTraceResult result = expanded.rayTrace(
                        start,
                        dir,
                        reach
                );

                if (result == null) {
                    continue;
                }

                distance = result.getHitPosition().distance(start);
            }

            if (best < 0 || distance < best) {
                best = distance;
            }
        }

        return best;
    }

    // --------------------------------------------------
    // Verification
    // --------------------------------------------------

    private void verify(
            UUID attackerId,
            UUID targetId,
            int swingTick,
            double distance
    ) {

        Player attacker = Bukkit.getPlayer(attackerId);
        Player target = Bukkit.getPlayer(targetId);

        if (attacker == null || target == null) {
            return;
        }

        if (!attacker.isOnline() || !target.isOnline()) {
            return;
        }

        FileConfiguration cfg = plugin.getConfig();

        int window = cfg.getInt(
                "detector.processed-window-ticks", 1
        );

        AttackKey key = new AttackKey(attackerId, targetId);
        Integer attemptTick = attempts.get(key);

        if (attemptTick != null
                && attemptTick >= swingTick - window
                && attemptTick <= swingTick + window + 2) {
            return;
        }

        if (target.isDead() || !isPvpMode(target)) {
            return;
        }

        if (target.isInvulnerable()) {
            return;
        }

        if (!attacker.getWorld().equals(target.getWorld())) {
            return;
        }

        double tolerance = cfg.getInt(
                "auto-fix.iframe-tolerance-ticks", 2
        );

        double half = target.getMaximumNoDamageTicks() / 2.0;
        double remaining = target.getNoDamageTicks() - half;

        boolean blocked = remaining > tolerance;

        boolean treatInvulnerabilityAsGhost = cfg.getBoolean(
                "detector.treat-invulnerability-as-ghost",
                false
        );

        if (blocked && !treatInvulnerabilityAsGhost) {
            return;
        }

        // لا نكرر الضرر برمجياً.
        boolean fixed = false;

        int[] stat = stats.computeIfAbsent(
                attackerId,
                k -> new int[2]
        );

        stat[0]++;

        // إرسال بيانات GhostHit إلى البلوقن الثاني.
        submitToProcessor(
                attacker,
                target,
                swingTick,
                distance
        );

        alert(attacker, target, fixed, distance);
    }

    // --------------------------------------------------
    // NerithHitProcessor communication
    // --------------------------------------------------

    private void submitToProcessor(
            Player attacker,
            Player target,
            int tick,
            double distance
    ) {

        org.bukkit.plugin.Plugin processor =
                Bukkit.getPluginManager().getPlugin(
                        "NerithHitProcessor"
                );

        if (processor == null || !processor.isEnabled()) {
            return;
        }

        try {

            processor.getClass()
                    .getMethod(
                            "submitGhostHit",
                            UUID.class,
                            UUID.class,
                            int.class,
                            double.class
                    )
                    .invoke(
                            processor,
                            attacker.getUniqueId(),
                            target.getUniqueId(),
                            tick,
                            distance
                    );

        } catch (ReflectiveOperationException ex) {

            plugin.getLogger().warning(
                    "Could not submit GhostHit to NerithHitProcessor: "
                            + ex.getMessage()
            );
        }
    }

    // --------------------------------------------------
    // Alerts
    // --------------------------------------------------

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private void alert(
            Player attacker,
            Player target,
            boolean fixed,
            double distance
    ) {

        if (!plugin.enabled("alerts")) {
            return;
        }

        FileConfiguration cfg = plugin.getConfig();

        long now = System.currentTimeMillis();

        long cooldown = cfg.getLong(
                "detector.alert-cooldown-ms", 0
        );

        Long last = lastAlert.get(attacker.getUniqueId());

        if (cooldown > 0
                && last != null
                && now - last < cooldown) {
            return;
        }

        lastAlert.put(attacker.getUniqueId(), now);

        String format = cfg.getString(
                "alert-format",
                DEFAULT_FORMAT
        );

        String status = fixed
                ? "&aAuto-fixed"
                : "&cDetected";

        String message = format
                .replace("{prefix}", plugin.prefix())
                .replace("{attacker}", attacker.getName())
                .replace("{target}", target.getName())
                .replace("{status}", status)
                .replace("{ping}", String.valueOf(attacker.getPing()))
                .replace("{reach}", fmt(distance))
                .replace("{distance}", fmt(distance))
                .replace("{movement}", "N/A");

        message = plugin.color(message);

        for (Player online : Bukkit.getOnlinePlayers()) {

            boolean allowed = online.isOp()
                    || online.hasPermission("nerithmchitsync.alerts");

            if (allowed
                    && !mutedAlerts.contains(online.getUniqueId())) {

                online.sendMessage(message);
            }
        }

        Bukkit.getConsoleSender().sendMessage(message);
    }

    // --------------------------------------------------
    // Public methods
    // --------------------------------------------------

    public boolean toggleAlerts(Player player) {

        if (mutedAlerts.remove(player.getUniqueId())) {
            return true;
        }

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
                "combo.window-ticks", 30
        );

        return combo != null
                && Bukkit.getCurrentTick() - combo.last <= window;
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private boolean isPvpMode(Player player) {

        GameMode mode = player.getGameMode();

        return mode == GameMode.SURVIVAL
                || mode == GameMode.ADVENTURE;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {

        UUID id = e.getPlayer().getUniqueId();

        attempts.keySet().removeIf(
                key -> key.attacker().equals(id)
                        || key.target().equals(id)
        );

        combos.remove(id);
        lastAlert.remove(id);
        lastSwing.remove(id);
        history.remove(id);
        stats.remove(id);
        mutedAlerts.remove(id);
    }

    public void shutdown() {

        attempts.clear();
        combos.clear();
        lastAlert.clear();
        lastSwing.clear();
        history.clear();
        stats.clear();
        mutedAlerts.clear();
    }
}
