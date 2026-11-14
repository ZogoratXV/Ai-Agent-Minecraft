/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core.mixin.paper;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric API handles configuration payloads on the network thread and completes its configuration
 * tasks from there ({@code completeTask}, added by fabric-networking-api). Completing the last Fabric
 * task resumes the vanilla configuration, where Paper fires Bukkit events that must run on the server
 * thread. Move task completion to the server thread, where vanilla completes its own tasks.
 */
@Mixin(value = ServerConfigurationPacketListenerImpl.class, priority = 1500)
public abstract class ConfigurationTaskThreadMixin {
	@Inject(method = "completeTask", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void rpgbridge$completeOnServerThread(ConfigurationTask.Type type, CallbackInfo ci) {
		MinecraftServer server = ((ServerCommonPacketListenerImplAccessor) this).rpgbridge$server();
		if (!server.isSameThread()) {
			ci.cancel();
			server.execute(() -> ((net.fabricmc.fabric.api.networking.v1.FabricServerConfigurationPacketListenerImpl) (Object) this).completeTask(type));
		}
	}
}
