package dev.rpgbridge.core.mixin.bukkit;

import dev.rpgbridge.core.bukkit.ModdedMenus;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.bukkit.inventory.InventoryView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * CraftBukkit declares getBukkitView() abstract; menus of Fabric mods do not implement it. Give it a
 * default body (vanilla menus keep overriding it) that builds a generic view, see {@link ModdedMenus}.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
	/**
	 * @author RPG Bridge
	 * @reason default Bukkit view for menus of Fabric mods (the CraftBukkit method is abstract)
	 */
	@Overwrite(remap = false)
	public InventoryView getBukkitView() {
		return ModdedMenus.view((AbstractContainerMenu) (Object) this);
	}
}
