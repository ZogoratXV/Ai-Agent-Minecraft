/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core.mixin.access;

import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BuiltInRegistries.class)
public interface BuiltInRegistriesInvoker {
	@Invoker("createContents")
	static void rpgbridge$createContents() {
		throw new AssertionError();
	}
}
