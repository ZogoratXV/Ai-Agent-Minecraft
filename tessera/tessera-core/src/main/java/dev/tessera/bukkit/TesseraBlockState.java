package dev.tessera.bukkit;

import dev.tessera.bridge.Materials;
import dev.tessera.stub.StubBlockState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Istantanea immutabile (per ora) dello stato di un blocco. */
public final class TesseraBlockState extends StubBlockState {

    private final TesseraBlock block;
    private final net.minecraft.world.level.block.state.BlockState state;

    public TesseraBlockState(TesseraBlock block, net.minecraft.world.level.block.state.BlockState state) {
        this.block = block;
        this.state = state;
    }

    @Override
    protected String tesseraStubName() {
        return "BlockState";
    }

    @Override
    public Block getBlock() {
        return block;
    }

    @Override
    public Material getType() {
        return Materials.fromState(state);
    }

    @Override
    public World getWorld() {
        return block.getWorld();
    }

    @Override
    public int getX() {
        return block.getX();
    }

    @Override
    public int getY() {
        return block.getY();
    }

    @Override
    public int getZ() {
        return block.getZ();
    }

    @Override
    public Location getLocation() {
        return block.getLocation();
    }

    @Override
    public boolean isPlaced() {
        return true;
    }

    @Override
    public boolean update(boolean force, boolean applyPhysics) {
        block.world().handle().setBlock(block.pos(), state,
                applyPhysics ? net.minecraft.world.level.block.Block.UPDATE_ALL : net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        return true;
    }

    @Override
    public boolean update(boolean force) {
        return update(force, true);
    }

    @Override
    public boolean update() {
        return update(false, true);
    }
}
