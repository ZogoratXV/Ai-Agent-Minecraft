/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.rpgbridge.core.selftest.SelfTestBot;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RpgBridgeMod implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("RPGBridge");

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(
				Commands.literal("rpgbridge")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("selftest")
								.executes(c -> selfTest(c.getSource(), "SelfTestBot"))
								.then(Commands.argument("name", StringArgumentType.word())
										.executes(c -> selfTest(c.getSource(), StringArgumentType.getString(c, "name")))))));
	}

	private static int selfTest(CommandSourceStack source, String name) {
		source.sendSystemMessage(Component.literal("RPG Bridge self-test: connecting as " + name + "..."));
		SelfTestBot.run(source.getServer(), name, line -> LOGGER.info("[SelfTest] {}", line));
		return 1;
	}
}
