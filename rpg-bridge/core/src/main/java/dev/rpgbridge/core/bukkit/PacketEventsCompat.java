/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core.bukkit;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipFile;

import dev.rpgbridge.core.RpgBridgeMod;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Item;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * PacketEvents (plugin a sé o incluso in altri plugin, es. GrimAC) ha tabelle fisse dei contenuti vanilla: per le
 * entità e gli oggetti delle mod restituisce null e i plugin che leggono o riscrivono i pacchetti vanno in errore
 * (kick "this.entityType is null" al lancio di una spell, "Can't resolve #id in minecraft:item" in Grim).
 * A server avviato, per ogni copia di PacketEvents trovata nei jar dei plugin, si registrano nei suoi registri le
 * entità e gli oggetti delle mod con i loro id reali, usando le classi di quella copia.
 */
public final class PacketEventsCompat {
	private static final String ENTITY_TYPES = "protocol/entity/type/EntityTypes.class";

	private PacketEventsCompat() {
	}

	public static void register(MinecraftServer server) {
		Map<String, ClassLoader> copies = new LinkedHashMap<>();
		for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
			for (String prefix : packetEventsPrefixes(plugin)) copies.putIfAbsent(prefix, plugin.getClass().getClassLoader());
		}
		if (copies.isEmpty()) return;
		Map<Identifier, EntityType<?>> entities = new LinkedHashMap<>();
		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
			if (!"minecraft".equals(id.getNamespace())) entities.put(id, type);
		}
		Map<Identifier, Item> items = new LinkedHashMap<>();
		for (Item item : BuiltInRegistries.ITEM) {
			Identifier id = BuiltInRegistries.ITEM.getKey(item);
			if (!"minecraft".equals(id.getNamespace())) items.put(id, item);
		}
		Map<Identifier, String> parents = entityParents(server, entities);
		for (var copy : copies.entrySet()) {
			try {
				int e = new Copy(copy.getKey(), copy.getValue()).registerEntities(entities, parents);
				int i = new Copy(copy.getKey(), copy.getValue()).registerItems(items);
				RpgBridgeMod.LOGGER.info("[PacketEvents] {}: registrate {} entità e {} oggetti delle mod{}", copy.getKey(), e, i,
						new Copy(copy.getKey(), copy.getValue()).check(entities));
			} catch (Throwable t) {
				RpgBridgeMod.LOGGER.warn("[PacketEvents] {}: impossibile registrare i contenuti delle mod ({})", copy.getKey(), t.toString());
			}
		}
	}

	/** Package prefixes of the PacketEvents copies inside a plugin jar (e.g. com.github.retrooper.packetevents). */
	private static Set<String> packetEventsPrefixes(Plugin plugin) {
		Set<String> prefixes = new HashSet<>();
		try {
			var source = plugin.getClass().getProtectionDomain().getCodeSource();
			if (source == null) return prefixes;
			File file = new File(source.getLocation().toURI());
			if (!file.isFile()) return prefixes;
			try (ZipFile zip = new ZipFile(file)) {
				zip.stream().map(e -> e.getName()).filter(n -> n.endsWith("/" + ENTITY_TYPES))
						.forEach(n -> prefixes.add(n.substring(0, n.length() - ENTITY_TYPES.length() - 1).replace('/', '.')));
			}
		} catch (Exception ignored) {
		}
		return prefixes;
	}

	/** Closest vanilla PacketEvents parent type for each mod entity, from its real class. */
	private static Map<Identifier, String> entityParents(MinecraftServer server, Map<Identifier, EntityType<?>> entities) {
		Map<Identifier, String> parents = new LinkedHashMap<>();
		var level = server.overworld();
		for (var e : entities.entrySet()) {
			String parent = "ENTITY";
			try {
				Entity probe = e.getValue().create(level, EntitySpawnReason.COMMAND);
				if (probe instanceof AbstractArrow) parent = "ABSTRACT_ARROW";
				else if (probe instanceof Projectile) parent = "PROJECTILE_ABSTRACT";
				else if (probe instanceof Monster) parent = "ABSTRACT_MONSTER";
				else if (probe instanceof Animal) parent = "ABSTRACT_ANIMAL";
				else if (probe instanceof PathfinderMob) parent = "ABSTRACT_CREATURE";
				else if (probe instanceof Mob) parent = "ABSTRACT_INSENTIENT";
				else if (probe instanceof LivingEntity) parent = "LIVINGENTITY";
				if (probe != null) probe.discard();
			} catch (Throwable ignored) {
				// entity that cannot be created without context: generic entity
			}
			parents.put(e.getKey(), parent);
		}
		return parents;
	}

	/** One PacketEvents copy, accessed through its own (possibly relocated) classes. */
	private static final class Copy {
		private final String prefix;
		private final ClassLoader loader;

		Copy(String prefix, ClassLoader loader) {
			this.prefix = prefix;
			this.loader = loader;
		}

		private Class<?> cls(String name) throws ClassNotFoundException {
			return Class.forName(prefix + "." + name, true, loader);
		}

		int registerEntities(Map<Identifier, EntityType<?>> entities, Map<Identifier, String> parents) throws Exception {
			Class<?> entityTypes = cls("protocol.entity.type.EntityTypes");
			Class<?> entityType = cls("protocol.entity.type.EntityType");
			Object registry = entityTypes.getMethod("getRegistry").invoke(null);
			Constructor<?> ctor = cls("protocol.entity.type.StaticEntityType").getConstructor(cls("util.mappings.TypesBuilderData"), entityType);
			int n = 0;
			for (var e : entities.entrySet()) {
				int id = BuiltInRegistries.ENTITY_TYPE.getId(e.getValue());
				Object parent = entityTypes.getField(parents.getOrDefault(e.getKey(), "ENTITY")).get(null);
				if (register(registry, e.getKey().toString(), id, data -> newInstance(ctor, data, parent))) n++;
			}
			return n;
		}

		/** Looks a mod entity up by id like a packet reader would (latest client version): " (verifica ok: name)". */
		String check(Map<Identifier, EntityType<?>> entities) {
			try {
				if (entities.isEmpty()) return "";
				var first = entities.entrySet().iterator().next();
				Object registry = cls("protocol.entity.type.EntityTypes").getMethod("getRegistry").invoke(null);
				Object builder = registry.getClass().getMethod("getTypesBuilder").invoke(registry);
				Object[] versions = (Object[]) builder.getClass().getMethod("getVersions").invoke(builder);
				Object latest = versions[versions.length - 1];
				Object found = registry.getClass().getMethod("getById", latest.getClass(), int.class)
						.invoke(registry, latest, BuiltInRegistries.ENTITY_TYPE.getId(first.getValue()));
				Object name = found == null ? null : found.getClass().getMethod("getName").invoke(found);
				return found == null ? " (VERIFICA FALLITA: " + first.getKey() + " non trovato per id)" : " (verifica per id ok: " + name + ")";
			} catch (Throwable t) {
				return " (verifica non eseguita: " + t + ")";
			}
		}

		int registerItems(Map<Identifier, Item> items) throws Exception {
			Class<?> itemTypes = cls("protocol.item.type.ItemTypes");
			Object registry = itemTypes.getMethod("getRegistry").invoke(null);
			Class<?> attribute = cls("protocol.item.type.ItemTypes$ItemAttribute");
			Class<?> itemType = cls("protocol.item.type.ItemType");
			Class<?> stateType = cls("protocol.world.states.type.StateType");
			Constructor<?> ctor = cls("protocol.item.type.StaticItemType").getConstructor(cls("util.mappings.TypesBuilderData"),
					int.class, int.class, itemType, stateType, Set.class);
			int n = 0;
			for (var e : items.entrySet()) {
				Item item = e.getValue();
				int id = BuiltInRegistries.ITEM.getId(item);
				int maxAmount = item.getDefaultMaxStackSize();
				Integer maxDamage = item.components().get(DataComponents.MAX_DAMAGE);
				Set<Object> attributes = new HashSet<>();
				if (item.components().has(DataComponents.FOOD)) attributes.add(enumValue(attribute, "EDIBLE"));
				if (register(registry, e.getKey().toString(), id,
						data -> newInstance(ctor, data, maxAmount, maxDamage == null ? 0 : maxDamage, null, null, Set.copyOf(attributes)))) n++;
			}
			return n;
		}

		/** Adds an entry with the same id for every client version known to the registry. */
		private boolean register(Object registry, String name, int id, Function<Object, Object> factory) throws Exception {
			Method getByName = registry.getClass().getMethod("getByName", String.class);
			if (getByName.invoke(registry, name) != null) return false;
			Object builder = registry.getClass().getMethod("getTypesBuilder").invoke(registry);
			Object[] versions = (Object[]) builder.getClass().getMethod("getVersions").invoke(builder);
			int[] ids = new int[versions.length];
			java.util.Arrays.fill(ids, id);
			Object location = cls("resources.ResourceLocation").getConstructor(String.class).newInstance(name);
			Object allVersions = cls("util.VersionRange").getField("ALL_VERSIONS").get(null);
			Object data = cls("util.mappings.TypesBuilderData")
					.getConstructor(location.getClass(), int[].class, builder.getClass(), allVersions.getClass())
					.newInstance(location, ids, builder, allVersions);
			Object instance = factory.apply(data);
			Object typeNames = field(registry, "typeNames");
			Object typeIds = field(registry, "typeIds");
			cls("util.mappings.MappingHelper").getMethod("registerMapping", builder.getClass(), typeNames.getClass(), typeIds.getClass(),
					data.getClass(), Class.forName(prefix + ".protocol.mapper.MappedEntity", true, loader))
					.invoke(null, builder, typeNames, typeIds, data, instance);
			@SuppressWarnings("unchecked")
			Collection<Object> entries = (Collection<Object>) field(registry, "entries");
			entries.add(instance);
			return true;
		}

		private static Object field(Object target, String name) throws ReflectiveOperationException {
			Field f = target.getClass().getDeclaredField(name);
			f.setAccessible(true);
			return f.get(target);
		}

		private static Object newInstance(Constructor<?> ctor, Object... args) {
			try {
				return ctor.newInstance(args);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}

		@SuppressWarnings({"unchecked", "rawtypes"})
		private static Object enumValue(Class<?> type, String name) {
			return Enum.valueOf((Class) type, name);
		}
	}

	@SuppressWarnings("unused")
	private static Object unusedArray() {
		return Array.newInstance(Object.class, 0);
	}

	@SuppressWarnings("unused")
	private static List<Object> unusedList() {
		return new ArrayList<>();
	}
}
