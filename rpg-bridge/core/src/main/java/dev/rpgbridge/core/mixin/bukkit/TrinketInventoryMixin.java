package dev.rpgbridge.core.mixin.bukkit;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftHumanEntity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.InventoryHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

/**
 * Trinkets' TrinketInventoryImpl (via the TrinketInventory interface) implements Container, without the methods CraftBukkit adds to the
 * interface (getContents, onOpen, getViewers, ...): AbstractMethodError as soon as Bukkit wraps it.
 * Only applied when Trinkets is installed.
 */
@Pseudo
@Mixin(targets = "eu.pb4.trinkets.impl.TrinketInventoryImpl", remap = false)
public abstract class TrinketInventoryMixin implements Container {
	@Unique
	private final List<HumanEntity> rpgbridge$viewers = new ArrayList<>();
	@Unique
	private int rpgbridge$maxStack = MAX_STACK;

	@Override
	public List<ItemStack> getContents() {
		List<ItemStack> list = new ArrayList<>();
		for (int i = 0; i < getContainerSize(); i++) list.add(getItem(i));
		return list;
	}

	@Override
	public void onOpen(CraftHumanEntity who) {
		rpgbridge$viewers.add(who);
	}

	@Override
	public void onClose(CraftHumanEntity who) {
		rpgbridge$viewers.remove(who);
	}

	@Override
	public List<HumanEntity> getViewers() {
		return rpgbridge$viewers;
	}

	@Override
	public InventoryHolder getOwner() {
		return null;
	}

	@Override
	public void setMaxStackSize(int size) {
		rpgbridge$maxStack = size;
	}

	@Override
	public Location getLocation() {
		return null;
	}
}
