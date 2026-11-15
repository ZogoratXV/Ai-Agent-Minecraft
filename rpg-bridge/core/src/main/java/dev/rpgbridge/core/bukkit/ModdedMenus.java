package dev.rpgbridge.core.bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftHumanEntity;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.craftbukkit.inventory.CraftInventoryView;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;

/**
 * Bukkit views for menus of Fabric mods. CraftBukkit makes {@code AbstractContainerMenu.getBukkitView()}
 * abstract and implements it in every vanilla menu; mod menus do not have it (AbstractMethodError when a
 * player opens one). The default view built here has the player's inventory at the bottom and, at the top,
 * a live container over the menu's other slots, so Bukkit events and plugins see the real items.
 */
public final class ModdedMenus {
	private static final Map<AbstractContainerMenu, InventoryView> VIEWS = new WeakHashMap<>();

	private ModdedMenus() {
	}

	public static InventoryView view(AbstractContainerMenu menu) {
		synchronized (VIEWS) {
			return VIEWS.computeIfAbsent(menu, m -> {
				HumanEntity player = null;
				for (Slot slot : m.slots) {
					if (slot.container instanceof Inventory inventory) {
						player = inventory.player.getBukkitEntity();
						break;
					}
				}
				return new CraftInventoryView<>(player, new CraftInventory(new MenuSlots(m)), m);
			});
		}
	}

	/** The menu's slots that are not the player's inventory, as one Container (reads and writes the slots). */
	static final class MenuSlots implements Container {
		private final AbstractContainerMenu menu;
		private final List<HumanEntity> viewers = new ArrayList<>();
		private int maxStack = MAX_STACK;

		MenuSlots(AbstractContainerMenu menu) {
			this.menu = menu;
		}

		private List<Slot> slots() {
			List<Slot> list = new ArrayList<>();
			for (Slot slot : menu.slots) if (!(slot.container instanceof Inventory)) list.add(slot);
			return list;
		}

		private Slot slot(int index) {
			List<Slot> list = slots();
			return index >= 0 && index < list.size() ? list.get(index) : null;
		}

		@Override
		public int getContainerSize() {
			return slots().size();
		}

		@Override
		public boolean isEmpty() {
			for (Slot slot : slots()) if (!slot.getItem().isEmpty()) return false;
			return true;
		}

		@Override
		public ItemStack getItem(int index) {
			Slot slot = slot(index);
			return slot == null ? ItemStack.EMPTY : slot.getItem();
		}

		@Override
		public ItemStack removeItem(int index, int count) {
			Slot slot = slot(index);
			return slot == null ? ItemStack.EMPTY : slot.remove(count);
		}

		@Override
		public ItemStack removeItemNoUpdate(int index) {
			Slot slot = slot(index);
			if (slot == null) return ItemStack.EMPTY;
			ItemStack stack = slot.getItem();
			slot.set(ItemStack.EMPTY);
			return stack;
		}

		@Override
		public void setItem(int index, ItemStack stack) {
			Slot slot = slot(index);
			if (slot != null) slot.set(stack);
		}

		@Override
		public int getMaxStackSize() {
			return maxStack;
		}

		@Override
		public void setChanged() {
			for (Slot slot : slots()) slot.setChanged();
		}

		@Override
		public boolean stillValid(Player player) {
			return menu.stillValid(player);
		}

		@Override
		public void clearContent() {
			for (Slot slot : slots()) slot.set(ItemStack.EMPTY);
		}

		@Override
		public List<ItemStack> getContents() {
			List<ItemStack> list = new ArrayList<>();
			for (Slot slot : slots()) list.add(slot.getItem());
			return list;
		}

		@Override
		public void onOpen(CraftHumanEntity who) {
			viewers.add(who);
		}

		@Override
		public void onClose(CraftHumanEntity who) {
			viewers.remove(who);
		}

		@Override
		public List<HumanEntity> getViewers() {
			return viewers;
		}

		@Override
		public InventoryHolder getOwner() {
			return null;
		}

		@Override
		public void setMaxStackSize(int size) {
			maxStack = size;
		}

		@Override
		public Location getLocation() {
			return null;
		}
	}
}
