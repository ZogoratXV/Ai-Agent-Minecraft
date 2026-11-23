package dev.tessera.registry;

import dev.tessera.bukkit.TesseraItemStack;
import dev.tessera.stub.StubItemType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockType;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** ItemType Bukkit sopra un Item vanilla: crea gli ItemStack e abilita Material#isItem. */
public final class TesseraItemType extends StubItemType {

    private final Item item;
    private final NamespacedKey key;

    TesseraItemType(Item item, NamespacedKey key) {
        this.item = item;
        this.key = key;
    }

    @Override
    protected String tesseraStubName() {
        return "ItemType";
    }

    public Item handle() {
        return item;
    }

    @Override
    public NamespacedKey getKey() {
        return key;
    }

    @Override
    public ItemStack createItemStack() {
        return createItemStack(1);
    }

    @Override
    public ItemStack createItemStack(int amount) {
        return new TesseraItemStack(new net.minecraft.world.item.ItemStack(item, amount));
    }

    @Override
    public int getMaxStackSize() {
        return item.getDefaultMaxStackSize();
    }

    @Override
    public short getMaxDurability() {
        return (short) item.components().getOrDefault(net.minecraft.core.component.DataComponents.MAX_DAMAGE, 0).intValue();
    }

    @Override
    public boolean hasBlockType() {
        return item instanceof BlockItem;
    }

    @Override
    public BlockType getBlockType() {
        if (!(item instanceof BlockItem bi)) throw new IllegalStateException(key + " non ha un blocco associato");
        return TesseraRegistries.block(bi.getBlock());
    }

    @Override
    public boolean isEdible() {
        return item.components().has(net.minecraft.core.component.DataComponents.FOOD);
    }

    @Override
    public String translationKey() {
        return item.getDescriptionId();
    }

    @Override
    public String getTranslationKey() {
        return translationKey();
    }

    @Override
    public String toString() {
        return "ItemType{" + key + "}";
    }
}
