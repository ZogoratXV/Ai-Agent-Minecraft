package dev.rpgbridge.core.mixin.replace.networking;

import dev.rpgbridge.core.bukkit.ChannelRegistrar;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In play, Fabric API handles (and cancels) minecraft:register at the head of Paper's
 * ServerGamePacketListenerImpl.handleCustomPayload: forward the channels to Paper first (priority before Fabric).
 */
@Mixin(value = ServerGamePacketListenerImpl.class, priority = 900)
public abstract class PaperGameChannelRegistrationMixin {
	@Inject(method = "handleCustomPayload", at = @At("HEAD"))
	private void rpgbridge$forwardChannels(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
		((ChannelRegistrar) this).rpgbridge$forwardRegistration(packet.payload());
	}
}
