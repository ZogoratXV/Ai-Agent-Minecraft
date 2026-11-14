/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core.selftest;

import net.fabricmc.fabric.impl.networking.CommonRegisterPayload;
import net.fabricmc.fabric.impl.networking.CommonVersionPayload;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.fabricmc.fabric.impl.registry.sync.SyncCompletePayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A headless client that joins this server like a Fabric client with the modpack: it registers the
 * mods' channels, answers the configuration handshakes (Fabric registry sync, Spell Engine, Better
 * Combat), enters the game and checks that a modded item stack arrives intact.
 *
 * <p>It runs inside the server JVM so it decodes packets with exactly the registries a Fabric client
 * reconstructs from the registry sync (the server's own).
 */
public final class SelfTestBot {
	private final MinecraftServer server;
	private final String name;
	private final Consumer<String> report;
	private final Connection connection;
	private final Set<String> events = Collections.synchronizedSet(new LinkedHashSet<>());
	private final Map<String, Integer> playPackets = new ConcurrentHashMap<>();
	private final Map<String, Integer> playPayloads = new ConcurrentHashMap<>();
	private final List<String> items = Collections.synchronizedList(new ArrayList<>());
	private final CompletableFuture<String> done = new CompletableFuture<>();
	private volatile boolean sentChannels;
	private volatile boolean castStarted;
	private volatile float zombieHealth = -1;
	private volatile net.minecraft.world.entity.LivingEntity zombie;

	private SelfTestBot(MinecraftServer server, String name, Consumer<String> report) {
		this.server = server;
		this.name = name;
		this.report = report;
		int port = server.getPort() > 0 ? server.getPort() : 25565;
		this.connection = Connection.connectToServer(new InetSocketAddress("127.0.0.1", port), EventLoopGroupHolder.remote(false), null);
	}

	/** Runs the whole test asynchronously; {@code report} receives log lines and the final verdict. */
	public static CompletableFuture<String> run(MinecraftServer server, String name, Consumer<String> report) {
		CompletableFuture<String> result = new CompletableFuture<>();
		Thread t = new Thread(() -> {
			try {
				SelfTestBot bot = new SelfTestBot(server, name, report);
				result.complete(bot.execute());
			} catch (Throwable e) {
				report.accept("FAILED: " + e);
				result.complete("FAILED: " + e);
			}
		}, "RPGBridge-SelfTest");
		t.setDaemon(true);
		t.start();
		return result;
	}

	private String execute() throws Exception {
		int port = server.getPort() > 0 ? server.getPort() : 25565;
		connection.initiateServerboundPlayConnection("127.0.0.1", port, LoginProtocols.SERVERBOUND, LoginProtocols.CLIENTBOUND,
				proxy(ClientLoginPacketListener.class, ConnectionProtocol.LOGIN, this::onLogin), false);
		connection.send(new ServerboundHelloPacket(name, UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8))));
		String verdict;
		try {
			verdict = done.get(45, TimeUnit.SECONDS);
		} catch (java.util.concurrent.TimeoutException e) {
			verdict = "FAILED: timeout";
		}
		report.accept("events: " + events);
		report.accept("play packets: " + new TreeMap<>(playPackets));
		report.accept("play payloads: " + new TreeMap<>(playPayloads));
		report.accept("items received: " + items);
		report.accept(verdict);
		connection.disconnect(net.minecraft.network.chat.Component.literal("self-test finished"));
		return verdict;
	}

	// ---------------------------------------------------------------- login

	private Object onLogin(String method, Object packet) {
		switch (packet) {
			case ClientboundLoginCompressionPacket p -> connection.setupCompression(p.getCompressionThreshold(), false);
			case ClientboundLoginFinishedPacket p -> {
				events.add("login");
				connection.setupInboundProtocol(ConfigurationProtocols.CLIENTBOUND,
						proxy(ClientConfigurationPacketListener.class, ConnectionProtocol.CONFIGURATION, this::onConfiguration));
				connection.send(ServerboundLoginAcknowledgedPacket.INSTANCE);
				connection.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
				// Paper keeps serverbound brand as raw bytes (plugin messaging): send it as a DiscardedPayload.
				var brand = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
				brand.writeUtf("fabric");
				byte[] brandBytes = new byte[brand.readableBytes()];
				brand.readBytes(brandBytes);
				connection.send(new ServerboundCustomPayloadPacket(new net.minecraft.network.protocol.common.custom.DiscardedPayload(
						BrandPayload.TYPE.id(), brandBytes)));
				connection.send(new ServerboundClientInformationPacket(ClientInformation.createDefault()));
			}
			case ClientboundLoginDisconnectPacket p -> done.complete("FAILED: disconnected during login: " + p.reason().getString());
			default -> { }
		}
		return null;
	}

	// ---------------------------------------------------------------- configuration

	private Object onConfiguration(String method, Object packet) {
		switch (packet) {
			case ClientboundCustomPayloadPacket p -> onConfigPayload(p.payload());
			case ClientboundSelectKnownPacks p -> connection.send(new ServerboundSelectKnownPacks(List.of()));
			case ClientboundRegistryDataPacket p -> events.add("registry:" + p.registry().identifier());
			case ClientboundKeepAlivePacket p -> connection.send(new ServerboundKeepAlivePacket(p.getId()));
			case ClientboundPingPacket p -> connection.send(new ServerboundPongPacket(p.getId()));
			case ClientboundFinishConfigurationPacket p -> {
				events.add("configuration-finished");
				var registries = server.registryAccess();
				connection.setupInboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries)),
						proxy(ClientGamePacketListener.class, ConnectionProtocol.PLAY, this::onGame));
				connection.send(ServerboundFinishConfigurationPacket.INSTANCE);
				connection.setupOutboundProtocol(GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries), () -> false));
			}
			case net.minecraft.network.protocol.common.ClientboundDisconnectPacket p ->
					done.complete("FAILED: disconnected during configuration: " + p.reason().getString());
			default -> events.add("config:" + packet.getClass().getSimpleName());
		}
		return null;
	}

	private void onConfigPayload(CustomPacketPayload payload) {
		String id = payload.type().id().toString();
		events.add("payload:" + id);
		switch (id) {
			case "minecraft:register" -> {
				// A Fabric client answers the server's registration with its own channels.
				if (!sentChannels) {
					sentChannels = true;
					send(new RegistrationPayload(RegistrationPayload.REGISTER, channels(PayloadTypeRegistryImpl.CLIENTBOUND_CONFIGURATION)));
				}
			}
			case "c:version" -> send(new CommonVersionPayload(new int[] {1}));
			case "c:register" -> send(new CommonRegisterPayload(1, CommonRegisterPayload.PLAY_PROTOCOL,
					new LinkedHashSet<>(channels(PayloadTypeRegistryImpl.CLIENTBOUND_PLAY))));
			case "fabric:registry/sync" -> send(SyncCompletePayload.INSTANCE);
			case "fabric:custom_ingredient_sync" -> send(net.fabricmc.fabric.impl.recipe.ingredient.CustomIngredientSync.createResponsePayload(
					((net.fabricmc.fabric.impl.recipe.ingredient.ClientboundCustomIngredientPayload) payload).protocolVersion()));
			case "fabric:accepted_attachments_v1" -> send(new net.fabricmc.fabric.impl.attachment.sync.serverbound.ServerboundAcceptedAttachmentsPayload(
					new java.util.HashSet<>(net.fabricmc.fabric.impl.attachment.AttachmentRegistryImpl.getSyncableAttachments())));
			case "spell_engine:config_sync" -> send(new net.spell_engine.network.Packets.Ack("spell_engine:config"));
			case "spell_engine:spell_registry_sync" -> send(new net.spell_engine.network.Packets.Ack("spell_engine:spell_registry"));
			case "bettercombat:config_sync" -> send(new net.bettercombat.network.Packets.Ack("bettercombat:config"));
			case "bettercombat:weapon_registry" -> send(new net.bettercombat.network.Packets.Ack("bettercombat:weapon_registry"));
			default -> { }
		}
	}

	private void send(CustomPacketPayload payload) {
		connection.send(new ServerboundCustomPayloadPacket(payload));
	}

	@SuppressWarnings("unchecked")
	private static List<Identifier> channels(PayloadTypeRegistryImpl<?> registry) {
		try {
			var f = PayloadTypeRegistryImpl.class.getDeclaredField("packetTypes");
			f.setAccessible(true);
			return new ArrayList<>(((Map<Identifier, ?>) f.get(registry)).keySet());
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	// ---------------------------------------------------------------- play

	private Object onGame(String method, Object packet) {
		if (packet instanceof ClientboundBundlePacket bundle) {
			for (Packet<?> sub : bundle.subPackets()) onGame("bundle", sub);
			return null;
		}
		playPackets.merge(packet.getClass().getSimpleName(), 1, Integer::sum);
		switch (packet) {
			case ClientboundLoginPacket p -> {
				events.add("joined");
				connection.send(new ServerboundPlayerLoadedPacket());
				if (sentChannels) send(new RegistrationPayload(RegistrationPayload.REGISTER, channels(PayloadTypeRegistryImpl.CLIENTBOUND_PLAY)));
				// Give the bot a modded item: it must arrive with the right id.
				server.execute(() -> {
					var player = server.getPlayerList().getPlayerByName(name);
					if (player == null) return;
					var item = BuiltInRegistries.ITEM.getOptional(Identifier.parse("wizards:wand_arcane"));
					if (item.isEmpty()) {
						done.complete("FAILED: wizards:wand_arcane not registered");
						return;
					}
					player.getInventory().add(new ItemStack(item.get()));
					// Arcane spells consume arcane runes (spell cost item).
					BuiltInRegistries.ITEM.getOptional(Identifier.parse("runes:arcane_stone"))
							.ifPresent(rune -> player.getInventory().add(new ItemStack(rune, 16)));
					// Keep other mobs out of the cast test.
					player.level().getServer().getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set day");
					player.level().getServer().getCommands().performPrefixedCommand(server.createCommandSourceStack(), "kill @e[type=!player,type=!painting,type=!item_frame,type=!glow_item_frame,type=!villager,type=!iron_golem,type=!item]");
					player.containerMenu.broadcastChanges();
				});
			}
			case ClientboundCustomPayloadPacket p -> playPayloads.merge(p.payload().type().id().toString(), 1, Integer::sum);
			case ClientboundKeepAlivePacket p -> connection.send(new ServerboundKeepAlivePacket(p.getId()));
			case ClientboundPingPacket p -> connection.send(new ServerboundPongPacket(p.getId()));
			case ClientboundPlayerPositionPacket p -> connection.send(new ServerboundAcceptTeleportationPacket(p.id()));
			case ClientboundChunkBatchFinishedPacket p -> connection.send(new ServerboundChunkBatchReceivedPacket(20.0F));
			case ClientboundContainerSetSlotPacket p -> onItem(p.getItem());
			case net.minecraft.network.protocol.game.ClientboundAddEntityPacket p -> spawned.add(BuiltInRegistries.ENTITY_TYPE.getKey(p.getType()).toString());
			case ClientboundContainerSetContentPacket p -> p.items().forEach(this::onItem);
			case net.minecraft.network.protocol.common.ClientboundDisconnectPacket p ->
					done.complete("FAILED: disconnected in game: " + p.reason().getString());
			default -> { }
		}
		return null;
	}

	private void onItem(ItemStack stack) {
		if (stack.isEmpty()) return;
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		items.add(id);
		if (id.equals("wizards:wand_arcane") && !castStarted) {
			castStarted = true;
			events.add("received " + id);
			castArcaneBolt();
		}
	}

	/** Casts wizards:arcane_bolt at a zombie in front of the bot, like the Spell Engine client does. */
	private void castArcaneBolt() {
		server.execute(() -> {
			var player = server.getPlayerList().getPlayerByName(name);
			if (player == null) return;
			var created = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:zombie"))
					.create(player.level(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			if (created instanceof net.minecraft.world.entity.Mob z) {
				var look = player.getLookAngle();
				z.setPos(player.getX() + look.x * 4, player.getY(), player.getZ() + look.z * 4);
				z.setNoAi(true);
				player.level().addFreshEntity(z);
				zombie = z;
				zombieHealth = z.getHealth();
			}
		});
		var spell = Identifier.parse("wizards:arcane_bolt");
		var delay = java.util.concurrent.CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS);
		java.util.concurrent.CompletableFuture.runAsync(() -> {
			var z = zombie;
			var snapshot = new net.spell_engine.internals.casting.SpellCast.TargetSnapshot(z == null ? List.of() : List.of(z.getId()), null);
			send(new net.spell_engine.network.Packets.CastRequest(spell, snapshot));
			events.add("cast_request sent");
			java.util.concurrent.CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS).execute(() -> server.execute(() -> {
				var player = server.getPlayerList().getPlayerByName(name);
				if (player == null) return;
				var source = net.spell_engine.internals.container.SpellContainerSource.getFirstSourceOfSpell(spell, player);
				var attempt = net.spell_engine.internals.casting.SpellCasting.attempt(player, player.getMainHandItem(), spell);
				var process = ((net.spell_engine.internals.casting.SpellCaster.Player) player).getInteractor().process();
				report.accept("cast diagnostics: mainHand=" + player.getMainHandItem() + " source=" + source
						+ " attempt=" + attempt.result() + " process=" + (process == null ? null : process.id()));
			}));
			java.util.concurrent.CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS).execute(() -> {
				send(new net.spell_engine.network.Packets.CastInput(spell, snapshot));
				events.add("cast_input sent");
				java.util.concurrent.CompletableFuture.delayedExecutor(2500, TimeUnit.MILLISECONDS).execute(this::checkCast);
			});
		}, delay);
	}

	private void checkCast() {
		server.execute(() -> {
			var z = zombie;
			float now = z == null ? -1 : (z.isAlive() ? z.getHealth() : 0);
			boolean projectile = playPackets.containsKey("ClientboundAddEntityPacket") && spawned.contains("spell_engine:spell_projectile");
			boolean cooldown = playPayloads.containsKey("spell_engine:spell_cooldown");
			report.accept("cast: projectile=" + projectile + " cooldown=" + cooldown + " zombie health " + zombieHealth + " -> " + now + " spawned=" + spawned);
			if (z != null) z.discard();
			if (projectile && now >= 0 && now < zombieHealth) {
				castResult = "wizards:arcane_bolt hit (zombie " + zombieHealth + " -> " + now + ")";
				betterCombatAttack();
			} else {
				done.complete("FAILED: arcane bolt did not damage the target");
			}
		});
	}

	private final Set<String> spawned = Collections.synchronizedSet(new LinkedHashSet<>());
	private volatile String castResult;

	/** Better Combat: hold a sword and send an attack request for a zombie in reach. */
	private void betterCombatAttack() {
		server.execute(() -> {
			var player = server.getPlayerList().getPlayerByName(name);
			if (player == null) return;
			var sword = BuiltInRegistries.ITEM.getValue(Identifier.parse("minecraft:iron_sword"));
			int slot = player.getInventory().getSelectedSlot();
			player.getInventory().setItem(slot, new ItemStack(sword));
			player.containerMenu.broadcastChanges();
			var created = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:zombie"))
					.create(player.level(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			if (!(created instanceof net.minecraft.world.entity.Mob z)) return;
			var look = player.getLookAngle();
			z.setPos(player.getX() + look.x * 2, player.getY(), player.getZ() + look.z * 2);
			z.setNoAi(true);
			player.level().addFreshEntity(z);
			zombie = z;
			zombieHealth = z.getHealth();
			int id = z.getId();
			java.util.concurrent.CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS).execute(() -> {
				send(new net.bettercombat.network.Packets.C2S_AttackRequest(0, false, slot, id, new int[] {id}));
				events.add("bettercombat attack_request sent");
				java.util.concurrent.CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS).execute(() -> server.execute(() -> {
					float now = z.isAlive() ? z.getHealth() : 0;
					report.accept("bettercombat: zombie health " + zombieHealth + " -> " + now);
					z.discard();
					if (now < zombieHealth) {
						done.complete("PASSED: Fabric handshakes, modded item, " + castResult
								+ ", Better Combat attack hit (zombie " + zombieHealth + " -> " + now + ")");
					} else {
						done.complete("FAILED: Better Combat attack did not damage the target (" + castResult + ")");
					}
				}));
			});
		});
	}

	// ---------------------------------------------------------------- listener proxies

	@FunctionalInterface
	private interface Handler {
		Object handle(String method, Object packet);
	}

	@SuppressWarnings("unchecked")
	private <T> T proxy(Class<T> type, ConnectionProtocol protocol, Handler handler) {
		InvocationHandler ih = (Object proxy, Method method, Object[] args) -> {
			String n = method.getName();
			switch (n) {
				case "protocol":
					return protocol;
				case "isAcceptingMessages":
				case "shouldHandleMessage":
					return true;
				case "onDisconnect":
					DisconnectionDetails details = (DisconnectionDetails) args[0];
					done.complete("FAILED: connection closed in " + protocol + ": " + details.reason().getString());
					return null;
				case "onPacketError":
					done.complete("FAILED: packet error in " + protocol + ": " + args[1]);
					return null;
				case "hashCode":
					return System.identityHashCode(proxy);
				case "equals":
					return proxy == args[0];
				case "toString":
					return "SelfTestBot[" + protocol + "]";
				default:
					break;
			}
			if (n.startsWith("handle") && args != null && args.length == 1 && args[0] instanceof Packet<?>) {
				return handler.handle(n, args[0]);
			}
			if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
			Class<?> r = method.getReturnType();
			if (r == boolean.class) return false;
			if (r.isPrimitive() && r != void.class) return 0;
			return null;
		};
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, ih);
	}
}
