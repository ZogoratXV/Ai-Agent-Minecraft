package dev.tessera.registry;

import dev.tessera.stub.StubBlockType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemType;
import org.jspecify.annotations.Nullable;

/** BlockType Bukkit sopra un Block vanilla: abilita Material#isBlock, isSolid, isAir, ecc. */
public final class TesseraBlockType extends StubBlockType {

    private final Block block;
    private final NamespacedKey key;

    TesseraBlockType(Block block, NamespacedKey key) {
        this.block = block;
        this.key = key;
    }

    @Override
    protected String tesseraStubName() {
        return "BlockType";
    }

    public Block handle() {
        return block;
    }

    private BlockState state() {
        return block.defaultBlockState();
    }

    @Override
    public NamespacedKey getKey() {
        return key;
    }

    @Override
    public boolean isAir() {
        return state().isAir();
    }

    @Override
    public boolean isSolid() {
        return state().blocksMotion();
    }

    @Override
    public boolean hasCollision() {
        return !state().getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO).isEmpty();
    }

    @Override
    public boolean hasGravity() {
        return block instanceof FallingBlock;
    }

    @Override
    public boolean isFlammable() {
        return state().ignitedByLava();
    }

    @Override
    public boolean isBurnable() {
        return state().ignitedByLava();
    }

    @Override
    public float getHardness() {
        return block.defaultDestroyTime();
    }

    @Override
    public float getBlastResistance() {
        return block.getExplosionResistance();
    }

    @Override
    public float getSlipperiness() {
        return block.getFriction();
    }

    @Override
    public boolean hasItemType() {
        return block.asItem() != net.minecraft.world.item.Items.AIR || isAir();
    }

    @Override
    public @Nullable ItemType getItemType() {
        return TesseraRegistries.item(block.asItem());
    }

    @Override
    public String translationKey() {
        return block.getDescriptionId();
    }

    @Override
    public String getTranslationKey() {
        return translationKey();
    }

    @Override
    public String toString() {
        return "BlockType{" + key + "}";
    }
}
