/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 * Provides Spell Engine's (GPL-3.0) RangedWeaponAccessor#shootAll_SpellEngine on Paper.
 */
package dev.rpgbridge.core.mixin.replace.spellengine;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/**
 * Paper's {@code shoot} takes an extra draw strength (0..1, reported in EntityShootBowEvent).
 * Spell Engine shoots spell arrows at full strength.
 */
@Mixin(ProjectileWeaponItem.class)
public abstract class PaperRangedWeaponMixin {
	@Shadow
	protected abstract void shoot(ServerLevel level, LivingEntity shooter, InteractionHand hand, ItemStack weapon, List<ItemStack> projectiles,
			float speed, float divergence, boolean critical, LivingEntity target, float drawStrength);

	/** Implements {@code net.spell_engine.mixin.item.RangedWeaponAccessor#shootAll_SpellEngine}. */
	public void shootAll_SpellEngine(ServerLevel level, LivingEntity shooter, InteractionHand hand, ItemStack weapon, List<ItemStack> projectiles,
			float speed, float divergence, boolean critical, LivingEntity target) {
		this.shoot(level, shooter, hand, weapon, projectiles, speed, divergence, critical, target, 1.0F);
	}
}
