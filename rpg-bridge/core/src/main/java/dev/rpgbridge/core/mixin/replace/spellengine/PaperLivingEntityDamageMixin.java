/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 * Ports two injectors of Spell Engine (GPL-3.0, Zsolt Molnar) to Paper's LivingEntity:
 * LivingEntityHealthImpacting#damage_ApplyDamage_entity and LivingEntityEvents#getDamageBlockedAmount_WRAP_onShieldHit.
 */
package dev.rpgbridge.core.mixin.replace.spellengine;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.level.Level;
import net.spell_engine.api.event.CombatEvents;
import org.bukkit.event.entity.EntityDamageEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
public abstract class PaperLivingEntityDamageMixin {
	/** Paper: {@code boolean actuallyHurt(ServerLevel, DamageSource, float, EntityDamageEvent)}; false = not applied. */
	@WrapOperation(method = "hurtServer",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;actuallyHurt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;FLorg/bukkit/event/entity/EntityDamageEvent;)Z"))
	private boolean rpgbridge$spellEngineIncomingDamage(LivingEntity instance, ServerLevel level, DamageSource source, float amount,
			EntityDamageEvent event, Operation<Boolean> original) {
		if (CombatEvents.ENTITY_DAMAGE_INCOMING.isListened()) {
			var args = new CombatEvents.EntityDamageTaken.Args(instance, source, amount);
			CombatEvents.ENTITY_DAMAGE_INCOMING.invoke(listener -> listener.onDamageTaken(args));
		}
		if (instance instanceof Player player && CombatEvents.PLAYER_DAMAGE_INCOMING.isListened()) {
			var args = new CombatEvents.PlayerDamageTaken.Args(player, source, amount);
			CombatEvents.PLAYER_DAMAGE_INCOMING.invoke(listener -> listener.onPlayerDamageTaken(args));
		}
		if (instance.isInvulnerableTo(level, source)) {
			return false; // a listener made the entity immune: cancel this damage instance
		}
		return original.call(instance, level, source, amount, event);
	}

	/** Paper: {@code applyItemBlocking(ServerLevel, DamageSource, float, boolean simulate)} does the work. */
	@WrapOperation(method = "applyItemBlocking(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;FZ)F",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/component/BlocksAttacks;hurtBlockingItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/InteractionHand;F)V"),
			require = 0)
	private void rpgbridge$spellEngineShieldBlock(BlocksAttacks component, Level level, ItemStack stack, LivingEntity instance, InteractionHand hand,
			float blockedAmount, Operation<Void> original, ServerLevel serverLevel, DamageSource source, float damageAmount, boolean simulate) {
		if (CombatEvents.ENTITY_SHIELD_BLOCK.isListened()) {
			var args = new CombatEvents.EntityShieldBlock.Args(instance, source, blockedAmount);
			CombatEvents.ENTITY_SHIELD_BLOCK.invoke(listener -> listener.onShieldBlock(args));
		}
		if (instance instanceof Player player && CombatEvents.PLAYER_SHIELD_BLOCK.isListened()) {
			var args = new CombatEvents.PlayerShieldBlock.Args(player, source, blockedAmount);
			CombatEvents.PLAYER_SHIELD_BLOCK.invoke(listener -> listener.onShieldBlock(args));
		}
		original.call(component, level, stack, instance, hand, blockedAmount);
	}
}
