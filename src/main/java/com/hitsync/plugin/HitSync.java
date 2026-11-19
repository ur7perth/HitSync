package com.hitsync.plugin;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers.EntityUseAction;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class HitSync extends JavaPlugin implements Listener, TabExecutor {

    // ---------------------------------------------------------------- settings
    private volatile boolean enabled;
    private double reach;
    private double rayTolerance;
    private int checkDelay;
    private boolean syncJava;
    private boolean syncBedrock;
    private String bedrockPrefix;

    // ---------------------------------------------------------------- state (main thread only)
    private final Map<UUID, Integer> lastAttempt = new HashMap<>();
    private final Map<UUID, Integer> lastUse = new HashMap<>();
    private final Map<UUID, FallData> falls = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();

    private boolean applying;
    private boolean applyFired;
    private boolean applyCancelled;

    private Object floodgateApi;
    private Method floodgateCheck;

    private static final class FallData {
        double lastY;
        double fall;
        FallData(double y) { this.lastY = y; }
    }

    private static final class Pending {
        final int tick;
        int entityId;
        final float cooldown;
        final Vector eye;
        final Vector dir;
        Pending(int tick, int entityId, float cooldown, Vector eye, Vector dir) {
            this.tick = tick;
            this.entityId = entityId;
            this.cooldown = cooldown;
            this.eye = eye;
            this.dir = dir;
        }
    }

    // ---------------------------------------------------------------- lifecycle
    @Override
    public void onEnable() {
        if (!Bukkit.getPluginManager().isPluginEnabled("ProtocolLib")) {
            getLogger().severe("ProtocolLib is required. Disabling HitSync.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);

        if (getCommand("hitsync") != null) {
            getCommand("hitsync").setExecutor(this);
            getCommand("hitsync").setTabCompleter(this);
        }

        registerPackets();
        Bukkit.getScheduler().runTask(this, this::setupFloodgate);
        getLogger().info("HitSync enabled. State: " + (enabled ? "ON" : "OFF"));
    }

    @Override
    public void onDisable() {
        try {
            ProtocolLibrary.getProtocolManager().removePacketListeners(this);
        } catch (Throwable ignored) { }
    }

    private void loadSettings() {
        reloadConfig();
        enabled = getConfig().getBoolean("enabled", true);
        reach = getConfig().getDouble("reach", 3.0);
        rayTolerance = getConfig().getDouble("ray-tolerance", 0.05);
        checkDelay = Math.max(1, getConfig().getInt("check-delay-ticks", 2));
        syncJava = getConfig().getBoolean("crit-sync.java", true);
        syncBedrock = getConfig().getBoolean("crit-sync.bedrock", true);
        bedrockPrefix = getConfig().getString("bedrock.name-prefix", ".");
    }

    private void setupFloodgate() {
        try {
            Class<?> c = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            floodgateApi = c.getMethod("getInstance").invoke(null);
            floodgateCheck = c.getMethod("isFloodgatePlayer", UUID.class);
        } catch (Throwable ignored) {
            floodgateApi = null;
            floodgateCheck = null;
        }
    }

    private boolean isBedrock(Player p) {
        UUID id = p.getUniqueId();
        if (id.getMostSignificantBits() == 0L) return true;
        if (floodgateApi != null && floodgateCheck != null) {
            try {
                if ((boolean) floodgateCheck.invoke(floodgateApi, id)) return true;
            } catch (Throwable ignored) { }
        }
        return bedrockPrefix != null && !bedrockPrefix.isEmpty() && p.getName().startsWith(bedrockPrefix);
    }

    // ---------------------------------------------------------------- ProtocolLib
    private void registerPackets() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(this, ListenerPriority.MONITOR,
                        PacketType.Play.Client.ARM_ANIMATION,
                        PacketType.Play.Client.USE_ENTITY) {
                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        if (!enabled || event.isPlayerTemporary()) return;
                        final Player player = event.getPlayer();
                        if (player == null) return;

                        if (event.getPacketType() == PacketType.Play.Client.ARM_ANIMATION) {
                            Bukkit.getScheduler().runTask(HitSync.this, () -> handleInput(player, -1));
                            return;
                        }

                        try {
                            PacketContainer packet = event.getPacket();
                            EntityUseAction action = packet.getEnumEntityUseActions().read(0).getAction();
                            if (action != EntityUseAction.ATTACK) return;
                            final int entityId = packet.getIntegers().read(0);
                            Bukkit.getScheduler().runTask(HitSync.this, () -> handleInput(player, entityId));
                        } catch (Throwable ignored) { }
                    }
                });
    }

    /**
     * Called on the main thread for every swing / attack packet.
     * At this point vanilla has already processed the packet, so we only schedule
     * a delayed check to see whether the server registered a hit attempt.
     */
    private void handleInput(Player p, int entityId) {
        if (!enabled || !p.isOnline() || !canAttack(p)) return;

        UUID id = p.getUniqueId();
        int now = Bukkit.getCurrentTick();

        Pending existing = pending.get(id);
        if (existing != null && now - existing.tick <= 1) {
            if (entityId >= 0) existing.entityId = entityId;
            return;
        }

        Location eye = p.getEyeLocation();
        final Pending pend = new Pending(now, entityId, p.getAttackCooldown(),
                eye.toVector(), eye.getDirection());
        pending.put(id, pend);

        Bukkit.getScheduler().runTaskLater(this, () -> resolve(p, pend), checkDelay);
    }

    private void resolve(Player attacker, Pending pend) {
        UUID id = attacker.getUniqueId();
        if (pending.get(id) == pend) pending.remove(id);

        if (!enabled || !attacker.isOnline() || !canAttack(attacker)) return;

        // The server already processed an attack attempt (damaged, or cancelled by protection)
        Integer last = lastAttempt.get(id);
        if (last != null && last >= pend.tick - 1) return;

        // Right-click interactions (placing blocks, using items) also send swing packets
        Integer use = lastUse.get(id);
        if (use != null && use >= pend.tick - 2) return;

        Player target = findTarget(attacker, pend);
        if (target == null || !validVictim(target)) return;

        applyGhostHit(attacker, target, pend.cooldown);
    }

    // ---------------------------------------------------------------- tracking
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttackMonitor(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (e.getCause() != DamageCause.ENTITY_ATTACK) return;
        lastAttempt.put(p.getUniqueId(), Bukkit.getCurrentTick());
        if (applying) {
            applyFired = true;
            applyCancelled = e.isCancelled();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {
            lastUse.put(e.getPlayer().getUniqueId(), Bukkit.getCurrentTick());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to == null) return;
        Player p = e.getPlayer();
        double y = to.getY();

        FallData d = falls.computeIfAbsent(p.getUniqueId(), k -> new FallData(y));
        if (resetsFall(p, to)) {
            d.fall = 0;
        } else {
            double dy = d.lastY - y;
            if (dy > 0.0001) d.fall += dy;
        }
        d.lastY = y;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastAttempt.remove(id);
        lastUse.remove(id);
        falls.remove(id);
        pending.remove(id);
    }

    private boolean resetsFall(Player p, Location loc) {
        return p.isGliding()
                || p.isSwimming()
                || p.isInWater()
                || p.isInLava()
                || p.isClimbing()
                || p.isFlying()
                || p.isRiptiding()
                || p.isInsideVehicle()
                || p.hasPotionEffect(PotionEffectType.LEVITATION)
                || loc.getBlock().getType() == Material.COBWEB
                || onSolidGround(loc);
    }

    /** Real collision check against block shapes just under the player's feet. */
    private boolean onSolidGround(Location loc) {
        World w = loc.getWorld();
        if (w == null) return false;
        BoundingBox feet = new BoundingBox(
                loc.getX() - 0.3, loc.getY() - 0.03, loc.getZ() - 0.3,
                loc.getX() + 0.3, loc.getY(), loc.getZ() + 0.3);

        for (int x = (int) Math.floor(feet.getMinX()); x <= (int) Math.floor(feet.getMaxX()); x++) {
            for (int y = (int) Math.floor(feet.getMinY()); y <= (int) Math.floor(feet.getMaxY()); y++) {
                for (int z = (int) Math.floor(feet.getMinZ()); z <= (int) Math.floor(feet.getMaxZ()); z++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType().isAir()) continue;
                    for (BoundingBox bb : b.getCollisionShape().getBoundingBoxes()) {
                        if (bb.shift(x, y, z).overlaps(feet)) return true;
                    }
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- critical logic
    /** Same conditions vanilla uses to decide a critical hit. */
    @SuppressWarnings("deprecation")
    private boolean serverWouldCrit(Player p, float cd) {
        return cd > 0.9f
                && p.getFallDistance() > 0.0f
                && !p.isOnGround()
                && !p.isClimbing()
                && !p.isInWater()
                && !p.hasPotionEffect(PotionEffectType.BLINDNESS)
                && !p.isInsideVehicle()
                && !p.isSprinting();
    }

    /** Our own verification: is the player truly falling right now? */
    private boolean isRealCrit(Player p, float cd) {
        if (cd <= 0.9f) return false;
        if (p.isSprinting() || p.hasPotionEffect(PotionEffectType.BLINDNESS)) return false;
        FallData d = falls.get(p.getUniqueId());
        if (d == null || d.fall <= 0.0) return false;
        return !resetsFall(p, p.getLocation());
    }

    /** Base attack damage scaled by the attack cooldown (vanilla formula, without crit). */
    private double scaledBase(Player p, float cd) {
        AttributeInstance attr = p.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);
        double base = attr != null ? attr.getValue() : 1.0;
        return base * (0.2 + cd * cd * 0.8);
    }

    private double enchantBonus(Player p, float cd) {
        ItemStack item = p.getInventory().getItemInMainHand();
        int sharp = item.getEnchantmentLevel(Enchantment.DAMAGE_ALL);
        return sharp > 0 ? (0.5 * sharp + 0.5) * cd : 0.0;
    }

    /**
     * Verifies every attack the server registers:
     *  - fake critical (glitch / elytra spam)  -> becomes a normal hit
     *  - real critical registered as normal    -> becomes a critical hit
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!enabled || applying) return;
        if (e.getCause() != DamageCause.ENTITY_ATTACK) return;
        if (!(e.getDamager() instanceof Player p)) return;
        if (!(e.getEntity() instanceof Player)) return;

        boolean bedrock = isBedrock(p);
        if (bedrock ? !syncBedrock : !syncJava) return;

        float cd = p.getAttackCooldown();
        boolean serverCrit = serverWouldCrit(p, cd);
        boolean realCrit = isRealCrit(p, cd);
        if (serverCrit == realCrit) return;

        double half = scaledBase(p, cd) * 0.5; // crit adds +50% of the scaled base damage
        e.setDamage(Math.max(0.0, e.getDamage() + (realCrit ? half : -half)));

        if (realCrit) critEffects((Player) e.getEntity(), p);
    }

    // ---------------------------------------------------------------- ghost hit fix
    private void applyGhostHit(Player attacker, Player target, float cd) {
        double base = scaledBase(attacker, cd);
        boolean crit = isRealCrit(attacker, cd);
        if (crit) base *= 1.5;
        double dmg = base + enchantBonus(attacker, cd);
        if (dmg <= 0.0) return;

        applying = true;
        applyFired = false;
        applyCancelled = false;
        try {
            // Fires EntityDamageByEntityEvent, so WorldGuard / any protection can cancel it
            target.damage(dmg, attacker);
        } finally {
            applying = false;
        }

        if (applyFired && !applyCancelled) {
            if (crit) critEffects(target, attacker);
            attacker.resetCooldown();
        }
    }

    private void critEffects(Player target, Player attacker) {
        Location l = target.getLocation().add(0, target.getHeight() * 0.5, 0);
        target.getWorld().spawnParticle(Particle.CRIT, l, 15, 0.3, 0.3, 0.3, 0.1);
        target.getWorld().playSound(l, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f);
    }

    // ---------------------------------------------------------------- validation
    private boolean canAttack(Player p) {
        if (!p.isOnline() || p.isDead() || p.isHandRaised()) return false;
        GameMode gm = p.getGameMode();
        if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) return false;
        return p.getWorld().getPVP();
    }

    private boolean validVictim(Player t) {
        if (!t.isOnline() || t.isDead() || t.isInvulnerable()) return false;
        GameMode gm = t.getGameMode();
        if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) return false;
        // Hit-immunity frames: vanilla would reject this hit normally, so it is not a ghost hit
        return t.getNoDamageTicks() <= t.getMaximumNoDamageTicks() / 2.0;
    }

    /** Server-side ray trace against player hitboxes, blocked by solid blocks. */
    private Player findTarget(Player attacker, Pending pend) {
        World w = attacker.getWorld();
        Vector eye = pend.eye;
        Vector dir = pend.dir;

        double maxDist = reach;
        RayTraceResult blockHit = w.rayTraceBlocks(
                new Location(w, eye.getX(), eye.getY(), eye.getZ()),
                dir, reach, FluidCollisionMode.NEVER, true);
        if (blockHit != null) {
            maxDist = blockHit.getHitPosition().distance(eye);
        }

        Player best = null;
        double bestDist = maxDist;
        double near = (reach + 2.0) * (reach + 2.0);

        for (Player o : w.getPlayers()) {
            if (o.equals(attacker) || o.isDead()) continue;
            if (o.getGameMode() == GameMode.SPECTATOR) continue;
            if (!attacker.canSee(o)) continue;
            if (o.getLocation().distanceSquared(attacker.getLocation()) > near) continue;

            BoundingBox box = o.getBoundingBox().expand(rayTolerance);
            RayTraceResult r = box.rayTrace(eye, dir, maxDist);
            if (r == null) continue;

            double dist = r.getHitPosition().distance(eye);
            if (dist <= bestDist) {
                bestDist = dist;
                best = o;
            }
        }

        if (best != null && pend.entityId >= 0 && best.getEntityId() != pend.entityId) return null;
        return best;
    }

    // ---------------------------------------------------------------- command
    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage("§eHitSync: " + (enabled ? "§aON" : "§cOFF") + " §7| /HitSync On | Off");
            return true;
        }
        if (a[0].equalsIgnoreCase("on")) {
            enabled = true;
            getConfig().set("enabled", true);
            saveConfig();
            s.sendMessage("§aHitSync has been enabled.");
        } else if (a[0].equalsIgnoreCase("off")) {
            enabled = false;
            getConfig().set("enabled", false);
            saveConfig();
            pending.clear();
            s.sendMessage("§cHitSync has been disabled.");
        } else {
            s.sendMessage("§e/HitSync <On|Off>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String o : new String[]{"On", "Off"}) {
                if (o.toLowerCase().startsWith(a[0].toLowerCase())) out.add(o);
            }
        }
        return out;
    }
                  }
