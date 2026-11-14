/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 * Replaces fabric-registry-sync-v0 BootstrapMixin (Apache-2.0, FabricMC) for Paper.
 */
package dev.rpgbridge.core.mixin.replace.registrysync;

import dev.rpgbridge.core.mixin.access.BuiltInRegistriesInvoker;
import net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager;
import net.fabricmc.fabric.impl.registry.sync.trackers.StateIdTracker;
import net.fabricmc.fabric.impl.registry.sync.trackers.vanilla.BlockItemTracker;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import dev.rpgbridge.core.compat.DelayedFreeze;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper replaced {@code BuiltInRegistries.bootStrap()} with {@code bootStrap(Runnable)} (the
 * runnable registers Paper's own entries, the freeze runs Paper's registry listeners). Like Fabric,
 * only create the contents here and keep the registries open for the mods; the real
 * {@code bootStrap(Runnable)} runs after mod initialisation, see {@link PaperMainMixin}.
 */
@Mixin(Bootstrap.class)
public abstract class PaperBootstrapMixin {
	@Inject(method = "bootStrap", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/Bootstrap;wrapStreams()V"))
	private static void rpgbridge$afterInitialize(CallbackInfo info) {
		// Same as Fabric: make sure vanilla blocks/items/fluids are registered first.
		Object oBlock = Blocks.AIR;
		Object oFluid = Fluids.EMPTY;
		Object oItem = Items.AIR;
		StateIdTracker.register(BuiltInRegistries.BLOCK, Block.BLOCK_STATE_REGISTRY, block -> block.getStateDefinition().getPossibleStates());
		StateIdTracker.register(BuiltInRegistries.FLUID, Fluid.FLUID_STATE_REGISTRY, fluid -> fluid.getStateDefinition().getPossibleStates());
		BlockItemTracker.register(BuiltInRegistries.ITEM);
		RegistrySyncManager.bootstrapRegistries();
	}

	@Redirect(method = "bootStrap", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/registries/BuiltInRegistries;bootStrap(Ljava/lang/Runnable;)V"))
	private static void rpgbridge$delayRegistryFreeze(Runnable paperBootstrap) {
		BuiltInRegistriesInvoker.rpgbridge$createContents();
		DelayedFreeze.paperBootstrap = paperBootstrap;
	}
}
