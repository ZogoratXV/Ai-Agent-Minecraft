package dev.agentcraft.server.body;

import dev.agentcraft.server.state.TeamView;
import dev.agentcraft.server.state.Views;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * Keeps one body per agent in the world, in step with the {@link TeamView}, and walks bodies along
 * their paths (one small step per tick, on the entity's own scheduler, Folia-safe).
 */
public final class BodyManager implements TeamView.Listener {
    /** Blocks per tick (vanilla player walking speed is about 0.216). */
    private static final double SPEED = 0.22;

    private static final int MAX_PATH_NODES = 4000;
    private static final double TELEPORT_BEYOND = 96;
    private static final double SPEECH_RADIUS = 32;

    private final Plugin plugin;
    private final Stations stations;
    private final NamespacedKey tag;
    private final Logger log;
    private final Map<String, Walker> walkers = new HashMap<>();

    private static final class Walker {
        AgentBody body;
        Views.Agent agent;
        final Deque<Location> path = new ArrayDeque<>();
        UUID follow;
        long ticks;
        ScheduledTask task;
    }

    private final Bodies factory;

    public BodyManager(Plugin plugin, Stations stations, Bodies factory, Logger log) {
        this.plugin = plugin;
        this.stations = stations;
        this.factory = factory;
        this.tag = new NamespacedKey(plugin, "agent");
        this.log = log;
    }

    /** Removes bodies left behind by a crash (bodies are not persistent, but be safe). */
    public void removeStrays() {
        for (World w : Bukkit.getWorlds()) {
            for (Mannequin m : w.getEntitiesByClass(Mannequin.class)) {
                if (m.getPersistentDataContainer().has(tag, PersistentDataType.STRING)) {
                    m.remove();
                }
            }
        }
    }

    public void removeAll() {
        walkers.values().forEach(w -> {
            if (w.task != null) {
                w.task.cancel();
            }
            w.body.remove();
        });
        walkers.clear();
    }

    // ------------------------------------------------------------------ TeamView.Listener

    @Override
    public void onSnapshot(TeamView view) {
        walkers.keySet().removeIf(id -> {
            if (view.agent(id).isEmpty()) {
                Walker w = walkers.get(id);
                if (w.task != null) {
                    w.task.cancel();
                }
                w.body.remove();
                return true;
            }
            return false;
        });
        view.agents().forEach(a -> ensure(a, true));
    }

    @Override
    public void onAgent(Views.Agent before, Views.Agent now) {
        Walker w = ensure(now, before == null);
        if (before != null && !now.station().equals(before.station())) {
            walkToStation(w, now.station());
        }
    }

    // ------------------------------------------------------------------ commands from tools

    /** Moves an agent to a station, a position, or starts following a player. */
    public Optional<String> moveToStation(String agentId, String station) {
        Walker w = walkers.get(agentId);
        if (w == null) {
            return Optional.of("no body for agent " + agentId);
        }
        walkToStation(w, station);
        return Optional.empty();
    }

    public Optional<String> moveToPosition(String agentId, Location at) {
        Walker w = walkers.get(agentId);
        if (w == null) {
            return Optional.of("no body for agent " + agentId);
        }
        w.follow = null;
        planPath(w, at);
        return Optional.empty();
    }

    public Optional<String> follow(String agentId, UUID player) {
        Walker w = walkers.get(agentId);
        if (w == null) {
            return Optional.of("no body for agent " + agentId);
        }
        Player p = Bukkit.getPlayer(player);
        if (p == null) {
            return Optional.of("player is not online");
        }
        w.follow = player;
        planPath(w, p.getLocation());
        return Optional.empty();
    }

    /** Shows a line of speech to players near the agent. */
    public Optional<String> say(String agentId, String text) {
        Walker w = walkers.get(agentId);
        if (w == null) {
            return Optional.of("no body for agent " + agentId);
        }
        Component line = Component.text()
                .append(StatusLine.name(w.agent))
                .append(Component.text(" » ", NamedTextColor.DARK_GRAY))
                .append(Component.text(text, NamedTextColor.WHITE))
                .build();
        Location at = w.body.location();
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) <= SPEECH_RADIUS * SPEECH_RADIUS) {
                p.sendMessage(line);
            }
        }
        return Optional.empty();
    }

    public Optional<Location> locationOf(String agentId) {
        Walker w = walkers.get(agentId);
        return w == null ? Optional.empty() : Optional.of(w.body.location());
    }

    // ------------------------------------------------------------------ internals

    private Walker ensure(Views.Agent a, boolean placeAtStation) {
        Walker w = walkers.computeIfAbsent(a.id(), id -> new Walker());
        w.agent = a;
        if (w.body == null || !w.body.isValid()) {
            Location at = stationLocation(a.station(), null);
            String skin = a.skin() == null ? null : a.skin().name();
            w.body = factory.spawn(at, a.id(), skin, tag);
            w.path.clear();
            startTicking(w);
        } else if (placeAtStation) {
            walkToStation(w, a.station());
        }
        w.body.setLabels(StatusLine.name(a), StatusLine.status(a));
        return w;
    }

    private void startTicking(Walker w) {
        if (w.task != null) {
            w.task.cancel();
        }
        w.task = w.body.entity().getScheduler().runAtFixedRate(plugin, t -> tick(w), null, 1L, 1L);
    }

    private void walkToStation(Walker w, String station) {
        w.follow = null;
        planPath(w, stationLocation(station, w));
    }

    private Location stationLocation(String station, Walker w) {
        if ("user".equals(station)) {
            Location from = w == null ? stations.base() : w.body.location();
            Optional<Player> admin = from.getWorld().getPlayers().stream()
                    .filter(p -> p.hasPermission("agentcraft.approve"))
                    .min((a, b) -> Double.compare(
                            a.getLocation().distanceSquared(from),
                            b.getLocation().distanceSquared(from)));
            if (admin.isPresent()) {
                Vector back = admin.get()
                        .getLocation()
                        .getDirection()
                        .setY(0)
                        .normalize()
                        .multiply(-2);
                return admin.get().getLocation().add(back);
            }
            return stations.get("podium");
        }
        return stations.get(Stations.NAMES.contains(station) ? station : "base");
    }

    private void planPath(Walker w, Location target) {
        Location from = w.body.location();
        w.path.clear();
        if (from.getWorld() != target.getWorld() || from.distance(target) > TELEPORT_BEYOND) {
            w.body.moveTo(target);
            return;
        }
        if (w.body.navigate(target)) {
            return;
        }
        Pathfinder pf = new Pathfinder(new WorldGrid(from.getWorld()), MAX_PATH_NODES);
        Pathfinder.Node start = pf.snap(from.getBlockX(), from.getBlockY(), from.getBlockZ(), 2);
        Pathfinder.Node goal = pf.snap(target.getBlockX(), target.getBlockY(), target.getBlockZ(), 3);
        List<Pathfinder.Node> nodes = (start == null || goal == null) ? List.of() : pf.find(start, goal);
        if (nodes.isEmpty()) {
            log.fine("no path for " + w.agent.id() + ", teleporting");
            w.body.moveTo(target);
            return;
        }
        for (Pathfinder.Node n : nodes) {
            w.path.add(new Location(from.getWorld(), n.x() + 0.5, n.y(), n.z() + 0.5));
        }
    }

    private void tick(Walker w) {
        w.ticks++;
        if (w.follow != null && w.ticks % 20 == 0) {
            Player p = Bukkit.getPlayer(w.follow);
            if (p == null || p.getWorld() != w.body.location().getWorld()) {
                w.follow = null;
            } else if (p.getLocation().distanceSquared(w.body.location()) > 9) {
                planPath(w, p.getLocation());
            }
        }
        Location next = w.path.peekFirst();
        if (next != null) {
            Location cur = w.body.location();
            Vector delta = next.toVector().subtract(cur.toVector());
            double dist = delta.length();
            Location to;
            if (dist <= SPEED) {
                to = next.clone();
                w.path.pollFirst();
            } else {
                to = cur.clone().add(delta.multiply(SPEED / dist));
            }
            double dx = next.getX() - cur.getX(), dz = next.getZ() - cur.getZ();
            if (dx * dx + dz * dz > 1.0e-4) {
                to.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
            } else {
                to.setYaw(cur.getYaw());
            }
            to.setPitch(0);
            w.body.moveTo(to);
        } else if (StatusLine.WORKING.contains(w.agent.state()) && w.agent.active() && !w.agent.paused()) {
            if (w.ticks % 15 == 0) {
                w.body.swingArm();
            }
        }
    }
}
