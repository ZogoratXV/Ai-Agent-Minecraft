package dev.tessera.bukkit;

import dev.tessera.stub.StubWorld;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** Mondo Bukkit sopra un ServerLevel. I nomi seguono la convenzione Bukkit (world, world_nether, world_the_end). */
public final class TesseraWorld extends StubWorld {

    private final TesseraServer server;
    private final ServerLevel level;
    private final String name;
    private final UUID uid;

    TesseraWorld(TesseraServer server, ServerLevel level) {
        this.server = server;
        this.level = level;
        String base = server.handle().getWorldData().getLevelName();
        var key = level.dimension();
        if (key == Level.OVERWORLD) this.name = base;
        else if (key == Level.NETHER) this.name = base + "_nether";
        else if (key == Level.END) this.name = base + "_the_end";
        else this.name = key.identifier().getNamespace() + "_" + key.identifier().getPath().replace('/', '_');
        this.uid = UUID.nameUUIDFromBytes(("tessera:" + key.identifier()).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected String tesseraStubName() {
        return "World";
    }

    public ServerLevel handle() {
        return level;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public UUID getUID() {
        return uid;
    }

    @Override
    public NamespacedKey getKey() {
        var id = level.dimension().identifier();
        return new NamespacedKey(id.getNamespace(), id.getPath());
    }

    @Override
    public Environment getEnvironment() {
        var key = level.dimension();
        if (key == Level.NETHER) return Environment.NETHER;
        if (key == Level.END) return Environment.THE_END;
        if (key == Level.OVERWORLD) return Environment.NORMAL;
        return Environment.CUSTOM;
    }

    @Override
    public long getSeed() {
        return level.getSeed();
    }

    @Override
    public Block getBlockAt(int x, int y, int z) {
        return new TesseraBlock(this, new BlockPos(x, y, z));
    }

    @Override
    public Block getBlockAt(Location location) {
        return getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    @Override
    public int getHighestBlockYAt(int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
    }

    @Override
    public Block getHighestBlockAt(int x, int z) {
        return getBlockAt(x, getHighestBlockYAt(x, z), z);
    }

    @Override
    public int getMinHeight() {
        return level.getMinY();
    }

    @Override
    public int getMaxHeight() {
        return level.getMaxY() + 1;
    }

    @Override
    public Location getSpawnLocation() {
        var data = level.getRespawnData();
        BlockPos pos = data.pos();
        return new Location(this, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, data.yaw(), data.pitch());
    }

    @Override
    public List<Player> getPlayers() {
        List<Player> list = new ArrayList<>();
        for (ServerPlayer p : level.players()) list.add(server.wrap(p));
        return list;
    }

    @Override
    public long getTime() {
        return Math.floorMod(level.getDefaultClockTime(), 24000L);
    }

    @Override
    public long getFullTime() {
        return level.getDefaultClockTime();
    }

    @Override
    public long getGameTime() {
        return level.getGameTime();
    }

    @Override
    public void setTime(long time) {
        long full = getFullTime();
        setFullTime(full - Math.floorMod(full, 24000L) + time);
    }

    @Override
    public void setFullTime(long time) {
        level.dimensionType().defaultClock().ifPresent(clock ->
                server.handle().clockManager().setTotalTicks(clock, time));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TesseraWorld w && w.level == level;
    }

    @Override
    public int hashCode() {
        return uid.hashCode();
    }

    @Override
    public String toString() {
        return "TesseraWorld{" + name + "}";
    }
}
