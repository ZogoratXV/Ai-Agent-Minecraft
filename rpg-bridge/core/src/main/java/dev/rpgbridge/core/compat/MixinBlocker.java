/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core.compat;

import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.Config;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Removes mixins of Fabric API / mods that cannot work on Purpur, before their targets load.
 *
 * <p>The list lives in {@code rpgbridge-mixin-compat.properties}: {@code <mixin class>=<reason>}.
 * Every removed mixin is either irrelevant on a dedicated server or re-implemented by an
 * RPG Bridge mixin against Paper's code (see {@code dev.rpgbridge.core.mixin.replace}).
 * Must run in {@code preLaunch}, when the mixin configs are known but no game class is loaded.
 */
public final class MixinBlocker {
	private MixinBlocker() {
	}

	public static Map<String, String> blocked() {
		Map<String, String> map = new LinkedHashMap<>();
		try (InputStream in = MixinBlocker.class.getResourceAsStream("/rpgbridge-mixin-compat.properties")) {
			if (in == null) return map;
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				line = line.strip();
				if (line.isEmpty() || line.startsWith("#")) continue;
				int eq = line.indexOf('=');
				if (eq > 0) map.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return map;
	}

	public static void apply() {
		Map<String, String> blocked = blocked();
		int removed = 0;
		java.util.List<Object> all = new java.util.ArrayList<>();
		for (Config config : Mixins.getConfigs()) all.add(config.getConfig());
		try {
			// Configs already consumed by the transformer live in MixinProcessor.configs/pendingConfigs.
			Object transformer = org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
			Object processor = transformer == null ? null : field(transformer, "processor");
			if (processor != null) {
				for (String f : new String[] {"configs", "pendingConfigs"}) {
					Object list = field(processor, f);
					if (list instanceof Collection<?> c) for (Object o : c) if (!all.contains(o)) all.add(o);
				}
			}
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Unsupported Mixin version", e);
		}
		for (Object mc : all) {
			try {
				removed += removeNames(mc, "mixinClasses", blocked) + removeNames(mc, "mixinClassesServer", blocked);
				removed += removeInfos(field(mc, "pendingMixins"), blocked) + removeInfos(field(mc, "mixins"), blocked);
				Map<String, List<Object>> mapping = (Map<String, List<Object>>) field(mc, "mixinMapping");
				if (mapping != null) {
					for (List<Object> list : mapping.values()) removeInfos(list, blocked);
				}
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("Unsupported Mixin version", e);
			}
		}
		System.out.println("[RPGBridge] Disabled " + blocked.size() + " incompatible mixins (" + removed + " entries)");

		java.util.Set<org.spongepowered.asm.mixin.extensibility.IMixinInfo> infos = new java.util.LinkedHashSet<>();
		for (Object mc : all) {
			try {
				for (String f : new String[] {"pendingMixins", "mixins"}) {
					if (field(mc, f) instanceof Collection<?> c) for (Object o : c) infos.add((org.spongepowered.asm.mixin.extensibility.IMixinInfo) o);
				}
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("Unsupported Mixin version", e);
			}
		}
		MixinPatcher.apply(new java.util.ArrayList<>(infos));
	}

	private static int removeNames(Object mc, String fieldName, Map<String, String> blocked) throws ReflectiveOperationException {
		List<String> names = (List<String>) field(mc, fieldName);
		if (names == null) return 0;
		String pkg = (String) field(mc, "mixinPackage");
		int before = names.size();
		try {
			names.removeIf(n -> blocked.containsKey(pkg + n) || blocked.containsKey(pkg + "." + n));
		} catch (UnsupportedOperationException e) {
			return 0;
		}
		return before - names.size();
	}

	private static int removeInfos(Object list, Map<String, String> blocked) {
		if (!(list instanceof Collection<?> infos)) return 0;
		int before = infos.size();
		infos.removeIf(info -> blocked.containsKey(((org.spongepowered.asm.mixin.extensibility.IMixinInfo) info).getClassName()));
		return before - infos.size();
	}

	private static Object field(Object target, String name) throws ReflectiveOperationException {
		for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
			try {
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				return f.get(target);
			} catch (NoSuchFieldException ignored) {
			}
		}
		return null;
	}
}
