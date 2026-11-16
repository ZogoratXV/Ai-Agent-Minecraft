/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core.mixin.bukkit;

import dev.rpgbridge.core.bukkit.ModdedBlockEntityState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.World;
import org.bukkit.craftbukkit.block.CraftBlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Factory predefinita di CraftBlockStates: per un blocco con block entity (solo quelli delle mod) usa un TileState generico. */
@Mixin(targets = "org.bukkit.craftbukkit.block.CraftBlockStates$1")
public abstract class DefaultBlockStateFactoryMixin {
	@Inject(method = "createBlockState", at = @At("HEAD"), cancellable = true)
	private void rpgbridge$moddedBlockEntity(World world, BlockPos pos, BlockState state, BlockEntity blockEntity,
			CallbackInfoReturnable<CraftBlockState> cir) {
		if (blockEntity != null) cir.setReturnValue(new ModdedBlockEntityState(world, blockEntity));
	}
}
