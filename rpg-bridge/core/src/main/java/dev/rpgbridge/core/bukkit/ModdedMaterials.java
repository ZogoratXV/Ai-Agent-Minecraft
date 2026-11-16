/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core.bukkit;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import dev.rpgbridge.core.RpgBridgeMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.BlockType;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.inventory.ItemType;

/**
 * Bukkit {@link Material} per gli oggetti e i blocchi delle mod.
 * <p>
 * CraftBukkit associa a ogni oggetto il Material con lo stesso nome del <em>percorso</em> dell'id (ignorando il
 * namespace): per {@code wizards:wand_arcane} cerca {@code WAND_ARCANE}, non lo trova e {@code ItemStack.getType()}
 * diventa null, mandando in errore molti plugin (inventari, zaini, statistiche). Peggio, una mod con un oggetto il cui
 * percorso coincide con uno vanilla verrebbe scambiata per l'oggetto vanilla.
 * <p>
 * Qui, appena chiusi i registri e prima che CraftBukkit e i plugin si inizializzino, si aggiungono costanti di Material
 * (nome {@code NAMESPACE_PERCORSO}, chiave {@code namespace:percorso}, legacy=false) che delegano a ItemType/BlockType
 * dei registri di Bukkit come tutti i Material moderni, e si correggono le tabelle di CraftMagicNumbers nei due sensi,
 * così anche {@code new ItemStack(Material)} di un plugin crea l'oggetto vero della mod.
 */
public final class ModdedMaterials {
	private static sun.misc.Unsafe unsafe;

	private ModdedMaterials() {
	}

	public static synchronized void register() {
		try {
			unsafe = unsafe();
			Map<Identifier, Item> items = new LinkedHashMap<>();
			Map<Identifier, Block> blocks = new LinkedHashMap<>();
			for (Item item : BuiltInRegistries.ITEM) {
				Identifier id = BuiltInRegistries.ITEM.getKey(item);
				if (!"minecraft".equals(id.getNamespace())) items.put(id, item);
			}
			for (Block block : BuiltInRegistries.BLOCK) {
				Identifier id = BuiltInRegistries.BLOCK.getKey(block);
				if (!"minecraft".equals(id.getNamespace())) blocks.put(id, block);
			}
			List<Identifier> keys = new ArrayList<>(items.keySet());
			for (Identifier id : blocks.keySet()) if (!items.containsKey(id)) keys.add(id);
			if (keys.isEmpty()) return;

			Material[] old = Material.values();
			Material template = Material.STICK;
			// New constants go between the modern Materials and the LEGACY_ ones: Paper's "modern values" seen by
			// plugins (Commodore rewrites their Material.values()) are [0, LEGACY_AIR.ordinal()), and plugins such as
			// FastAsyncWorldEdit size arrays by that and index them by ordinal. The LEGACY_ constants move up.
			int legacyStart = Material.LEGACY_AIR.ordinal();
			int added = keys.size();
			Material[] all = new Material[old.length + added];
			System.arraycopy(old, 0, all, 0, legacyStart);
			Map<Identifier, Material> created = new LinkedHashMap<>();
			int ordinal = legacyStart;
			for (Identifier id : keys) {
				Material material = newMaterial(id, ordinal, template);
				all[ordinal++] = material;
				created.put(id, material);
			}
			for (int i = legacyStart; i < old.length; i++) {
				putInt(old[i], Enum.class, "ordinal", i + added);
				all[i + added] = old[i];
			}

			setStatic(Material.class, "$VALUES", all);
			@SuppressWarnings("unchecked")
			Map<String, Material> byName = (Map<String, Material>) getStatic(Material.class, "BY_NAME");
			for (Material m : created.values()) byName.put(m.name(), m);
			resetEnumCaches(Material.class);

			// CraftMagicNumbers: its static init maps items and blocks by path; run it now and fix the modded entries.
			Class.forName(CraftMagicNumbers.class.getName(), true, CraftMagicNumbers.class.getClassLoader());
			@SuppressWarnings("unchecked")
			Map<Item, Material> itemMaterial = (Map<Item, Material>) getStatic(CraftMagicNumbers.class, "ITEM_MATERIAL");
			@SuppressWarnings("unchecked")
			Map<Block, Material> blockMaterial = (Map<Block, Material>) getStatic(CraftMagicNumbers.class, "BLOCK_MATERIAL");
			@SuppressWarnings("unchecked")
			Map<Material, Item> materialItem = (Map<Material, Item>) getStatic(CraftMagicNumbers.class, "MATERIAL_ITEM");
			@SuppressWarnings("unchecked")
			Map<Material, Block> materialBlock = (Map<Material, Block>) getStatic(CraftMagicNumbers.class, "MATERIAL_BLOCK");
			// EnumMaps created before the new constants existed are sized for the old values: rebuild them.
			materialItem = rebuild(CraftMagicNumbers.class, "MATERIAL_ITEM", materialItem);
			materialBlock = rebuild(CraftMagicNumbers.class, "MATERIAL_BLOCK", materialBlock);
			for (var e : created.entrySet()) {
				Item item = items.get(e.getKey());
				Block block = blocks.get(e.getKey());
				if (item != null) {
					itemMaterial.put(item, e.getValue());
					materialItem.put(e.getValue(), item);
				}
				if (block != null) {
					blockMaterial.put(block, e.getValue());
					materialBlock.put(e.getValue(), block);
				}
			}
			int fixedCollisions = 0;
			for (var e : items.entrySet()) if (itemMaterial.get(e.getValue()) == created.get(e.getKey())) fixedCollisions++;
			boolean consistent = EnumSet.allOf(Material.class).size() == all.length;
			RpgBridgeMod.LOGGER.info("[Material] {} Material aggiunti per oggetti e blocchi delle mod ({} oggetti, {} blocchi){}",
					created.size(), items.size(), blocks.size(), consistent ? "" : " - ATTENZIONE: cache enum non aggiornata");
		} catch (Throwable t) {
			RpgBridgeMod.LOGGER.error("[Material] Impossibile creare i Material delle mod: getType() resterà null per i loro oggetti", t);
		}
	}

	private static Material newMaterial(Identifier id, int ordinal, Material template) throws ReflectiveOperationException {
		Material m = (Material) unsafe.allocateInstance(Material.class);
		String name = (id.getNamespace() + "_" + id.getPath()).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
		put(m, Enum.class, "name", name);
		putInt(m, Enum.class, "ordinal", ordinal);
		putInt(m, Material.class, "id", -1);
		put(m, Material.class, "ctor", get(template, Material.class, "ctor"));
		put(m, Material.class, "data", get(template, Material.class, "data"));
		putBoolean(m, Material.class, "legacy", false);
		NamespacedKey key = new NamespacedKey(id.getNamespace(), id.getPath());
		put(m, Material.class, "key", key);
		put(m, Material.class, "itemType", memoize(() -> Registry.ITEM.get(key)));
		put(m, Material.class, "blockType", memoize(() -> Registry.BLOCK.get(key)));
		return m;
	}

	private static <T> Supplier<T> memoize(Supplier<T> source) {
		return new Supplier<>() {
			private volatile boolean done;
			private T value;

			@Override
			public T get() {
				if (!done) {
					value = source.get();
					done = true;
				}
				return value;
			}
		};
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static <V> Map<Material, V> rebuild(Class<?> owner, String field, Map<Material, V> current) throws ReflectiveOperationException {
		if (!(current instanceof EnumMap)) return current;
		EnumMap<Material, V> fresh = new EnumMap<>(Material.class);
		fresh.putAll(current);
		setStatic(owner, field, fresh);
		return fresh;
	}

	private static void resetEnumCaches(Class<?> enumClass) {
		for (String name : new String[] {"enumConstants", "enumConstantDirectory"}) {
			try {
				Field f = Class.class.getDeclaredField(name);
				unsafe.putObject(enumClass, unsafe.objectFieldOffset(f), null);
			} catch (NoSuchFieldException ignored) {
				// JDK without this cache field
			}
		}
	}

	// ------------------------------------------------------------------ Unsafe field access

	private static sun.misc.Unsafe unsafe() throws ReflectiveOperationException {
		Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		f.setAccessible(true);
		return (sun.misc.Unsafe) f.get(null);
	}

	private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
		return owner.getDeclaredField(name);
	}

	private static void put(Object target, Class<?> owner, String name, Object value) throws NoSuchFieldException {
		unsafe.putObject(target, unsafe.objectFieldOffset(field(owner, name)), value);
	}

	private static Object get(Object target, Class<?> owner, String name) throws NoSuchFieldException {
		return unsafe.getObject(target, unsafe.objectFieldOffset(field(owner, name)));
	}

	private static void putInt(Object target, Class<?> owner, String name, int value) throws NoSuchFieldException {
		unsafe.putInt(target, unsafe.objectFieldOffset(field(owner, name)), value);
	}

	private static void putBoolean(Object target, Class<?> owner, String name, boolean value) throws NoSuchFieldException {
		unsafe.putBoolean(target, unsafe.objectFieldOffset(field(owner, name)), value);
	}

	@SuppressWarnings("deprecation")
	private static Object getStatic(Class<?> owner, String name) throws NoSuchFieldException {
		Field f = field(owner, name);
		if (!Modifier.isStatic(f.getModifiers())) throw new NoSuchFieldException(name + " is not static");
		return unsafe.getObject(unsafe.staticFieldBase(f), unsafe.staticFieldOffset(f));
	}

	@SuppressWarnings("deprecation")
	private static void setStatic(Class<?> owner, String name, Object value) throws NoSuchFieldException {
		Field f = field(owner, name);
		unsafe.putObject(unsafe.staticFieldBase(f), unsafe.staticFieldOffset(f), value);
	}

	/** Solo per i tipi usati nelle firme: evita che il compilatore segnali import inutilizzati. */
	@SuppressWarnings("unused")
	private static void types(ItemType i, BlockType b) {
	}
}
