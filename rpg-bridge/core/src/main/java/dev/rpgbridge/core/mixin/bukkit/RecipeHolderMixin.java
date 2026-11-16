/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core.mixin.bukkit;

import dev.rpgbridge.core.bukkit.ModdedRecipe;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ricette delle mod senza conversione Bukkit: ricetta generica invece di AbstractMethodError. */
@Mixin(RecipeHolder.class)
public abstract class RecipeHolderMixin {
	@Shadow
	@Final
	private ResourceKey<Recipe<?>> id;

	@Shadow
	@Final
	private Recipe<?> value;

	@Inject(method = "toBukkitRecipe", at = @At("HEAD"), cancellable = true)
	private void rpgbridge$moddedRecipe(CallbackInfoReturnable<org.bukkit.inventory.Recipe> cir) {
		if (ModdedRecipe.lacksBukkitRecipe(value)) cir.setReturnValue(new ModdedRecipe(CraftNamespacedKey.fromMinecraft(id.identifier())));
	}
}
