package dev.rpgbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration-phase data produced by the dumper mod on a Fabric server running the
 * admin's modpack (see dumper/). Nothing here is shipped with the plugin.
 */
public final class Dump {
	/** Payload body of {@code fabric:registry/sync}. */
	public final byte[] fabricRegistrySync;
	public final byte[] spellEngineConfigSync;
	public final byte[] spellEngineSpellRegistrySync;
	public final byte[] betterCombatConfigSync;
	public final byte[] betterCombatWeaponRegistry;
	/** Registry id → ordered entry ids (index = raw id), for the static registries Fabric syncs. */
	public final Map<String, List<String>> registryIds;
	private final Path registryDataDir;
	private final Path tagsFile;

	private Dump(Path dir) throws IOException {
		this.fabricRegistrySync = Files.readAllBytes(dir.resolve("fabric_registry_sync.bin"));
		this.spellEngineConfigSync = Files.readAllBytes(dir.resolve("spell_engine_config_sync.bin"));
		this.spellEngineSpellRegistrySync = Files.readAllBytes(dir.resolve("spell_engine_spell_registry_sync.bin"));
		this.betterCombatConfigSync = Files.readAllBytes(dir.resolve("bettercombat_config_sync.bin"));
		this.betterCombatWeaponRegistry = Files.readAllBytes(dir.resolve("bettercombat_weapon_registry.bin"));
		this.registryDataDir = dir.resolve("registry_data");
		this.tagsFile = dir.resolve("update_tags.bin");

		Map<String, List<String>> ids = new LinkedHashMap<>();
		JsonObject root = JsonParser.parseString(Files.readString(dir.resolve("registry_ids.json"))).getAsJsonObject();
		for (Map.Entry<String, JsonElement> e : root.entrySet()) {
			JsonArray arr = e.getValue().getAsJsonArray();
			List<String> list = new ArrayList<>(arr.size());
			for (int i = 0; i < arr.size(); i++) {
				JsonArray pair = arr.get(i).getAsJsonArray();
				if (pair.get(0).getAsInt() != i) throw new IOException("registry_ids.json: non-contiguous raw ids in " + e.getKey());
				list.add(pair.get(1).getAsString());
			}
			ids.put(e.getKey(), list);
		}
		this.registryIds = ids;
	}

	public static Dump load(Path dir) throws IOException {
		if (!Files.isDirectory(dir)) {
			throw new IOException("Dump directory not found: " + dir + " (run the dumper on a Fabric server with the modpack, see README)");
		}
		return new Dump(dir);
	}

	/** All dumped synchronized data-driven registries, decoded as vanilla packets. */
	public List<ClientboundRegistryDataPacket> registryData() throws IOException {
		List<ClientboundRegistryDataPacket> out = new ArrayList<>();
		try (var files = Files.list(registryDataDir)) {
			for (Path f : files.sorted().toList()) {
				out.add(ClientboundRegistryDataPacket.STREAM_CODEC.decode(buf(Files.readAllBytes(f))));
			}
		}
		return out;
	}

	public ClientboundUpdateTagsPacket tags() throws IOException {
		return ClientboundUpdateTagsPacket.STREAM_CODEC.decode(buf(Files.readAllBytes(tagsFile)));
	}

	private static FriendlyByteBuf buf(byte[] bytes) {
		return new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
	}
}
