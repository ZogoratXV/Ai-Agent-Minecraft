package dev.rpgbridge.core.bukkit;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Implemented on ServerCommonPacketListenerImpl (see PaperCommonCustomPayloadMixin). */
public interface ChannelRegistrar {
	/** Tells Paper about channels a Fabric client (un)registered with minecraft:register / unregister. */
	void rpgbridge$forwardRegistration(CustomPacketPayload payload);
}
