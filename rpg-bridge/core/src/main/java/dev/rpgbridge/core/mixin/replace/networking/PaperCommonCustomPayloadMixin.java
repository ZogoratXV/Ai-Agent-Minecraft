package dev.rpgbridge.core.mixin.replace.networking;

import net.fabricmc.fabric.impl.networking.PacketListenerExtensions;
import net.fabricmc.fabric.impl.networking.server.ServerConfigurationNetworkAddon;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces Fabric API's ServerCommonPacketListenerImplMixin#handleCustomPayloadReceivedAsync (Apache-2.0, adapted).
 * Fabric only expects the configuration listener here (play payloads are handled in ServerGamePacketListenerImpl and
 * never reach the common method in vanilla). Paper's ServerGamePacketListenerImpl.handleCustomPayload calls super
 * for payloads Fabric did not handle (plugin channels, e.g. voice chat), and Fabric threw "Unknown addon", kicking
 * the player. Same behaviour for configuration; anything else continues to Paper's plugin messaging.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class PaperCommonCustomPayloadMixin {
	@Shadow
	@Final
	protected MinecraftServer server;

	@Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
	private void rpgbridge$handleConfigurationPayload(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
		if (!(((PacketListenerExtensions) this).getAddon() instanceof ServerConfigurationNetworkAddon addon)) return;
		try {
			if (addon.handle(packet.payload())) ci.cancel();
		} catch (RunningOnDifferentThreadException e) {
			server.packetProcessor().scheduleIfPossible((ServerCommonPacketListenerImpl) (Object) this, packet);
			ci.cancel();
		}
	}
}
