package dev.rpgbridge;

import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Adds the modded entries of the client modpack to Purpur's built-in registries, with the
 * same raw ids the clients receive through {@code fabric:registry/sync}. The server then
 * encodes modded ids natively in every packet (inventories, entities, ...).
 * Server-side the entries are plain placeholders: behaviour is implemented by the plugin.
 */
final class RegistryInjector {
	private final Logger log;

	RegistryInjector(Logger log) {
		this.log = log;
	}

	/** Checks that the vanilla prefix of every dumped registry matches Purpur exactly. */
	boolean verifyVanillaPrefix(Dump dump) {
		boolean ok = true;
		for (var e : dump.registryIds.entrySet()) {
			Registry<?> registry = BuiltInRegistries.REGISTRY.getValue(Identifier.parse(e.getKey()));
			if (registry == null) {
				log.severe("Registry " + e.getKey() + " from the dump does not exist on this server");
				ok = false;
				continue;
			}
			List<String> ids = e.getValue();
			int vanilla = registry.size();
			for (int raw = 0; raw < Math.min(vanilla, ids.size()); raw++) {
				Object value = registry.byId(raw);
				String serverId = String.valueOf(((Registry) registry).getKey(value));
				if (!serverId.equals(ids.get(raw))) {
					log.severe("Raw id mismatch in " + e.getKey() + " at " + raw + ": server=" + serverId + " dump=" + ids.get(raw));
					ok = false;
					break;
				}
			}
		}
		return ok;
	}

	/** Registers placeholder items for every modded item id, in dump order. */
	int injectItems(Dump dump) throws ReflectiveOperationException {
		List<String> ids = dump.registryIds.get("minecraft:item");
		if (ids == null) return 0;
		MappedRegistry<Item> registry = (MappedRegistry<Item>) BuiltInRegistries.ITEM;
		int start = registry.size();
		if (start > ids.size()) throw new IllegalStateException("Server has more items than the dump");
		unfreeze(registry);
		try {
			for (int raw = start; raw < ids.size(); raw++) {
				Identifier id = Identifier.parse(ids.get(raw));
				ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
				Item item = new Item(new Item.Properties().setId(key));
				Holder.Reference<Item> holder = Registry.registerForHolder(registry, key, item);
				bindEmptyTags(holder);
				int assigned = registry.getId(item);
				if (assigned != raw) throw new IllegalStateException(id + " got raw id " + assigned + ", expected " + raw);
			}
		} finally {
			refreeze(registry);
		}
		return ids.size() - start;
	}

	private static void unfreeze(MappedRegistry<?> registry) throws ReflectiveOperationException {
		Field frozen = MappedRegistry.class.getDeclaredField("frozen");
		frozen.setAccessible(true);
		frozen.setBoolean(registry, false);
		Field intrusive = MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders");
		intrusive.setAccessible(true);
		if (intrusive.get(registry) == null) intrusive.set(registry, new IdentityHashMap<>());
	}

	/** Like {@link MappedRegistry#freeze()}, which refuses registries whose tags are already loaded. */
	private static void refreeze(MappedRegistry<?> registry) throws ReflectiveOperationException {
		Field intrusive = MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders");
		intrusive.setAccessible(true);
		var pending = (java.util.Map<?, ?>) intrusive.get(registry);
		if (pending != null && !pending.isEmpty()) throw new IllegalStateException("Unregistered intrusive holders: " + pending.keySet());
		intrusive.set(registry, null);
		Field frozen = MappedRegistry.class.getDeclaredField("frozen");
		frozen.setAccessible(true);
		frozen.setBoolean(registry, true);
	}

	private static void bindEmptyTags(Holder.Reference<?> holder) throws ReflectiveOperationException {
		Method bind = Holder.Reference.class.getDeclaredMethod("bindTags", java.util.Collection.class);
		bind.setAccessible(true);
		bind.invoke(holder, Set.of());
	}
}
