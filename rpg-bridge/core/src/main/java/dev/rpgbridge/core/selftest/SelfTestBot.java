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
	private volatile boolean stay;
	/** Bots kept online after the test ({@code rpgbridge selftest <name> stay}), driven by {@code rpgbridge bot ...}. */
	private static final Map<String, SelfTestBot> ONLINE = new ConcurrentHashMap<>();
	private static final String PLUGIN_CHANNEL = "rpgbridge:plugin_test";
	private volatile boolean castStarted;
	private volatile float zombieHealth = -1;
	private volatile net.minecraft.world.entity.LivingEntity zombie;

	private SelfTestBot(MinecraftServer server, String name, Consumer<String> report) {
		this.server = server;
		this.name = name;
		this.report = report;
		int port = server.getPort() > 0 ? server.getPort() : 25565;
		this.connection = Connection.connectToServer(new InetSocketAddress("127.0.0.1", port), EventLoopGroupHolder.remote(false), null);
		// Paper's packet limiter lives in Connection, so it also counts what the bot (a client) receives: in a world
		// with many entities it kicked the bot. Real clients are not affected; turn it off for the bot's side only.
		try {
			var counts = Connection.class.getDeclaredField("allPacketCounts");
			counts.setAccessible(true);
			counts.set(connection, null);
		} catch (ReflectiveOperationException ignored) {
		}
	}

	/** Runs the whole test asynchronously; {@code report} receives log lines and the final verdict. */
	public static CompletableFuture<String> run(MinecraftServer server, String name, Consumer<String> report) {
		return run(server, name, report, false);
	}

	/** @param stay keep the bot online after the test, for {@link #attack} and {@link #leave} */
	public static CompletableFuture<String> run(MinecraftServer server, String name, Consumer<String> report, boolean stay) {
		CompletableFuture<String> result = new CompletableFuture<>();
		Thread t = new Thread(() -> {
			try {
				SelfTestBot bot = new SelfTestBot(server, name, report);
				bot.stay = stay;
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
		UUID botId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
		// The basic test checks the mods, not the server's class rules: exempt the bot while it runs.
		dev.rpgbridge.core.classlock.ClassLock.setExempt(botId, true);
		connection.send(new ServerboundHelloPacket(name, botId));
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
		// Kept online for console checks ("bot attack"): from now on the class rules apply to it.
		dev.rpgbridge.core.classlock.ClassLock.setExempt(botId, false);
		if (stay && connection.isConnected()) {
			ONLINE.put(name, this);
			report.accept(name + " stays online: rpgbridge bot attack " + name + " | rpgbridge bot leave " + name);
		} else {
			connection.disconnect(net.minecraft.network.chat.Component.literal("self-test finished"));
		}
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
			case net.minecraft.network.protocol.login.ClientboundCustomQueryPacket p -> onLoginQuery(p);
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
			case net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket p -> {
				if (done.isDone()) report.accept("action bar: " + p.text().getString());
			}
			case net.minecraft.network.protocol.game.ClientboundSystemChatPacket p -> {
				if (done.isDone()) report.accept((p.overlay() ? "action bar: " : "chat: ") + p.content().getString());
			}
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
				if (sentChannels) {
					// Like a Fabric client mod talking to a plugin: it also registers the plugin's channel.
					List<Identifier> playChannels = new ArrayList<>(channels(PayloadTypeRegistryImpl.CLIENTBOUND_PLAY));
					playChannels.add(Identifier.parse(PLUGIN_CHANNEL));
					send(new RegistrationPayload(RegistrationPayload.REGISTER, playChannels));
				}
				// Give the bot a modded item: it must arrive with the right id.
				server.execute(() -> {
					var player = server.getPlayerList().getPlayerByName(name);
					if (player == null) return;
					var item = BuiltInRegistries.ITEM.getOptional(Identifier.parse("wizards:wand_arcane"));
					if (item.isEmpty()) {
						done.complete("FAILED: wizards:wand_arcane not registered");
						return;
					}
					// Plugins' channels: Paper announces them with a raw minecraft:register payload, a channel Fabric
					// API also has a typed codec for (it kicked players: "Failed to encode packet").
					player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
							new net.minecraft.network.protocol.common.custom.DiscardedPayload(Identifier.parse("minecraft:register"),
									"rpgbridge:test\0".getBytes(StandardCharsets.UTF_8))));
					// A play payload on a channel no mod handles (plugin channel, e.g. voice chat): Paper passes it on
					// to plugin messaging through the common listener (Fabric threw "Unknown addon" there).
					send(new net.minecraft.network.protocol.common.custom.DiscardedPayload(Identifier.parse("rpgbridge:plugin_test"),
							new byte[] {1, 2, 3}));
					// A player saved by an earlier run may hold something else: start from an empty inventory.
					player.getInventory().clearContent();
					player.getInventory().setItem(player.getInventory().getSelectedSlot(), new ItemStack(item.get()));
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
			var created = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:husk")) // husk: does not burn in daylight (fire damage would fake a hit)
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
			var created = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:husk")) // husk: does not burn in daylight (fire damage would fake a hit)
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
						openModdedMenu("Fabric handshakes, modded item, " + castResult
								+ ", Better Combat attack hit (zombie " + zombieHealth + " -> " + now + ")");
					} else {
						done.complete("FAILED: Better Combat attack did not damage the target (" + castResult + ")");
					}
				}));
			});
		});
	}

	/**
	 * Mod menu: place Spell Engine's Spell Binding table and use it like a client (use-item-on packet), so
	 * Paper opens the mod menu and fires the Bukkit open/close events (needs a Bukkit view of the menu).
	 */
	private void openModdedMenu(String passed) {
		server.execute(() -> {
			var player = server.getPlayerList().getPlayerByName(name);
			if (player == null) return;
			var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse("spell_engine:spell_binding"));
			var pos = player.blockPosition().relative(player.getDirection(), 2);
			player.level().setBlockAndUpdate(pos, block.defaultBlockState());
			player.getInventory().setItem(player.getInventory().getSelectedSlot(), ItemStack.EMPTY);
			player.containerMenu.broadcastChanges();
			var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),
					net.minecraft.core.Direction.UP, pos, false);
			java.util.concurrent.CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS).execute(() -> {
				connection.send(new net.minecraft.network.protocol.game.ServerboundUseItemOnPacket(
						net.minecraft.world.InteractionHand.MAIN_HAND, hit, 1));
				events.add("use spell_engine:spell_binding sent");
				java.util.concurrent.CompletableFuture.delayedExecutor(1000, TimeUnit.MILLISECONDS).execute(() -> server.execute(() -> {
					var menu = player.containerMenu;
					String menuClass = menu.getClass().getName();
					if (!menuClass.contains("SpellBinding")) {
						done.complete("FAILED: Spell Binding menu not open (" + menuClass + ")");
						return;
					}
					var view = menu.getBukkitView();
					var top = view.getTopInventory();
					report.accept("menu: " + menuClass + " bukkit top=" + top.getType() + " size=" + top.getSize()
							+ " contents=" + top.getContents().length + " player=" + view.getPlayer().getName());
					int id = menu.containerId;
					connection.send(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(id));
					java.util.concurrent.CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS).execute(() -> server.execute(() -> {
						player.level().removeBlock(pos, false);
						// Channels the client registered (minecraft:register) must be known to Bukkit, or plugin
						// messages to client mods are dropped.
						// Bukkit Material of a mod item: must exist, and a plugin-made stack must be the real mod item.
						var wand = BuiltInRegistries.ITEM.getValue(Identifier.parse("wizards:wand_arcane"));
						var bukkitType = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(new ItemStack(wand)).getType();
						var roundTrip = bukkitType == null ? null : org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(new org.bukkit.inventory.ItemStack(bukkitType));
						String roundTripId = roundTrip == null ? "null" : BuiltInRegistries.ITEM.getKey(roundTrip.getItem()).toString();
						report.accept("Bukkit material: wizards:wand_arcane -> " + bukkitType + " (key " + (bukkitType == null ? null : bukkitType.getKey())
								+ ") -> new ItemStack -> " + roundTripId);
						if (bukkitType == null || !"wizards:wand_arcane".equals(roundTripId)) {
							done.complete("FAILED: Bukkit Material for mod items (" + bukkitType + " -> " + roundTripId + ")");
							return;
						}
						var listening = player.getBukkitEntity().getListeningPluginChannels();
						report.accept("Bukkit listening channels: " + listening.size() + " " + listening.stream().limit(5).toList());
						if (listening.isEmpty()) {
							done.complete("FAILED: Bukkit does not know the channels registered by the client");
						} else if (player.containerMenu == player.inventoryMenu) {
							pluginMessaging(player, passed + ", Spell Binding menu opened and closed (Bukkit "
									+ top.getType() + " view, " + top.getSize() + " slots), " + listening.size()
									+ " client channels known to Bukkit, Bukkit Material " + bukkitType);
						} else {
							done.complete("FAILED: Spell Binding menu did not close");
						}
					}));
				}));
			});
		});
	}

	/**
	 * Plugin messaging both ways, like client mods that talk to Bukkit plugins: a plugin (any loaded one stands in)
	 * sends on a channel the bot registered, and receives what the bot sends on it.
	 */
	private void pluginMessaging(net.minecraft.server.level.ServerPlayer player, String passed) {
		var plugin = java.util.Arrays.stream(org.bukkit.Bukkit.getPluginManager().getPlugins()).filter(org.bukkit.plugin.Plugin::isEnabled).findFirst().orElse(null);
		if (plugin == null) {
			done.complete("PASSED: " + passed + " (plugin messaging not tested: no plugin loaded)");
			return;
		}
		var messenger = org.bukkit.Bukkit.getMessenger();
		var received = new java.util.concurrent.atomic.AtomicInteger(-1);
		org.bukkit.plugin.messaging.PluginMessageListener listener = (channel, who, message) -> received.set(message.length);
		messenger.registerOutgoingPluginChannel(plugin, PLUGIN_CHANNEL);
		messenger.registerIncomingPluginChannel(plugin, PLUGIN_CHANNEL, listener);
		player.getBukkitEntity().sendPluginMessage(plugin, PLUGIN_CHANNEL, new byte[] {4, 5, 6, 7});
		send(new net.minecraft.network.protocol.common.custom.DiscardedPayload(Identifier.parse(PLUGIN_CHANNEL), new byte[] {1, 2, 3}));
		java.util.concurrent.CompletableFuture.delayedExecutor(1000, TimeUnit.MILLISECONDS).execute(() -> server.execute(() -> {
			messenger.unregisterIncomingPluginChannel(plugin, PLUGIN_CHANNEL, listener);
			messenger.unregisterOutgoingPluginChannel(plugin, PLUGIN_CHANNEL);
			boolean toClient = playPayloads.containsKey(PLUGIN_CHANNEL);
			report.accept("plugin messaging: plugin -> client " + toClient + ", client -> plugin " + received.get() + " bytes");
			if (toClient && received.get() == 3) {
				done.complete("PASSED: " + passed + ", plugin messages both ways");
			} else {
				done.complete("FAILED: plugin messaging (plugin -> client " + toClient + ", client -> plugin " + received.get() + " bytes)");
			}
		}));
	}

	/**
	 * Casts a spell with a bot kept online ({@code stay}) at a fresh husk in front of it, like a client (cast request,
	 * then cast input), and reports the husk's health: lets the console check spells granted by items or blocked
	 * by the class/tome rules. Spells with a rune cost need the runes in the inventory.
	 */
	public static boolean cast(String name, String spellId) {
		SelfTestBot bot = ONLINE.get(name);
		Identifier spell = Identifier.tryParse(spellId);
		if (bot == null || spell == null) return false;
		bot.server.execute(() -> {
			var player = bot.server.getPlayerList().getPlayerByName(name);
			if (player == null) return;
			var created = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:husk"))
					.create(player.level(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			if (!(created instanceof net.minecraft.world.entity.Mob target)) return;
			var look = player.getLookAngle();
			target.setPos(player.getX() + look.x * 4, player.getY(), player.getZ() + look.z * 4);
			target.setNoAi(true);
			player.level().addFreshEntity(target);
			float before = target.getHealth();
			String held = player.getMainHandItem().isEmpty() ? "empty hand" : BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem())
					+ " \"" + player.getMainHandItem().getHoverName().getString() + "\"";
			var snapshot = new net.spell_engine.internals.casting.SpellCast.TargetSnapshot(List.of(target.getId()), null);
			java.util.concurrent.CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS).execute(() -> {
				bot.send(new net.spell_engine.network.Packets.CastRequest(spell, snapshot));
				java.util.concurrent.CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS).execute(() -> {
					bot.send(new net.spell_engine.network.Packets.CastInput(spell, snapshot));
					java.util.concurrent.CompletableFuture.delayedExecutor(2500, TimeUnit.MILLISECONDS).execute(() -> bot.server.execute(() -> {
						float after = target.isAlive() ? target.getHealth() : 0;
						target.discard();
						bot.report.accept("cast " + spell + " with " + held + ": husk " + before + " -> " + after
								+ (after < before ? " (HIT)" : " (NO DAMAGE)"));
					}));
				});
			});
		});
		return true;
	}

	/** Right-clicks in the air with the main-hand item of a bot kept online (e.g. equips a trinket such as a spell book). */
	public static boolean use(String name) {
		SelfTestBot bot = ONLINE.get(name);
		if (bot == null) return false;
		bot.connection.send(new net.minecraft.network.protocol.game.ServerboundUseItemPacket(net.minecraft.world.InteractionHand.MAIN_HAND, 1, 0f, 0f));
		return true;
	}

	/** Disconnects a bot kept online with {@code stay}. */
	public static boolean leave(String name) {
		SelfTestBot bot = ONLINE.remove(name);
		if (bot == null) return false;
		bot.connection.disconnect(net.minecraft.network.chat.Component.literal("left"));
		return true;
	}

	/**
	 * Attacks a fresh zombie (no AI) in front of a bot kept online, with the item in its main hand (or the first
	 * item of its inventory, moved to the main hand), through a Better Combat attack request like a client
	 * (or a plain vanilla attack packet).
	 * Reports the zombie's health before and after: plugins that block the attack (e.g. MMOItems class
	 * requirements) leave it unchanged. Chat and action bar messages the bot receives are reported too.
	 */
	public static boolean attack(String name, boolean vanilla) {
		SelfTestBot bot = ONLINE.get(name);
		if (bot == null) return false;
		bot.server.execute(() -> {
			var player = bot.server.getPlayerList().getPlayerByName(name);
			if (player == null) return;
			var inventory = player.getInventory();
			int slot = inventory.getSelectedSlot();
			if (inventory.getItem(slot).isEmpty()) {
				for (int i = 0; i < 36; i++) {
					if (!inventory.getItem(i).isEmpty()) {
						inventory.setItem(slot, inventory.getItem(i).copy());
						inventory.setItem(i, ItemStack.EMPTY);
						break;
					}
				}
			}
			player.containerMenu.broadcastChanges();
			ItemStack held = inventory.getItem(slot);
			String what = held.isEmpty() ? "empty hand" : BuiltInRegistries.ITEM.getKey(held.getItem()) + " \"" + held.getHoverName().getString() + "\"";
			var created = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:husk")) // husk: does not burn in daylight (fire damage would fake a hit)
					.create(player.level(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			if (!(created instanceof net.minecraft.world.entity.Mob z)) return;
			var look = player.getLookAngle();
			z.setPos(player.getX() + look.x * 2, player.getY(), player.getZ() + look.z * 2);
			z.setNoAi(true);
			player.level().addFreshEntity(z);
			float before = z.getHealth();
			int id = z.getId();
			java.util.concurrent.CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS).execute(() -> {
				if (vanilla) bot.connection.send(new net.minecraft.network.protocol.game.ServerboundAttackPacket(id));
				else bot.send(new net.bettercombat.network.Packets.C2S_AttackRequest(0, false, slot, id, new int[] {id}));
				java.util.concurrent.CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS).execute(() -> bot.server.execute(() -> {
					float after = z.isAlive() ? z.getHealth() : 0;
					z.discard();
					bot.report.accept((vanilla ? "vanilla" : "Better Combat") + " attack with " + what + ": zombie " + before + " -> " + after
							+ (after < before ? " (HIT)" : " (NO DAMAGE)"));
				}));
			});
		});
		return true;
	}

	/**
	 * Login queries: answer Velocity's modern forwarding ("velocity:player_info") like the proxy would, signed with
	 * the secret of Paper's config, so the self-test also covers servers behind Velocity. Others: no answer.
	 */
	private void onLoginQuery(net.minecraft.network.protocol.login.ClientboundCustomQueryPacket query) {
		net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload answer = null;
		if (query.payload().id().toString().equals("velocity:player_info")) {
			try {
				var data = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
				data.writeVarInt(1); // MODERN_DEFAULT
				data.writeUtf("127.0.0.1");
				data.writeUUID(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)));
				data.writeUtf(name);
				data.writeVarInt(0); // no profile properties
				byte[] bytes = new byte[data.readableBytes()];
				data.readBytes(bytes);
				String secret = io.papermc.paper.configuration.GlobalConfiguration.get().proxies.velocity.secret;
				javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
				mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
				var signed = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
				signed.writeBytes(mac.doFinal(bytes));
				signed.writeBytes(bytes);
				answer = new net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket.QueryAnswerPayload(signed);
				events.add("velocity forwarding answered");
			} catch (java.security.GeneralSecurityException e) {
				done.complete("FAILED: velocity forwarding: " + e);
				return;
			}
		}
		connection.send(new net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket(query.transactionId(), answer));
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
