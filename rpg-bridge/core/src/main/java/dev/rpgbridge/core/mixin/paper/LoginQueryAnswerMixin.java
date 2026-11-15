package dev.rpgbridge.core.mixin.paper;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fabricmc.fabric.impl.networking.payload.FriendlyByteBufLoginQueryResponse;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fabric API decodes every login query answer as its own FriendlyByteBufLoginQueryResponse, while Paper casts the
 * answer to vanilla's QueryAnswerPayload (Velocity modern forwarding, "velocity:player_info"): ClassCastException,
 * players behind Velocity cannot log in. Give Paper the same bytes in the type it expects.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class LoginQueryAnswerMixin {
	@WrapOperation(method = {"handleCustomQueryPacket", "handleCustomQueryPacket0"},
			at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/login/ServerboundCustomQueryAnswerPacket;payload()Lnet/minecraft/network/protocol/login/custom/CustomQueryAnswerPayload;"),
			require = 2)
	private CustomQueryAnswerPayload rpgbridge$paperPayload(ServerboundCustomQueryAnswerPacket packet, Operation<CustomQueryAnswerPayload> original) {
		CustomQueryAnswerPayload payload = original.call(packet);
		if (payload instanceof FriendlyByteBufLoginQueryResponse fabric) {
			// The buffer holds only the answer, but arrives with its reader index at the end: give Paper all of it.
			var data = fabric.data();
			return new ServerboundCustomQueryAnswerPacket.QueryAnswerPayload(
					new net.minecraft.network.FriendlyByteBuf(data.copy(0, data.writerIndex())));
		}
		return payload;
	}
}
