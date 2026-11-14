/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core.mixin.bukkit;

import dev.rpgbridge.core.bukkit.ModdedEntities;
import net.minecraft.world.entity.Entity;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftEntityType;
import org.bukkit.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** CraftBukkit has no wrapper class for mod entities: use {@link ModdedEntities}. */
@Mixin(CraftEntity.class)
public abstract class CraftEntityMixin {
	@Inject(method = "getEntity", at = @At("HEAD"), cancellable = true)
	private static void rpgbridge$moddedEntity(CraftServer server, Entity entity, CallbackInfoReturnable<CraftEntity> cir) {
		if (CraftEntityType.minecraftToBukkit(entity.getType()) == EntityType.UNKNOWN) {
			cir.setReturnValue(ModdedEntities.wrap(server, entity));
		}
	}
}
