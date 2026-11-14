/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core.mixin.paper;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ca.spottedleaf.moonrise.patches.chunk_system.entity.ChunkSystemEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper's chunk system (Moonrise) caches {@code canBeCollidedWith()} at the very start of the Entity
 * constructor, before {@code entityData} exists. Vanilla calls it lazily, so mod entities may read their
 * synched data in it (Spell Engine's SummonedEntity does) and crash. If the early call fails, compute the
 * value again at the end of the Entity constructor, when the synched data is defined.
 */
@Mixin(Entity.class)
public abstract class EntityHardCollidingMixin {
	@Shadow
	@Final
	@Mutable
	private boolean isHardColliding;

	@Unique
	private boolean rpgbridge$hardCollidingDeferred;

	@WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;moonrise$isHardCollidingUncached()Z"))
	private boolean rpgbridge$safeHardColliding(Entity entity, Operation<Boolean> original) {
		try {
			return original.call(entity);
		} catch (RuntimeException e) {
			this.rpgbridge$hardCollidingDeferred = true;
			return false;
		}
	}

	@Inject(method = "<init>", at = @At("RETURN"))
	private void rpgbridge$computeDeferredHardColliding(CallbackInfo ci) {
		if (!this.rpgbridge$hardCollidingDeferred) return;
		this.rpgbridge$hardCollidingDeferred = false;
		try {
			this.isHardColliding = ((ChunkSystemEntity) this).moonrise$isHardCollidingUncached();
		} catch (RuntimeException ignored) {
			// still not computable: keep "not hard colliding", like an entity without collision box
		}
	}
}
