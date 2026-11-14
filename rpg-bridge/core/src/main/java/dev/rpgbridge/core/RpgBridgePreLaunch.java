/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core;

import dev.rpgbridge.core.compat.MixinBlocker;
import dev.rpgbridge.core.compat.MixinCompat;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.spongepowered.asm.mixin.Mixins;

/** Runs before any game class is loaded. */
public final class RpgBridgePreLaunch implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		MixinBlocker.apply();
		Mixins.registerErrorHandlerClass(MixinCompat.class.getName());
	}
}
