package dev.rpgbridge.dumper;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager;
import net.fabricmc.fabric.impl.registry.sync.packet.RegistrySyncPayload;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.flag.FeatureFlags;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

/**
 * Runs inside a headless Fabric server that has the same mods as the clients.
 * On server start it writes, to {@code rpgbridge-dump/}, the exact bytes a Fabric
 * server would send during the configuration phase, then stops the server.
 * The RPG Bridge plugin replays these bytes to modded clients on Purpur.
 */
public final class Dumper implements ModInitializer {
	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			try {
				dump(server, server.getServerDirectory().resolve("rpgbridge-dump"));
				System.out.println("[rpgbridge-dumper] dump complete");
			} catch (Throwable t) {
				System.err.println("[rpgbridge-dumper] dump FAILED");
				t.printStackTrace();
			}
			server.halt(false);
		});
	}

	private static void dump(MinecraftServer server, Path out) throws IOException {
		Files.createDirectories(out.resolve("registry_data"));
		JsonObject manifest = new JsonObject();

		// 1. fabric:registry/sync payload + human-readable id map
		Map<Identifier, Object2IntMap<Identifier>> map = RegistrySyncManager.createAndPopulateRegistryMap();
		JsonObject ids = new JsonObject();
		if (map != null) {
			write(out.resolve("fabric_registry_sync.bin"), RegistrySyncPayload.CODEC, new RegistrySyncPayload(map));
			map.forEach((registry, entries) -> {
				JsonArray arr = new JsonArray();
				entries.object2IntEntrySet().stream()
						.sorted(Comparator.comparingInt(Object2IntMap.Entry::getIntValue))
						.forEach(e -> {
							JsonArray pair = new JsonArray();
							pair.add(e.getIntValue());
							pair.add(e.getKey().toString());
							arr.add(pair);
						});
				ids.add(registry.toString(), arr);
			});
		}
		Files.writeString(out.resolve("registry_ids.json"), new GsonBuilder().create().toJson(ids));

		// 2. Spell Engine configuration payloads
		write(out.resolve("spell_engine_config_sync.bin"), net.spell_engine.network.Packets.ConfigSync.CODEC,
				new net.spell_engine.network.Packets.ConfigSync(net.spell_engine.SpellEngineMod.config));
		write(out.resolve("spell_engine_spell_registry_sync.bin"), net.spell_engine.network.Packets.SpellRegistrySync.CODEC,
				new net.spell_engine.network.Packets.SpellRegistrySync(net.spell_engine.internals.container.SpellAssignments.encoded));

		// 3. Better Combat configuration payloads
		var bcConfig = net.bettercombat.network.Packets.ConfigSync.serialize(net.bettercombat.BetterCombatMod.getConfig());
		write(out.resolve("bettercombat_config_sync.bin"), net.bettercombat.network.Packets.ConfigSync.CODEC,
				new net.bettercombat.network.Packets.ConfigSync(bcConfig));
		var weapons = net.bettercombat.logic.WeaponRegistry.getEncodedRegistry();
		write(out.resolve("bettercombat_weapon_registry.bin"), net.bettercombat.network.Packets.WeaponRegistrySync.CODEC,
				new net.bettercombat.network.Packets.WeaponRegistrySync(weapons.compressed(), weapons.chunks()));

		// 4. Synchronized data-driven registries (no known packs: full data inline)
		var ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
		JsonArray registries = new JsonArray();
		RegistrySynchronization.packRegistries(ops, server.registryAccess(), Set.of(), (key, entries) -> {
			String name = key.identifier().toString();
			registries.add(name);
			try {
				write(out.resolve("registry_data").resolve(name.replace(':', '.').replace('/', '.') + ".bin"),
						ClientboundRegistryDataPacket.STREAM_CODEC, new ClientboundRegistryDataPacket(key, entries));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		manifest.add("registry_data", registries);

		// 5. Tags (raw ids consistent with the registry sync above)
		write(out.resolve("update_tags.bin"), ClientboundUpdateTagsPacket.STREAM_CODEC,
				new ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(server.registries())));

		manifest.addProperty("minecraft", net.minecraft.SharedConstants.getCurrentVersion().name());
		manifest.addProperty("feature_flags", FeatureFlags.REGISTRY.toNames(server.getWorldData().enabledFeatures()).toString());
		Files.writeString(out.resolve("manifest.json"), new GsonBuilder().setPrettyPrinting().create().toJson(manifest));
	}

	private static <T> void write(Path file, StreamCodec<? super FriendlyByteBuf, T> codec, T value) throws IOException {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		codec.encode(buf, value);
		byte[] bytes = new byte[buf.readableBytes()];
		buf.readBytes(bytes);
		Files.write(file, bytes);
	}
}
