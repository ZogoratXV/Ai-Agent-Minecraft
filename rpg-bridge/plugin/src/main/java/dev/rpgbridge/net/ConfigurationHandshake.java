package dev.rpgbridge.net;

import dev.rpgbridge.Dump;
import io.netty.channel.Channel;
import io.papermc.paper.connection.PaperCommonConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import net.kyori.adventure.text.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.tags.TagNetworkSerialization;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plays the server side of the Fabric API / Spell Engine / Better Combat configuration
 * handshakes for modded clients. Runs on Paper's configuration thread pool, after Purpur
 * has already sent the vanilla registries and tags.
 */
public final class ConfigurationHandshake implements Listener {
	private static final long TIMEOUT_SECONDS = 15;
	/** Serverbound configuration channels we answer; announced to the client. */
	private static final List<String> SERVERBOUND = List.of(
			"fabric:registry/sync/complete", "spell_engine:ack", "bettercombat:ack");

	private final Logger log;
	private final Dump dump;
	private final List<ClientboundRegistryDataPacket> extraRegistries;
	private final ClientboundUpdateTagsPacket extraTags;

	public ConfigurationHandshake(Logger log, Dump dump, MinecraftServer server) throws Exception {
		this.log = log;
		this.dump = dump;
		// Data-driven registries the clients need but Purpur doesn't have (e.g. minecraft:spell).
		var serverRegistries = server.registryAccess();
		this.extraRegistries = dump.registryData().stream()
				.filter(p -> serverRegistries.lookup(p.registry()).isEmpty())
				.toList();
		// Tags: the new registries + static registries (raw ids identical to the injected ones).
		Map<ResourceKey<? extends net.minecraft.core.Registry<?>>, TagNetworkSerialization.NetworkPayload> tags = new LinkedHashMap<>();
		var extraKeys = extraRegistries.stream().map(ClientboundRegistryDataPacket::registry).toList();
		dump.tags().getTags().forEach((key, payload) -> {
			if (extraKeys.contains(key) || BuiltInRegistries.REGISTRY.containsKey(key.identifier())) tags.put(key, payload);
		});
		this.extraTags = new ClientboundUpdateTagsPacket(tags);
		log.info("Extra registries for modded clients: " + extraKeys.stream().map(k -> k.identifier().toString()).toList());
	}

	@EventHandler
	public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
		Connection connection;
		try {
			connection = connectionOf(event.getConnection());
		} catch (ReflectiveOperationException e) {
			log.log(Level.SEVERE, "Cannot access the connection", e);
			return;
		}
		BridgeSession session = BridgeSession.get(connection.channel);
		if (session == null) return;
		try {
			// The Fabric client registers its channels right at the start of the configuration phase.
			session.registered().get(3, TimeUnit.SECONDS);
		} catch (Exception ignored) {
			// vanilla (or non-Fabric) client: nothing to do
		}
		if (!session.isModded()) return;

		String name = event.getConnection().getProfile().getName();
		try {
			send(connection, register(SERVERBOUND));

			send(connection, payload("fabric:registry/sync", dump.fabricRegistrySync));
			await(session, "fabric:registry/sync/complete", name);

			send(connection, payload("spell_engine:config_sync", dump.spellEngineConfigSync));
			await(session, "spell_engine:ack|spell_engine:config", name);
			send(connection, payload("spell_engine:spell_registry_sync", dump.spellEngineSpellRegistrySync));
			await(session, "spell_engine:ack|spell_engine:spell_registry", name);

			if (session.clientChannels.contains("bettercombat:config_sync")) {
				send(connection, payload("bettercombat:config_sync", dump.betterCombatConfigSync));
				await(session, "bettercombat:ack|bettercombat:config", name);
				send(connection, payload("bettercombat:weapon_registry", dump.betterCombatWeaponRegistry));
				await(session, "bettercombat:ack|bettercombat:weapon_registry", name);
			}

			for (ClientboundRegistryDataPacket packet : extraRegistries) send(connection, packet);
			send(connection, extraTags);
			log.info(name + ": modded handshake completed");
		} catch (TimeoutException e) {
			event.getConnection().disconnect(Component.text("RPG Bridge: handshake timed out (" + e.getMessage() + ")"));
		} catch (Exception e) {
			log.log(Level.SEVERE, name + ": modded handshake failed", e);
			event.getConnection().disconnect(Component.text("RPG Bridge: handshake failed, see server log"));
		}
	}

	private void await(BridgeSession session, String key, String name) throws Exception {
		try {
			session.expect(key).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			log.warning(name + ": no reply for " + key);
			throw new TimeoutException(key);
		}
	}

	private static void send(Connection connection, Packet<?> packet) {
		connection.send(packet);
	}

	private static Packet<?> payload(String channel, byte[] data) {
		return new ClientboundCustomPayloadPacket(new DiscardedPayload(Identifier.parse(channel), data));
	}

	private static Packet<?> register(List<String> channels) {
		return payload("minecraft:register", String.join("\0", channels).getBytes(StandardCharsets.UTF_8));
	}

	private static Field packetListenerField;

	private static Connection connectionOf(Object apiConnection) throws ReflectiveOperationException {
		if (packetListenerField == null) {
			Field f = PaperCommonConnection.class.getDeclaredField("packetListener");
			f.setAccessible(true);
			packetListenerField = f;
		}
		return ((ServerCommonPacketListenerImpl) packetListenerField.get(apiConnection)).connection;
	}

	/** Installs the per-connection handler; called for every new Netty channel. */
	public static void install(Channel channel) {
		BridgeSession session = new BridgeSession();
		channel.attr(BridgeSession.KEY).set(session);
		if (channel.pipeline().get("packet_handler") != null) {
			channel.pipeline().addBefore("packet_handler", BridgeChannelHandler.NAME, new BridgeChannelHandler(session));
		} else {
			channel.pipeline().addLast(BridgeChannelHandler.NAME, new BridgeChannelHandler(session));
		}
	}
}
