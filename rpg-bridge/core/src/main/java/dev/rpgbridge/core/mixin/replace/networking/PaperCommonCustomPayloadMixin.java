package dev.rpgbridge.core.mixin.replace.networking;

import dev.rpgbridge.core.bukkit.ChannelRegistrar;
import java.nio.charset.StandardCharsets;
import net.fabricmc.fabric.impl.networking.PacketListenerExtensions;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
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
 * <p>
 * Also: Paper learns which plugin channels a client listens on from raw minecraft:register payloads, but Fabric API
 * decodes them as RegistrationPayload and consumes them, so Paper never knew the channels of Fabric client mods and
 * silently dropped plugin messages sent to them (Player.sendPluginMessage). The channels are forwarded to Paper.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class PaperCommonCustomPayloadMixin implements ChannelRegistrar {
	@Shadow
	@Final
	protected MinecraftServer server;

	@Shadow
	private void readChannelIdentifier(byte[] data, int from, int to, boolean register) {
	}

	@Override
	public void rpgbridge$forwardRegistration(CustomPacketPayload payload) {
		if (!(payload instanceof RegistrationPayload registration)) return;
		boolean register = registration.type() == RegistrationPayload.REGISTER;
		var channels = java.util.List.copyOf(registration.channels());
		// Paper fires PlayerRegisterChannelEvent, which must run on the server thread (Fabric handles this on Netty).
		server.execute(() -> {
			for (var channel : channels) {
				byte[] bytes = channel.toString().getBytes(StandardCharsets.US_ASCII);
				readChannelIdentifier(bytes, 0, bytes.length, register);
			}
		});
	}

	@Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
	private void rpgbridge$handleConfigurationPayload(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
		rpgbridge$forwardRegistration(packet.payload());
		if (!(((PacketListenerExtensions) this).getAddon() instanceof ServerConfigurationNetworkAddon addon)) return;
		try {
			if (addon.handle(packet.payload())) ci.cancel();
		} catch (RunningOnDifferentThreadException e) {
			server.packetProcessor().scheduleIfPossible((ServerCommonPacketListenerImpl) (Object) this, packet);
			ci.cancel();
		}
	}
}
