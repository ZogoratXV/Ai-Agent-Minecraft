package dev.rpgbridge.core.mixin.classlock;

import dev.rpgbridge.core.classlock.ClassLock;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocco per classe: armature e mano secondaria. Ogni mezzo secondo un oggetto riservato a un'altra classe viene tolto
 * dallo slot e rimesso nell'inventario (o lasciato a terra se l'inventario è pieno), qualunque sia il modo in cui
 * è stato indossato (clic nell'inventario, shift-clic, tasto F, dispenser, comandi o plugin).
 */
@Mixin(ServerPlayer.class)
public abstract class ClassLockEquipmentMixin {
	@Unique
	private static final EquipmentSlot[] RPGBRIDGE$SLOTS = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND};

	@Inject(method = "doTick", at = @At("TAIL"))
	private void rpgbridge$classLockEquipment(CallbackInfo ci) {
		ServerPlayer player = (ServerPlayer) (Object) this;
		if (player.tickCount % 10 != 0 || player.isSpectator() || !player.isAlive()) return;
		for (EquipmentSlot slot : RPGBRIDGE$SLOTS) {
			ItemStack stack = player.getItemBySlot(slot);
			if (stack.isEmpty() || ClassLock.allowed(player, stack)) continue;
			ItemStack removed = stack.copy();
			player.setItemSlot(slot, ItemStack.EMPTY);
			if (!player.getInventory().add(removed)) player.drop(removed, false);
			player.inventoryMenu.broadcastChanges();
			ClassLock.notify(player, removed);
		}
	}
}
