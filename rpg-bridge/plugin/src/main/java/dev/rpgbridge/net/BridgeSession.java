package dev.rpgbridge.net;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Per-connection bridge state, attached to the Netty channel. */
public final class BridgeSession {
	public static final AttributeKey<BridgeSession> KEY = AttributeKey.valueOf("rpgbridge_session");

	/** Channels the client declared it can receive ({@code minecraft:register} / {@code c:register}). */
	public final Set<String> clientChannels = ConcurrentHashMap.newKeySet();
	/** Pending replies, keyed by "channel" or "channel|ack code". */
	private final Map<String, CompletableFuture<byte[]>> replies = new ConcurrentHashMap<>();
	private final CompletableFuture<Void> registered = new CompletableFuture<>();

	public static BridgeSession get(Channel channel) {
		return channel.attr(KEY).get();
	}

	public boolean isModded() {
		return clientChannels.contains("fabric:registry/sync") && clientChannels.contains("spell_engine:config_sync");
	}

	public CompletableFuture<Void> registered() {
		return registered;
	}

	void onRegister(Iterable<String> channels) {
		channels.forEach(clientChannels::add);
		registered.complete(null);
	}

	public CompletableFuture<byte[]> expect(String key) {
		return replies.computeIfAbsent(key, k -> new CompletableFuture<>());
	}

	void reply(String key, byte[] data) {
		replies.computeIfAbsent(key, k -> new CompletableFuture<>()).complete(data);
	}
}
