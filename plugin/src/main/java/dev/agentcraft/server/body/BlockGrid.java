package dev.agentcraft.server.body;

/** What the pathfinder needs to know about blocks. Implemented on a World and, in tests, on a map. */
public interface BlockGrid {
    /** An NPC can be inside this block (air, grass, open door...). False for unloaded or dangerous blocks. */
    boolean passable(int x, int y, int z);

    /** An NPC can stand on top of this block. */
    boolean standable(int x, int y, int z);

    default boolean walkable(int x, int y, int z) {
        return standable(x, y - 1, z) && passable(x, y, z) && passable(x, y + 1, z);
    }
}
