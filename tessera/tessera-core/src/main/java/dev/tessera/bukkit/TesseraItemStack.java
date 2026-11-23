package dev.tessera.bukkit;

import dev.tessera.bridge.Text;
import dev.tessera.registry.TesseraRegistries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * ItemStack Bukkit appoggiato a un ItemStack vanilla (l'equivalente di CraftItemStack).
 * Supporta tipo e quantità; meta, incantesimi e componenti non sono ancora implementati.
 */
public final class TesseraItemStack extends ItemStack {

    private net.minecraft.world.item.ItemStack handle;

    public TesseraItemStack(net.minecraft.world.item.ItemStack handle) {
        super();
        this.handle = handle;
    }

    public net.minecraft.world.item.ItemStack handle() {
        return handle;
    }

    @Override
    public Material getType() {
        if (handle.isEmpty()) return Material.AIR;
        Material m = Material.matchMaterial(BuiltInRegistries.ITEM.getKey(handle.getItem()).toString());
        return m == null ? Material.AIR : m;
    }

    @Override
    public void setType(Material type) {
        var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(type.getKey().toString()));
        handle = new net.minecraft.world.item.ItemStack(item, Math.max(1, handle.getCount()));
    }

    @Override
    public int getAmount() {
        return handle.getCount();
    }

    @Override
    public void setAmount(int amount) {
        handle.setCount(amount);
    }

    @Override
    public int getMaxStackSize() {
        return handle.getMaxStackSize();
    }

    @Override
    public boolean isEmpty() {
        return handle.isEmpty();
    }

    @Override
    public boolean hasItemMeta() {
        return false;
    }

    @Override
    public String translationKey() {
        return handle.getItem().getDescriptionId();
    }

    @Override
    public String getTranslationKey() {
        return translationKey();
    }

    @Override
    public net.kyori.adventure.text.Component displayName() {
        return Text.fromVanilla(handle.getDisplayName());
    }

    @Override
    public ItemStack clone() {
        return new TesseraItemStack(handle.copy());
    }

    @Override
    public ItemStack asQuantity(int qty) {
        return new TesseraItemStack(handle.copyWithCount(qty));
    }

    @Override
    public ItemStack asOne() {
        return asQuantity(1);
    }

    @Override
    public boolean isSimilar(@Nullable ItemStack other) {
        if (other == this) return true;
        if (other instanceof TesseraItemStack t) {
            return net.minecraft.world.item.ItemStack.isSameItemSameComponents(handle, t.handle);
        }
        return other != null && other.getType() == getType();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ItemStack other && isSimilar(other) && other.getAmount() == getAmount();
    }

    @Override
    public int hashCode() {
        return 31 * getType().hashCode() + getAmount();
    }

    @Override
    public String toString() {
        return "ItemStack{" + getType() + " x " + getAmount() + "}";
    }

    static {
        // Garantisce che i registri siano pronti prima del primo uso da parte di un plugin.
        TesseraRegistries.class.getName();
    }
}
