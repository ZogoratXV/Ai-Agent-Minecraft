package dev.agentcraft.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.server.body.BlockGrid;
import dev.agentcraft.server.body.Pathfinder;
import dev.agentcraft.server.body.Pathfinder.Node;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PathfinderTest {

    /** Solid blocks in a set; everything else is air. Floor at y=0 for |x|,|z| <= 20. */
    static final class Grid implements BlockGrid {
        final Set<Node> solid = new HashSet<>();

        Grid() {
            for (int x = -20; x <= 20; x++) {
                for (int z = -20; z <= 20; z++) {
                    solid.add(new Node(x, 0, z));
                }
            }
        }

        void wall(int x, int z1, int z2, int height) {
            for (int z = z1; z <= z2; z++) {
                for (int y = 1; y <= height; y++) {
                    solid.add(new Node(x, y, z));
                }
            }
        }

        @Override
        public boolean passable(int x, int y, int z) {
            return !solid.contains(new Node(x, y, z));
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return solid.contains(new Node(x, y, z));
        }
    }

    @Test
    void walksStraightOnFlatGround() {
        Pathfinder pf = new Pathfinder(new Grid(), 1000);
        List<Node> p = pf.find(new Node(0, 1, 0), new Node(5, 1, 0));
        assertEquals(6, p.size());
        assertEquals(new Node(5, 1, 0), p.getLast());
    }

    @Test
    void goesAroundAWall() {
        Grid g = new Grid();
        g.wall(2, -3, 3, 3);
        List<Node> p = new Pathfinder(g, 2000).find(new Node(0, 1, 0), new Node(4, 1, 0));
        assertFalse(p.isEmpty());
        assertTrue(p.stream().noneMatch(n -> g.solid.contains(n)), "path goes through the wall");
        assertTrue(p.stream().anyMatch(n -> Math.abs(n.z()) >= 4), "path must go around the wall's end");
    }

    @Test
    void stepsUpOneBlockButNotTwo() {
        Grid g = new Grid();
        g.wall(2, -20, 20, 1); // a 1-high step across the whole floor
        assertFalse(new Pathfinder(g, 4000)
                .find(new Node(0, 1, 0), new Node(4, 1, 0))
                .isEmpty());

        Grid high = new Grid();
        high.wall(2, -20, 20, 2); // 2 high: impassable
        assertTrue(new Pathfinder(high, 4000)
                .find(new Node(0, 1, 0), new Node(4, 1, 0))
                .isEmpty());
    }

    @Test
    void doesNotCutCornersDiagonally() {
        Grid g = new Grid();
        g.wall(1, 0, 0, 2);
        g.wall(0, 1, 1, 2);
        List<Node> p = new Pathfinder(g, 2000).find(new Node(0, 1, 0), new Node(1, 1, 1));
        assertTrue(p.isEmpty() || p.size() > 2, "must not squeeze diagonally between two blocks");
    }

    @Test
    void givesUpWithinTheNodeBudget() {
        Grid g = new Grid();
        // goal enclosed on all sides
        g.wall(9, 9, 11, 3);
        g.wall(11, 9, 11, 3);
        g.solid.add(new Node(10, 1, 9));
        g.solid.add(new Node(10, 2, 9));
        g.solid.add(new Node(10, 3, 9));
        g.solid.add(new Node(10, 1, 11));
        g.solid.add(new Node(10, 2, 11));
        g.solid.add(new Node(10, 3, 11));
        assertTrue(new Pathfinder(g, 300)
                .find(new Node(0, 1, 0), new Node(10, 1, 10))
                .isEmpty());
    }

    @Test
    void snapsToTheNearestWalkableBlock() {
        Pathfinder pf = new Pathfinder(new Grid(), 100);
        Node n = pf.snap(3, 4, 3, 2);
        assertNotNull(n);
        assertEquals(new Node(3, 1, 3), n);
    }
}
