/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core.mixin.bukkit;

import org.bukkit.Registry;
import org.bukkit.craftbukkit.entity.CraftEntityType;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mod entity types are not in Bukkit's EntityType enum: report them as {@link EntityType#UNKNOWN}. */
@Mixin(CraftEntityType.class)
public abstract class CraftEntityTypeMixin {
	@Inject(method = "minecraftToBukkit", at = @At("HEAD"), cancellable = true)
	private static void rpgbridge$moddedType(net.minecraft.world.entity.EntityType<?> minecraft, CallbackInfoReturnable<EntityType> cir) {
		if (minecraft != null && Registry.ENTITY_TYPE.get(CraftNamespacedKey.fromMinecraft(net.minecraft.world.entity.EntityType.getKey(minecraft))) == null) {
			cir.setReturnValue(EntityType.UNKNOWN);
		}
	}
}
