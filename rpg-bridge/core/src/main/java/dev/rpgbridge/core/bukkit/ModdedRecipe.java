/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core.bukkit;

import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

/**
 * Ricetta Bukkit generica per i tipi di ricetta delle mod (es. Runes {@code RuneCraftingRecipe}). CraftBukkit aggiunge
 * a {@code net.minecraft.world.item.crafting.Recipe} il metodo astratto {@code toBukkitRecipe(NamespacedKey)}, che le
 * mod non implementano: {@code Bukkit.recipeIterator()} lanciava AbstractMethodError (CastigoRicette non partiva).
 * Solo la chiave è significativa; il risultato è vuoto (i plugin non possono ricreare questi tipi di ricetta).
 */
public record ModdedRecipe(NamespacedKey key) implements Recipe, Keyed {
	private static final Map<Class<?>, Boolean> MISSING = new ConcurrentHashMap<>();

	/** True se la classe della ricetta non implementa toBukkitRecipe di CraftBukkit. */
	public static boolean lacksBukkitRecipe(Object recipe) {
		return MISSING.computeIfAbsent(recipe.getClass(), c -> {
			try {
				return Modifier.isAbstract(c.getMethod("toBukkitRecipe", NamespacedKey.class).getModifiers());
			} catch (NoSuchMethodException e) {
				return true;
			}
		});
	}

	@Override
	public ItemStack getResult() {
		return ItemStack.of(Material.AIR);
	}

	@Override
	public NamespacedKey getKey() {
		return key;
	}
}
