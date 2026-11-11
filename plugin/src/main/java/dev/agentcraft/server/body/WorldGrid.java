package dev.agentcraft.server.body;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/** {@link BlockGrid} on a live world. Unloaded chunks count as walls, so pathing never loads chunks. */
public final class WorldGrid implements BlockGrid {
    private final World world;

    public WorldGrid(World world) {
        this.world = world;
    }

    private Block block(int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        return world.getBlockAt(x, y, z);
    }

    @Override
    public boolean passable(int x, int y, int z) {
        Block b = block(x, y, z);
        if (b == null) {
            return false;
        }
        Material m = b.getType();
        return b.isPassable() && m != Material.LAVA && m != Material.FIRE && m != Material.SOUL_FIRE;
    }

    @Override
    public boolean standable(int x, int y, int z) {
        Block b = block(x, y, z);
        return b != null && b.getType().isSolid() && !b.isPassable();
    }
}
