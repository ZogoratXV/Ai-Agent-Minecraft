package dev.rpgbridge.net;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Sits right before Purpur's {@code packet_handler}, so it sees decoded packets.
 * Records the client's channel registrations and consumes the replies of the
 * configuration handshakes driven by {@link ConfigurationHandshake}.
 */
public final class BridgeChannelHandler extends ChannelDuplexHandler {
	public static final String NAME = "rpgbridge";

	private final BridgeSession session;

	public BridgeChannelHandler(BridgeSession session) {
		this.session = session;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof ServerboundCustomPayloadPacket packet && packet.payload() instanceof DiscardedPayload payload) {
			String id = payload.id().toString();
			byte[] data = payload.data();
			switch (id) {
				case "minecraft:register" -> session.onRegister(splitNul(data)); // also let Purpur see it
				case "c:register" -> {
					session.onRegister(readCommonRegister(data));
					return;
				}
				case "c:version", "fabric:registry/sync/complete" -> {
					session.reply(id, data);
					return;
				}
				case "spell_engine:ack", "bettercombat:ack" -> {
					String code = new FriendlyByteBuf(Unpooled.wrappedBuffer(data)).readUtf();
					session.reply(id + "|" + code, data);
					return;
				}
				default -> { }
			}
		}
		super.channelRead(ctx, msg);
	}

	private static List<String> splitNul(byte[] data) {
		List<String> out = new ArrayList<>();
		for (String s : new String(data, StandardCharsets.UTF_8).split("\0")) {
			if (!s.isEmpty()) out.add(s);
		}
		return out;
	}

	private static List<String> readCommonRegister(byte[] data) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
		buf.readVarInt(); // version
		buf.readUtf(); // protocol
		int n = buf.readVarInt();
		List<String> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) out.add(buf.readIdentifier().toString());
		return out;
	}
}
