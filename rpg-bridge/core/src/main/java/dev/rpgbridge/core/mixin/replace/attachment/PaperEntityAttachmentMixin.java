/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 * Replaces the save injector of fabric-data-attachment-api-v1 EntityMixin (Apache-2.0, FabricMC).
 */
package dev.rpgbridge.core.mixin.replace.attachment;

import net.fabricmc.fabric.impl.attachment.AttachmentTargetImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper saves entities through {@code saveWithoutId(ValueOutput, boolean, boolean, boolean)}. */
@Mixin(Entity.class)
public abstract class PaperEntityAttachmentMixin {
	@Inject(method = "saveWithoutId(Lnet/minecraft/world/level/storage/ValueOutput;ZZZ)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;addAdditionalSaveData(Lnet/minecraft/world/level/storage/ValueOutput;Z)V"))
	private void rpgbridge$writeAttachments(ValueOutput output, boolean includeAll, boolean includeNonSaveable, boolean forceSerialization, CallbackInfo ci) {
		((AttachmentTargetImpl) this).fabric_writeAttachmentsToNbt(output);
	}
}
