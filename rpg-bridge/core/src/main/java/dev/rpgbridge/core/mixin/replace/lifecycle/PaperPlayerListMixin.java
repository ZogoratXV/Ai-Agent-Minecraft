/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 * Replaces the reload hook of fabric-lifecycle-events-v1 PlayerListMixin (Apache-2.0, FabricMC).
 */
package dev.rpgbridge.core.mixin.replace.lifecycle;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper split {@code reloadResources()} into {@code reloadTagData()} and {@code reloadRecipes()}. */
@Mixin(PlayerList.class)
public abstract class PaperPlayerListMixin {
	@Inject(method = "reloadResources()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;reloadRecipes()V"))
	private void rpgbridge$onDataPacksReloaded(CallbackInfo ci) {
		for (ServerPlayer player : ((PlayerList) (Object) this).getPlayers()) {
			ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.invoker().onSyncDataPackContents(player, false);
		}
	}
}
