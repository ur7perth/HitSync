package com.nerith.hitsync;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class HitTracker implements Listener {

    private static final class Attempt {

        private final UUID attacker;
        private final UUID target;

        private final int tick;
        private final double distance;

        private final boolean attackerFlying;
        private final boolean targetFlying;

        private final Vector attackerVelocity;
        private final Vector targetVelocity;

        private boolean damageSeen;
        private boolean damageCancelled;

        private Attempt(
                Player attacker,
                Player target,
                int tick
        ) {
            this.attacker = attacker.getUniqueId();
            this.target = target.getUniqueId();

            this.tick = tick;

            this.distance = attacker.getLocation()
                    .distance(target.getLocation());

            this.attackerFlying =
                    attacker.isGliding() || attacker.isFlying();

            this.targetFlying =
                    target.isGliding() || target.isFlying();

            this.attackerVelocity =
                    attacker.getVelocity().clone();

            this.targetVelocity =
                    target.getVelocity().clone();
        }
    }

    private final NerithMcHitSync plugin;

    /*
     * Each attacker has their own pending attempts.
     * Each attempt also stores the exact target UUID.
     */
    private final Map<UUID, Deque<Attempt>> pending =
            new HashMap<>();

    private final Map<UUID, Long> lastAlert =
            new HashMap<>();

    private final Map<UUID, Deque<Long>> fixTimes =
            new HashMap<>();

    private final Map<UUID, Boolean> mutedAlerts =
            new HashMap<>();

    /*
     * stats:
     * [0] = unregistered attack candidates
     * [1] = successful retry attempts
     * [2] = cancelled damage events
     */
    private final Map<UUID, int[]> stats =
            new HashMap<>();

    public HitTracker(NerithMcHitSync plugin) {
        this.plugin = plugin;
    }

    // --------------------------------------------------
    // Actual attack event
    // --------------------------------------------------

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = false
    )
    public void onPreAttack(PrePlayerAttackEntityEvent event) {

        if (!plugin.enabled("detector")) {
            return;
        }

        Player attacker = event.getPlayer();

        Entity attacked = event.getAttacked();

        if (!(attacked instanceof Player target)) {
            return;
        }

        if (attacker.equals(target)) {
            return;
        }

        if (!isPvpMode(attacker) || !isPvpMode(target)) {
            return;
        }

        if (!attacker.getWorld().equals(target.getWorld())) {
            return;
        }

        if (attacker.isDead() || target.isDead()) {
            return;
        }

        if (!event.willAttack() || event.isCancelled()) {
            return;
        }

        int tick = Bukkit.getCurrentTick();

        Attempt attempt = new Attempt(
                attacker,
                target,
                tick
        );

        Deque<Attempt> attempts = pending.computeIfAbsent(
                attacker.getUniqueId(),
                ignored -> new ArrayDeque<>()
        );

        attempts.addLast(attempt);

        int maxPending = plugin.getConfig().getInt(
                "detector.max-pending-attempts",
                20
        );

        while (attempts.size() > maxPending) {
            attempts.removeFirst();
        }

        long delay = Math.max(
                1,
                plugin.getConfig().getLong(
                        "detector.check-delay-ticks",
                        2
                )
        );

        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> evaluate(attempt),
                delay
        );
    }

    // --------------------------------------------------
    // Real damage event
    // --------------------------------------------------

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

        Deque<Attempt> attempts = pending.get(
                attacker.getUniqueId()
        );

        if (attempts == null) {
            return;
        }

        int now = Bukkit.getCurrentTick();

        Iterator<Attempt> iterator = attempts.descendingIterator();

        while (iterator.hasNext()) {

            Attempt attempt = iterator.next();

            if (!attempt.target.equals(target.getUniqueId())) {
                continue;
            }

            int age = now - attempt.tick;

            if (age < 0 || age > 3) {
                continue;
            }

            attempt.damageSeen = true;
            attempt.damageCancelled = event.isCancelled();

            if (event.isCancelled()) {
                increment(attacker.getUniqueId(), 2);
            }

            return;
        }
    }

    // --------------------------------------------------
    // Evaluate the attempt
    // --------------------------------------------------

    private void evaluate(Attempt attempt) {

        removeAttempt(attempt);

        if (attempt.damageSeen) {
            return;
        }

        Player attacker = Bukkit.getPlayer(
                attempt.attacker
        );

        Player target = Bukkit.getPlayer(
                attempt.target
        );

        if (attacker == null || target == null) {
            return;
        }

        if (!attacker.isOnline() || !target.isOnline()) {
            return;
        }

        if (attacker.isDead() || target.isDead()) {
            return;
        }

        if (!isPvpMode(attacker) || !isPvpMode(target)) {
            return;
        }

        if (!attacker.getWorld().equals(target.getWorld())) {
            return;
        }

        if (attempt.damageCancelled) {
            return;
        }

        if (target.isInvulnerable()) {
            return;
        }

        /*
         * Do not treat ordinary vanilla invulnerability frames
         * as a ghost hit by default.
         */
        int tolerance = plugin.getConfig().getInt(
                "detector.iframe-tolerance-ticks",
                2
        );

        double half =
                target.getMaximumNoDamageTicks() / 2.0;

        double remaining =
                target.getNoDamageTicks() - half;

        boolean iframeBlocked = remaining > tolerance;

        if (iframeBlocked && !plugin.getConfig().getBoolean(
                "detector.treat-invulnerability-as-ghost",
                false
        )) {
            return;
        }

        double distance = attempt.distance;

        double maxDistance = plugin.getConfig().getDouble(
                "auto-fix.max-reach",
                3.9
        );

        /*
         * Fast Elytra movement is recorded separately.
         * The detector does not reject a valid attack just because
         * one or both players are gliding.
         */

        boolean airborne =
                attempt.attackerFlying || attempt.targetFlying;

        increment(attacker.getUniqueId(), 0);

        boolean fixed = false;

        if (plugin.enabled("auto-fix")
                && !iframeBlocked
                && distance <= maxDistance) {

            fixed = tryFix(
                    attacker,
                    target
            );
        }

        if (fixed) {
            increment(attacker.getUniqueId(), 1);
        }

        alert(
                attacker,
                target,
                fixed,
                distance,
                airborne,
                attempt
        );

        if (plugin.getConfig().getBoolean("debug", false)) {

            plugin.getLogger().info(
                    "Attack candidate: "
                            + attacker.getName()
                            + " -> "
                            + target.getName()
                            + " | distance="
                            + format(distance)
                            + " | airborne="
                            + airborne
                            + " | attackerSpeed="
                            + format(attempt.attackerVelocity.length())
                            + " | targetSpeed="
                            + format(attempt.targetVelocity.length())
                            + " | fixed="
                            + fixed
            );
        }
    }

    // --------------------------------------------------
    // Remove pending attempt
    // --------------------------------------------------

    private void removeAttempt(Attempt attempt) {

        Deque<Attempt> attempts = pending.get(
                attempt.attacker
        );

        if (attempts == null) {
            return;
        }

        attempts.remove(attempt);

        if (attempts.isEmpty()) {
            pending.remove(attempt.attacker);
        }
    }

    // --------------------------------------------------
    // Optional retry
    // --------------------------------------------------

    private boolean tryFix(
            Player attacker,
            Player target
    ) {

        if (!plugin.getConfig().getBoolean(
                "auto-fix.enabled",
                true
        )) {
            return false;
        }

        if (!attacker.hasPermission(
                "nerithmchitsync.autofix"
        )) {
            return false;
        }

        if (!attacker.isOnline()
                || !target.isOnline()
                || attacker.isDead()
                || target.isDead()) {
            return false;
        }

        if (!isPvpMode(attacker) || !isPvpMode(target)) {
            return false;
        }

        if (!attacker.getWorld().equals(target.getWorld())) {
            return false;
        }

        double maxReach = plugin.getConfig().getDouble(
                "auto-fix.max-reach",
                3.9
        );

        double distance = attacker.getLocation()
                .distance(target.getLocation());

        if (distance > maxReach) {
            return false;
        }

        /*
         * Rate limit retries per attacker.
         */
        int maxFixes = plugin.getConfig().getInt(
                "auto-fix.max-fixes-per-second",
                10
        );

        Deque<Long> times = fixTimes.computeIfAbsent(
                attacker.getUniqueId(),
                ignored -> new ArrayDeque<>()
        );

        long now = System.currentTimeMillis();

        while (!times.isEmpty()
                && now - times.peekFirst() > 1000) {
            times.removeFirst();
        }

        if (times.size() >= maxFixes) {
            return false;
        }

        times.addLast(now);

        /*
         * Reset iframes only when explicitly enabled.
         */
        if (plugin.getConfig().getBoolean(
                "auto-fix.reset-iframes",
                false
        )) {
            target.setNoDamageTicks(0);
        }

        double before =
                target.getHealth()
                        + target.getAbsorptionAmount();

        /*
         * Use Bukkit's normal attack handling.
         * This does not directly manufacture damage.
         */
        attacker.attack(target);

        double after =
                target.getHealth()
                        + target.getAbsorptionAmount();

        return after < before;
    }

    // --------------------------------------------------
    // Alerts
    // --------------------------------------------------

    private void alert(
            Player attacker,
            Player target,
            boolean fixed,
            double distance,
            boolean airborne,
            Attempt attempt
    ) {

        if (!plugin.enabled("alerts")) {
            return;
        }

        long now = System.currentTimeMillis();

        long cooldown = plugin.getConfig().getLong(
                "detector.alert-cooldown-ms",
                500
        );

        Long previous = lastAlert.get(
                attacker.getUniqueId()
        );

        if (cooldown > 0
                && previous != null
                && now - previous < cooldown) {
            return;
        }

        lastAlert.put(attacker.getUniqueId(), now);

        String format = plugin.getConfig().getString(
                "alert-format",
                "{prefix} &7- &c{attacker} &7ghost hit &f{target}"
        );

        String movement = airborne
                ? "AIRBORNE"
                : "GROUND";

        String status = fixed
                ? "&aRETRIED"
                : "&cNOT FIXED";

        String message = plugin.color(
                format
                        .replace("{prefix}", plugin.prefix())
                        .replace("{attacker}", attacker.getName())
                        .replace("{target}", target.getName())
                        .replace("{status}", status)
                        .replace("{ping}", String.valueOf(
                                attacker.getPing()
                        ))
                        .replace("{reach}", format(distance))
                        .replace("{movement}", movement)
                        .replace("{attacker-speed}", format(
                                attempt.attackerVelocity.length()
                        ))
                        .replace("{target-speed}", format(
                                attempt.targetVelocity.length()
                        ))
        );

        for (Player online : Bukkit.getOnlinePlayers()) {

            boolean allowed = online.isOp()
                    || online.hasPermission(
                            "nerithmchitsync.alerts"
                    );

            boolean muted = mutedAlerts.getOrDefault(
                    online.getUniqueId(),
                    false
            );

            if (allowed && !muted) {
                online.sendMessage(message);
            }
        }

        Bukkit.getConsoleSender().sendMessage(message);
    }

    // --------------------------------------------------
    // Alerts toggle
    // --------------------------------------------------

    public boolean toggleAlerts(Player player) {

        UUID id = player.getUniqueId();

        boolean muted = mutedAlerts.getOrDefault(
                id,
                false
        );

        mutedAlerts.put(id, !muted);

        return muted;
    }

    // --------------------------------------------------
    // Stats
    // --------------------------------------------------

    private void increment(UUID id, int index) {

        int[] values = stats.computeIfAbsent(
                id,
                ignored -> new int[3]
        );

        values[index]++;
    }

    public int ghosts(UUID id) {
        int[] values = stats.get(id);
        return values == null ? 0 : values[0];
    }

    public int fixes(UUID id) {
        int[] values = stats.get(id);
        return values == null ? 0 : values[1];
    }

    public int cancelled(UUID id) {
        int[] values = stats.get(id);
        return values == null ? 0 : values[2];
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private boolean isPvpMode(Player player) {

        GameMode mode = player.getGameMode();

        return mode == GameMode.SURVIVAL
                || mode == GameMode.ADVENTURE;
    }

    private static String format(double value) {

        return String.format(
                java.util.Locale.ROOT,
                "%.2f",
                value
        );
    }

    // --------------------------------------------------
    // Player quit
    // --------------------------------------------------

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {

        UUID id = event.getPlayer().getUniqueId();

        pending.remove(id);
        lastAlert.remove(id);
        fixTimes.remove(id);
        mutedAlerts.remove(id);
        stats.remove(id);

        for (Deque<Attempt> attempts : pending.values()) {
            attempts.removeIf(
                    attempt -> attempt.target.equals(id)
            );
        }

        pending.entrySet().removeIf(
                entry -> entry.getValue().isEmpty()
        );
    }

    // --------------------------------------------------
    // Shutdown
    // --------------------------------------------------

    public void shutdown() {

        pending.clear();
        lastAlert.clear();
        fixTimes.clear();
        mutedAlerts.clear();
        stats.clear();
    }
}
