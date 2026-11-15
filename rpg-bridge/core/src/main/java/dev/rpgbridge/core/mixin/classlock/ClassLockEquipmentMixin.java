package dev.rpgbridge.core.mixin.classlock;

import dev.rpgbridge.core.classlock.BoundTomes;
import dev.rpgbridge.core.classlock.ClassLock;
import dev.rpgbridge.core.classlock.TrinketSlots;
import dev.rpgbridge.core.classlock.WeaponBonuses;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Controllo periodico dell'equipaggiamento (ogni mezzo secondo) e dell'inventario (ogni secondo):
 * - tomi: un tomo non legato si lega a chi lo equipaggia (slot di Trinkets o mano secondaria);
 * - blocco per classe e tomi di altri: armature, mano secondaria e slot di Trinkets non consentiti tornano
 *   nell'inventario (o a terra se pieno), qualunque sia il modo in cui sono stati equipaggiati;
 * - bonus delle armi: applicati agli oggetti dell'inventario a cui una regola li assegna.
 * Su ServerPlayer.doTick: Paper non chiama ServerPlayer.tick() per i giocatori.
 */
@Mixin(ServerPlayer.class)
public abstract class ClassLockEquipmentMixin {
	@Unique
	private static final EquipmentSlot[] RPGBRIDGE$SLOTS = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND};

	@Inject(method = "doTick", at = @At("TAIL"))
	private void rpgbridge$checkEquipment(CallbackInfo ci) {
		ServerPlayer player = (ServerPlayer) (Object) this;
		if (player.tickCount % 10 != 0 || player.isSpectator() || !player.isAlive()) return;
		boolean changed = false;

		for (EquipmentSlot slot : RPGBRIDGE$SLOTS) {
			ItemStack stack = player.getItemBySlot(slot);
			if (stack.isEmpty()) continue;
			if (slot == EquipmentSlot.OFFHAND) {
				ItemStack bound = BoundTomes.bindIfUnbound(player, stack);
				if (bound != null) {
					player.setItemSlot(slot, bound);
					stack = bound;
					changed = true;
				}
			}
			if (ClassLock.allowed(player, stack)) continue;
			ItemStack removed = stack.copy();
			player.setItemSlot(slot, ItemStack.EMPTY);
			rpgbridge$giveBack(player, removed);
			ClassLock.notify(player, removed);
			changed = true;
		}

		for (var access : TrinketSlots.equipped(player)) {
			ItemStack stack = access.get();
			if (stack.isEmpty()) continue;
			ItemStack bound = BoundTomes.bindIfUnbound(player, stack);
			if (bound != null) {
				access.set(bound);
				stack = bound;
			}
			if (ClassLock.allowed(player, stack)) continue;
			ItemStack removed = stack.copy();
			access.set(ItemStack.EMPTY);
			rpgbridge$giveBack(player, removed);
			ClassLock.notify(player, removed);
			changed = true;
		}

		if (player.tickCount % 20 == 0) {
			var inventory = player.getInventory();
			for (int i = 0; i < inventory.getContainerSize(); i++) {
				ItemStack stack = inventory.getItem(i);
				if (!stack.isEmpty() && WeaponBonuses.apply(stack)) {
					inventory.setChanged();
					changed = true;
				}
			}
		}
		if (changed) player.inventoryMenu.broadcastChanges();
	}

	@Unique
	private static void rpgbridge$giveBack(ServerPlayer player, ItemStack stack) {
		if (!player.getInventory().add(stack)) player.drop(stack, false);
	}
}
