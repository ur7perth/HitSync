package com.nerith.hitsync;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HitTracker implements Listener {

    private record Snapshot(
            int tick,
            BoundingBox box,
            Vector eye,
            Vector direction,
            boolean flying,
            boolean gliding
    ) {}

    private record Attempt(
            UUID target,
            int tick,
            boolean willAttack,
            boolean cancelled,
            double distance,
            boolean airborne,
            int targetNoDamageTicks,
            int targetMaxNoDamageTicks
    ) {}

    private final NerithMcHitSync plugin;

    private final Map<UUID, Deque<Snapshot>> history =
            new ConcurrentHashMap<>();

    private final Map<UUID, Deque<Attempt>> pending =
            new ConcurrentHashMap<>();

    private final Map<UUID, int[]> stats =
            new ConcurrentHashMap<>();

    private final Map<UUID, Long> lastAlert =
            new ConcurrentHashMap<>();

    private final Map<UUID, Boolean> mutedAlerts =
            new ConcurrentHashMap<>();

    private int snapshotTask = -1;

    public HitTracker(NerithMcHitSync plugin) {
        this.plugin = plugin;

        snapshotTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                plugin,
                this::takeSnapshots,
                1L,
                1L
        );
    }

    private void takeSnapshots() {
        int tick = Bukkit.getCurrentTick();

        for (Player player : Bukkit.getOnlinePlayers()) {

            Deque<Snapshot> snapshots = history.computeIfAbsent(
                    player.getUniqueId(),
                    ignored -> new ArrayDeque<>()
            );

            Location eye = player.getEyeLocation();

            snapshots.addLast(new Snapshot(
                    tick,
                    player.getBoundingBox().clone(),
                    eye.toVector(),
                    eye.getDirection().clone(),
                    player.isFlying(),
                    player.isGliding()
            ));

            while (snapshots.size() > 40) {
                snapshots.removeFirst();
            }
        }
    }

    private Snapshot snapshotAt(UUID playerId, int wantedTick) {
        Deque<Snapshot> snapshots = history.get(playerId);

        if (snapshots == null || snapshots.isEmpty()) {
            return null;
        }

        Snapshot before = null;
        Snapshot after = null;

        for (Snapshot snapshot : snapshots) {

            if (snapshot.tick() <= wantedTick) {
                before = snapshot;
            }

            if (snapshot.tick() >= wantedTick) {
                after = snapshot;
                break;
            }
        }

        if (before == null) {
            return snapshots.peekFirst();
        }

        if (after == null || after.tick() == before.tick()) {
            return before;
        }

        double fraction = (wantedTick - before.tick())
                / (double) (after.tick() - before.tick());

        BoundingBox box = interpolate(
                before.box(),
                after.box(),
                fraction
        );

        Vector eye = before.eye().clone().add(
                after.eye().clone()
                        .subtract(before.eye())
                        .multiply(fraction)
        );

        Vector direction = before.direction().clone()
                .multiply(1.0 - fraction)
                .add(after.direction().clone().multiply(fraction));

        if (direction.lengthSquared() > 1.0E-8) {
            direction.normalize();
        }

        return new Snapshot(
                wantedTick,
                box,
                eye,
                direction,
                before.flying() || after.flying(),
                before.gliding() || after.gliding()
        );
    }

    private BoundingBox interpolate(
            BoundingBox a,
            BoundingBox b,
            double f
    ) {
        return BoundingBox.of(
                new Vector(
                        lerp(a.getMinX(), b.getMinX(), f),
                        lerp(a.getMinY(), b.getMinY(), f),
                        lerp(a.getMinZ(), b.getMinZ(), f)
                ),
                new Vector(
                        lerp(a.getMaxX(), b.getMaxX(), f),
                        lerp(a.getMaxY(), b.getMaxY(), f),
                        lerp(a.getMaxZ(), b.getMaxZ(), f)
                )
        );
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = false
    )
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        if (!plugin.enabled("detector")) return;

        Player attacker = event.getPlayer();
        Entity entity = event.getAttacked();

        if (!(entity instanceof Player target)) return;

        if (!isPvpMode(attacker) || !isPvpMode(target)) return;

        if (attacker.getWorld() != target.getWorld()) return;

        int tick = Bukkit.getCurrentTick();

        Snapshot attackerSnap = snapshotAt(
                attacker.getUniqueId(),
                tick
        );

        Snapshot targetSnap = snapshotAt(
                target.getUniqueId(),
                tick
        );

        double distance = attacker.getEyeLocation()
                .distance(target.getLocation());

        boolean airborne = attacker.isFlying()
                || attacker.isGliding()
                || target.isFlying()
                || target.isGliding();

        if (attackerSnap != null && targetSnap != null) {

            double rewind = Math.min(
                    Math.max(attacker.getPing(), 0),
                    plugin.getConfig().getDouble(
                            "detector.max-rewind-ms",
                            300
                    )
            ) / 50.0;

            int rewindTicks = (int) Math.round(rewind);

            Snapshot oldTarget = snapshotAt(
                    target.getUniqueId(),
                    tick - rewindTicks
            );

            Snapshot oldAttacker = snapshotAt(
                    attacker.getUniqueId(),
                    tick - rewindTicks
            );

            if (oldTarget != null && oldAttacker != null) {

                distance = rayDistance(
                        oldAttacker.eye(),
                        oldAttacker.direction(),
                        oldTarget.box(),
                        plugin.getConfig().getDouble(
                                "detector.reach",
                                3.6
                        )
                );

                airborne = oldAttacker.flying()
                        || oldAttacker.gliding()
                        || oldTarget.flying()
                        || oldTarget.gliding();
            }
        }

        Attempt attempt = new Attempt(
                target.getUniqueId(),
                tick,
                event.willAttack(),
                event.isCancelled(),
                distance,
                airborne,
                target.getNoDamageTicks(),
                target.getMaximumNoDamageTicks()
        );

        Deque<Attempt> queue = pending.computeIfAbsent(
                attacker.getUniqueId(),
                ignored -> new ArrayDeque<>()
        );

        queue.addLast(attempt);

        while (queue.size() > 12) {
            queue.removeFirst();
        }

        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> evaluate(attacker.getUniqueId(), attempt),
                2L
        );
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = false
    )
    public void onDamage(EntityDamageByEntityEvent event) {

        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }

        if (!(event.getEntity() instanceof Player target)) {
            return;
        }

        Deque<Attempt> queue = pending.get(attacker.getUniqueId());

        if (queue == null) return;

        Attempt matched = null;

        for (Attempt attempt : queue) {

            if (attempt.target().equals(target.getUniqueId())
                    && Bukkit.getCurrentTick() - attempt.tick() <= 2) {

                matched = attempt;
                break;
            }
        }

        if (matched == null) return;

        queue.remove(matched);

        if (queue.isEmpty()) {
            pending.remove(attacker.getUniqueId(), queue);
        }

        if (event.isCancelled()) {
            increment(attacker.getUniqueId(), 2);
        }
    }

    private void evaluate(UUID attackerId, Attempt attempt) {

        Deque<Attempt> queue = pending.get(attackerId);

        if (queue == null || !queue.remove(attempt)) {
            return;
        }

        if (queue.isEmpty()) {
            pending.remove(attackerId, queue);
        }

        UUID targetId = attempt.target();

        Player attacker = Bukkit.getPlayer(attackerId);
        Player target = Bukkit.getPlayer(targetId);

        if (attacker == null || target == null
                || !attacker.isOnline()
                || !target.isOnline()
                || target.isDead()) {
            return;
        }

        if (attacker.getWorld() != target.getWorld()) {
            return;
        }

        if (attempt.cancelled() || !attempt.willAttack()) {
            return;
        }

        int half = attempt.targetMaxNoDamageTicks() / 2;

        if (attempt.targetNoDamageTicks() > half) {
            increment(attackerId, 1);
            return;
        }

        increment(attackerId, 0);

        if (plugin.getConfig().getBoolean("debug", false)) {

            plugin.getLogger().info(
                    "Unregistered attack candidate: "
                            + attacker.getName()
                            + " -> "
                            + target.getName()
                            + " | ping="
                            + attacker.getPing()
                            + "ms | distance="
                            + format(attempt.distance())
                            + " | airborne="
                            + attempt.airborne()
            );
        }

        alert(attacker, target, attempt);
    }

    private double rayDistance(
            Vector origin,
            Vector direction,
            BoundingBox targetBox,
            double reach
    ) {
        if (direction.lengthSquared() < 1.0E-8) {
            return -1;
        }

        BoundingBox expanded = targetBox.clone().expand(
                plugin.getConfig().getDouble(
                        "detector.aim-leniency",
                        0.10
                )
        );

        if (expanded.contains(origin)) {
            return 0;
        }

        RayTraceResult result = expanded.rayTrace(
                origin,
                direction,
                reach
        );

        return result == null
                ? -1
                : result.getHitPosition().distance(origin);
    }

    private void increment(UUID id, int index) {
        int[] values = stats.computeIfAbsent(
                id,
                ignored -> new int[3]
        );

        values[index]++;
    }

    private void alert(
            Player attacker,
            Player target,
            Attempt attempt
    ) {
        if (!plugin.enabled("alerts")) return;

        long now = System.currentTimeMillis();

        long cooldown = plugin.getConfig().getLong(
                "detector.alert-cooldown-ms",
                1500
        );

        Long previous = lastAlert.get(attacker.getUniqueId());

        if (cooldown > 0
                && previous != null
                && now - previous < cooldown) {
            return;
        }

        lastAlert.put(attacker.getUniqueId(), now);

        String format = plugin.getConfig().getString(
                "alert-format",
                "{prefix} &7- &c{attacker} &7unregistered hit on &f{target}"
        );

        String message = plugin.color(
                format
                        .replace("{prefix}", plugin.prefix())
                        .replace("{attacker}", attacker.getName())
                        .replace("{target}", target.getName())
                        .replace("{ping}", String.valueOf(attacker.getPing()))
                        .replace("{reach}", format(attempt.distance()))
                        .replace(
                                "{movement}",
                                attempt.airborne() ? "airborne" : "ground"
                        )
        );

        for (Player online : Bukkit.getOnlinePlayers()) {

            if ((online.isOp()
                    || online.hasPermission("nerithmchitsync.alerts"))
                    && !mutedAlerts.getOrDefault(
                            online.getUniqueId(),
                            false
                    )) {

                online.sendMessage(message);
            }
        }

        Bukkit.getConsoleSender().sendMessage(message);
    }

    private static String format(double value) {
        return Double.isFinite(value) && value >= 0
                ? String.format(java.util.Locale.ROOT, "%.2f", value)
                : "n/a";
    }

    public boolean toggleAlerts(Player player) {

        boolean next = mutedAlerts.getOrDefault(
                player.getUniqueId(),
                false
        );

        mutedAlerts.put(player.getUniqueId(), !next);

        return next;
    }

    public int ghosts(UUID id) {
        int[] values = stats.get(id);
        return values == null ? 0 : values[0];
    }

    public int fixes(UUID id) {
        int[] values = stats.get(id);
        return values == null ? 0 : values[1];
    }

    public int blocked(UUID id) {
        int[] values = stats.get(id);
        return values == null ? 0 : values[2];
    }

    private boolean isPvpMode(Player player) {

        GameMode mode = player.getGameMode();

        return mode == GameMode.SURVIVAL
                || mode == GameMode.ADVENTURE;
    }

    public void shutdown() {

        if (snapshotTask != -1) {
            Bukkit.getScheduler().cancelTask(snapshotTask);
        }

        history.clear();
        pending.clear();
        stats.clear();
        lastAlert.clear();
        mutedAlerts.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {

        UUID id = event.getPlayer().getUniqueId();

        history.remove(id);
        pending.remove(id);
        stats.remove(id);
        lastAlert.remove(id);
        mutedAlerts.remove(id);

        for (Map.Entry<UUID, Deque<Attempt>> entry : pending.entrySet()) {

            Deque<Attempt> queue = entry.getValue();

            queue.removeIf(attempt -> attempt.target().equals(id));

            if (queue.isEmpty()) {
                pending.remove(entry.getKey(), queue);
            }
        }
    }
}
