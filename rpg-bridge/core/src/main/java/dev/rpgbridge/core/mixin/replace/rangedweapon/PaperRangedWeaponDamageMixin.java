/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 * Port of RangedWeaponItemMixin#applyCustomDamage_RWA from Ranged Weapon API
 * (MIT, Copyright (c) FabricExtras contributors) to Paper's ProjectileWeaponItem.shoot.
 */
package dev.rpgbridge.core.mixin.replace.rangedweapon;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.rpg_foundation.ranged_weapon.api.EntityAttributes_RangedWeapon;
import net.rpg_foundation.ranged_weapon.api.RangedWeaponProperties;
import net.rpg_foundation.ranged_weapon.internal.ArrowExtension;
import net.rpg_foundation.ranged_weapon.internal.ScalingUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Vanilla spawns each projectile with {@code spawnProjectile(projectile, level, stack, beforeSpawn)} and
 * Ranged Weapon API scales the arrow damage inside {@code beforeSpawn}. Paper aims the projectile first,
 * fires EntityShootBowEvent and then calls {@code spawnProjectile(projectile, level, stack)}: scale the
 * damage right before that call, with the same formula.
 */
@Mixin(ProjectileWeaponItem.class)
public abstract class PaperRangedWeaponDamageMixin {
	@WrapOperation(method = "shoot",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/Projectile;spawnProjectile(Lnet/minecraft/world/entity/projectile/Projectile;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/entity/projectile/Projectile;"))
	private Projectile rpgbridge$applyCustomDamage(Projectile projectile, ServerLevel level, ItemStack projectileStack, Operation<Projectile> original,
			ServerLevel level_, LivingEntity shooter, InteractionHand hand, ItemStack weapon, List<ItemStack> projectiles,
			float speed, float divergence, boolean critical, LivingEntity target, float drawStrength) {
		RangedWeaponProperties properties = RangedWeaponProperties.get(weapon);
		if (properties != null && projectile instanceof AbstractArrow arrow && !((ArrowExtension) projectile).rwa_isModified()) {
			double rangedDamage = shooter.getAttributeValue(EntityAttributes_RangedWeapon.DAMAGE.entry);
			if (rangedDamage > 0) {
				double bonusVelocity = shooter.getAttributeValue(EntityAttributes_RangedWeapon.VELOCITY.entry);
				double velocityBaseline = properties.velocityBaseline(weapon.getItem());
				double velocityMultiplier = (velocityBaseline + bonusVelocity) / velocityBaseline;
				double multiplier = ScalingUtil.arrowDamageMultiplier(properties.damageBaseline((ProjectileWeaponItem) (Object) this), rangedDamage, velocityMultiplier);
				arrow.setBaseDamage(((ArrowExtension) projectile).rwa_getDamage() * multiplier);
				((ArrowExtension) projectile).rwa_markModified(true);
			}
		}
		return original.call(projectile, level, projectileStack);
	}
}
