package dev.tessera.bukkit;

import dev.tessera.bridge.Materials;
import dev.tessera.stub.StubBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;

/** Blocco Bukkit: una posizione in un mondo, letta/scritta direttamente sul ServerLevel. */
public final class TesseraBlock extends StubBlock {

    private final TesseraWorld world;
    private final BlockPos pos;

    public TesseraBlock(TesseraWorld world, BlockPos pos) {
        this.world = world;
        this.pos = pos.immutable();
    }

    @Override
    protected String tesseraStubName() {
        return "Block";
    }

    public TesseraWorld world() {
        return world;
    }

    public BlockPos pos() {
        return pos;
    }

    public net.minecraft.world.level.block.state.BlockState vanillaState() {
        return world.handle().getBlockState(pos);
    }

    @Override
    public Material getType() {
        return Materials.fromState(vanillaState());
    }

    @Override
    public void setType(Material type) {
        setType(type, true);
    }

    @Override
    public void setType(Material type, boolean applyPhysics) {
        var state = Materials.toBlock(type).defaultBlockState();
        world.handle().setBlock(pos, state, applyPhysics ? Block.UPDATE_ALL : Block.UPDATE_CLIENTS);
    }

    @Override
    public boolean isEmpty() {
        return vanillaState().isAir();
    }

    @Override
    public boolean isLiquid() {
        return !vanillaState().getFluidState().isEmpty();
    }

    @Override
    public BlockState getState() {
        return new TesseraBlockState(this, vanillaState());
    }

    @Override
    public World getWorld() {
        return world;
    }

    @Override
    public int getX() {
        return pos.getX();
    }

    @Override
    public int getY() {
        return pos.getY();
    }

    @Override
    public int getZ() {
        return pos.getZ();
    }

    @Override
    public Location getLocation() {
        return new Location(world, pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public org.bukkit.block.Block getRelative(int dx, int dy, int dz) {
        return new TesseraBlock(world, pos.offset(dx, dy, dz));
    }

    @Override
    public org.bukkit.block.Block getRelative(BlockFace face) {
        return getRelative(face.getModX(), face.getModY(), face.getModZ());
    }

    @Override
    public org.bukkit.block.Block getRelative(BlockFace face, int distance) {
        return getRelative(face.getModX() * distance, face.getModY() * distance, face.getModZ() * distance);
    }

    @Override
    public byte getLightLevel() {
        return (byte) world.handle().getMaxLocalRawBrightness(pos);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TesseraBlock b && b.world.equals(world) && b.pos.equals(pos);
    }

    @Override
    public int hashCode() {
        return 31 * world.hashCode() + pos.hashCode();
    }

    @Override
    public String toString() {
        return "TesseraBlock{" + world.getName() + " " + pos.toShortString() + " " + getType() + "}";
    }
}
