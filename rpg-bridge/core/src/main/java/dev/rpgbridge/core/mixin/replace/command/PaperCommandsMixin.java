/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 * Replaces fabric-command-api-v2 CommandsMixin (Apache-2.0, FabricMC) for Paper.
 */
package dev.rpgbridge.core.mixin.replace.command;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper builds the vanilla command tree in {@code Commands(CommandSelection, CommandBuildContext, boolean)}. */
@Mixin(Commands.class)
public abstract class PaperCommandsMixin {
	@Shadow
	@Final
	private CommandDispatcher<CommandSourceStack> dispatcher;

	@Inject(method = "<init>(Lnet/minecraft/commands/Commands$CommandSelection;Lnet/minecraft/commands/CommandBuildContext;Z)V",
			at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/CommandDispatcher;setConsumer(Lcom/mojang/brigadier/ResultConsumer;)V"))
	private void rpgbridge$addFabricCommands(Commands.CommandSelection selection, CommandBuildContext buildContext, boolean modern, CallbackInfo ci) {
		CommandRegistrationCallback.EVENT.invoker().register(this.dispatcher, buildContext, selection);
	}
}
