package dev.agentcraft.server.body;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Bounded A* over blocks for NPCs that have no vanilla pathfinder (Mannequin is not a Mob).
 * Walks on flat ground, diagonally when both sides are free, up one block and down up to three.
 * Gives up after {@code maxNodes} expansions so a far or unreachable target never stalls a tick.
 */
public final class Pathfinder {

    public record Node(int x, int y, int z) {
        double dist(Node o) {
            double dx = x - o.x, dy = y - o.y, dz = z - o.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int MAX_DROP = 3;

    private final BlockGrid grid;
    private final int maxNodes;

    public Pathfinder(BlockGrid grid, int maxNodes) {
        this.grid = grid;
        this.maxNodes = maxNodes;
    }

    /** Nearest walkable block within {@code radius} (same column first), or null. */
    public Node snap(int x, int y, int z, int radius) {
        for (int r = 0; r <= radius; r++) {
            for (int dy = 0; dy <= 3; dy++) {
                for (int sign : new int[] {1, -1}) {
                    int yy = y + dy * sign;
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                                continue;
                            }
                            if (grid.walkable(x + dx, yy, z + dz)) {
                                return new Node(x + dx, yy, z + dz);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Path from start to goal, both included; empty if none was found within the node budget. */
    public List<Node> find(Node start, Node goal) {
        if (start.equals(goal)) {
            return List.of(start);
        }
        record Entry(Node node, double f) {}
        PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<Node, Double> g = new HashMap<>();
        Map<Node, Node> parent = new HashMap<>();
        g.put(start, 0.0);
        open.add(new Entry(start, start.dist(goal)));
        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            Node cur = open.poll().node;
            if (cur.equals(goal)) {
                return rebuild(parent, cur);
            }
            expanded++;
            double gc = g.get(cur);
            for (Node next : neighbours(cur)) {
                double cost = gc + cur.dist(next) + (next.y != cur.y ? 0.5 : 0);
                Double known = g.get(next);
                if (known == null || cost < known) {
                    g.put(next, cost);
                    parent.put(next, cur);
                    open.add(new Entry(next, cost + next.dist(goal)));
                }
            }
        }
        return List.of();
    }

    private List<Node> neighbours(Node n) {
        List<Node> out = new ArrayList<>(8);
        for (int[] d : DIRS) {
            int nx = n.x + d[0], nz = n.z + d[1];
            boolean diagonal = d[0] != 0 && d[1] != 0;
            if (diagonal && !(clear(n.x + d[0], n.y, n.z) && clear(n.x, n.y, n.z + d[1]))) {
                continue;
            }
            if (grid.walkable(nx, n.y, nz)) {
                out.add(new Node(nx, n.y, nz));
                continue;
            }
            // step up one block: needs head room above the current position
            if (!diagonal && grid.walkable(nx, n.y + 1, nz) && grid.passable(n.x, n.y + 2, n.z)) {
                out.add(new Node(nx, n.y + 1, nz));
                continue;
            }
            // walk off an edge and drop
            if (!diagonal && clear(nx, n.y, nz)) {
                for (int drop = 1; drop <= MAX_DROP; drop++) {
                    if (grid.walkable(nx, n.y - drop, nz)) {
                        out.add(new Node(nx, n.y - drop, nz));
                        break;
                    }
                    if (!grid.passable(nx, n.y - drop, nz)) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    private boolean clear(int x, int y, int z) {
        return grid.passable(x, y, z) && grid.passable(x, y + 1, z);
    }

    private static List<Node> rebuild(Map<Node, Node> parent, Node end) {
        List<Node> path = new ArrayList<>();
        for (Node n = end; n != null; n = parent.get(n)) {
            path.add(n);
        }
        Collections.reverse(path);
        return path;
    }
}
