package dev.rpgbridge.core.mixin.paper;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paper sends plugin messages (and its own minecraft:register / unregister for plugin channels) as raw bytes in a
 * DiscardedPayload. When a Fabric mod registered a typed codec for the same channel (Fabric API does for
 * minecraft:register), the codec gets a DiscardedPayload it cannot encode and the player is kicked
 * ("Failed to encode packet ... minecraft:register"). Raw payloads are written as the raw bytes Paper prepared.
 */
@Mixin(targets = "net.minecraft.network.protocol.common.custom.CustomPacketPayload$1")
public abstract class RawPluginMessageMixin {
	@WrapOperation(method = "writeCap",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/network/codec/StreamCodec;encode(Ljava/lang/Object;Ljava/lang/Object;)V"))
	@SuppressWarnings({"rawtypes", "unchecked"})
	private void rpgbridge$writeRawPayload(StreamCodec codec, Object buf, Object payload, Operation<Void> original) {
		if (payload instanceof DiscardedPayload raw && buf instanceof FriendlyByteBuf out) {
			out.writeBytes(raw.data());
			return;
		}
		original.call(codec, buf, payload);
	}
}
