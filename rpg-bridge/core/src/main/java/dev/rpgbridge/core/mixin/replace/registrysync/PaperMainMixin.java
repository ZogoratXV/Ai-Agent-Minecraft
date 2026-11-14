/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 * Replaces fabric-registry-sync-v0 MainMixin (Apache-2.0, FabricMC) for Paper.
 */
package dev.rpgbridge.core.mixin.replace.registrysync;

import net.fabricmc.fabric.impl.registry.sync.trackers.vanilla.BlockInitTracker;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Main;
import net.minecraft.world.item.CreativeModeTabs;
import dev.rpgbridge.core.compat.DelayedFreeze;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freezes the built-in registries once the mods are initialised (Fabric Loader runs the mod
 * initialisers right before {@code server.properties} is read, i.e. before this point), running
 * Paper's registry bootstrap and freeze listeners as Paper would.
 */
@Mixin(Main.class)
public abstract class PaperMainMixin {
	@Inject(method = "main(Ljoptsimple/OptionSet;)V",
			at = @At(value = "NEW", target = "net/minecraft/server/dedicated/DedicatedServerSettings"))
	private static void rpgbridge$afterModInit(CallbackInfo info) {
		Runnable paper = DelayedFreeze.paperBootstrap;
		if (paper == null) throw new IllegalStateException("Registry bootstrap was not intercepted");
		DelayedFreeze.paperBootstrap = null;
		BuiltInRegistries.bootStrap(paper); // createContents() is a no-op the second time (Fabric's BuiltInRegistriesMixin)
		BlockInitTracker.postFreeze();
		CreativeModeTabs.validate();
	}
}
