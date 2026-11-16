/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.rpgbridge.core.classlock.BoundTomes;
import dev.rpgbridge.core.classlock.ClassLock;
import dev.rpgbridge.core.classlock.TrinketSlots;
import dev.rpgbridge.core.classlock.WeaponBonuses;
import dev.rpgbridge.core.selftest.SelfTestBot;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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
		// Crea/legge config/rpgbridge/classi.yml all'avvio (MMOCore e i plugin sono già caricati).
		ServerLifecycleEvents.SERVER_STARTED.register(server -> reloadAll());
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(
				Commands.literal("rpgbridge")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("selftest")
								.executes(c -> selfTest(c.getSource(), "SelfTestBot", false))
								.then(Commands.argument("name", StringArgumentType.word())
										.executes(c -> selfTest(c.getSource(), StringArgumentType.getString(c, "name"), false))
										.then(Commands.literal("stay")
												.executes(c -> selfTest(c.getSource(), StringArgumentType.getString(c, "name"), true)))))
						.then(Commands.literal("ricarica").executes(c -> {
							c.getSource().sendSystemMessage(Component.literal("RPG Bridge: " + reloadAll()));
							return 1;
						}))
						.then(Commands.literal("tomo")
								.then(Commands.literal("slega").then(Commands.argument("giocatore", EntityArgument.player())
										.executes(c -> {
											ServerPlayer target = EntityArgument.getPlayer(c, "giocatore");
											var stack = target.getMainHandItem();
											if (BoundTomes.unbind(stack)) {
												target.inventoryMenu.broadcastChanges();
												c.getSource().sendSystemMessage(Component.literal("Tomo di " + target.getName().getString() + " sciolto: si legherà a chi lo equipaggia."));
												return 1;
											}
											c.getSource().sendFailure(Component.literal(target.getName().getString() + " non ha in mano un tomo legato."));
											return 0;
										}))))
						.then(Commands.literal("classi")
								.then(Commands.literal("ricarica").executes(c -> {
									c.getSource().sendSystemMessage(Component.literal("RPG Bridge: " + reloadAll()));
									return 1;
								}))
								.then(Commands.literal("controlla").then(Commands.argument("giocatore", EntityArgument.player())
										.executes(c -> classInfo(c.getSource(), EntityArgument.getPlayer(c, "giocatore"))))))
						.then(Commands.literal("bot")
								.then(Commands.literal("attack").then(Commands.argument("name", StringArgumentType.word())
										.executes(c -> botResult(c.getSource(), SelfTestBot.attack(StringArgumentType.getString(c, "name"), false)))
										.then(Commands.literal("vanilla")
												.executes(c -> botResult(c.getSource(), SelfTestBot.attack(StringArgumentType.getString(c, "name"), true))))))
								.then(Commands.literal("cast").then(Commands.argument("name", StringArgumentType.word())
										.then(Commands.argument("spell", StringArgumentType.greedyString())
												.executes(c -> botResult(c.getSource(), SelfTestBot.cast(StringArgumentType.getString(c, "name"),
														StringArgumentType.getString(c, "spell").trim()))))))
								.then(Commands.literal("slot").then(Commands.argument("name", StringArgumentType.word())
										.then(Commands.argument("n", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 8))
												.executes(c -> botResult(c.getSource(), SelfTestBot.slot(StringArgumentType.getString(c, "name"),
														com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "n")))))))
								.then(Commands.literal("use").then(Commands.argument("name", StringArgumentType.word())
										.executes(c -> botResult(c.getSource(), SelfTestBot.use(StringArgumentType.getString(c, "name"))))))
								.then(Commands.literal("leave").then(Commands.argument("name", StringArgumentType.word())
										.executes(c -> botResult(c.getSource(), SelfTestBot.leave(StringArgumentType.getString(c, "name")))))))));
	}

	private static int classInfo(CommandSourceStack source, ServerPlayer player) {
		String[] own = ClassLock.playerClass(player.getUUID());
		source.sendSystemMessage(Component.literal(player.getName().getString() + ": classe MMOCore "
				+ (own == null ? "non disponibile (MMOCore assente)" : own[0] + " (" + own[1] + ")")));
		for (var slot : net.minecraft.world.entity.EquipmentSlot.values()) {
			var stack = player.getItemBySlot(slot);
			if (stack.isEmpty()) continue;
			source.sendSystemMessage(Component.literal(" " + slot.getName() + ": " + describe(player, stack)));
		}
		for (var access : TrinketSlots.equipped(player)) {
			source.sendSystemMessage(Component.literal(" trinket: " + describe(player, access.get())));
		}
		return 1;
	}

	private static String describe(ServerPlayer player, net.minecraft.world.item.ItemStack stack) {
		var required = ClassLock.requiredClasses(stack);
		String owner = BoundTomes.ownerName(stack);
		return ClassLock.itemKeys(stack)
				+ (required == null ? " - classe libera" : " - classi " + required)
				+ (BoundTomes.owner(stack) == null ? (BoundTomes.isTome(stack) ? " - tomo non legato" : "") : " - tomo di " + owner)
				+ WeaponBonuses.describe(stack)
				+ (ClassLock.allowed(player, stack) ? " (consentito)" : " (BLOCCATO)");
	}

	private static String reloadAll() {
		return ClassLock.reload() + "; " + BoundTomes.reload() + "; " + WeaponBonuses.reload();
	}

	private static int selfTest(CommandSourceStack source, String name, boolean stay) {
		source.sendSystemMessage(Component.literal("RPG Bridge self-test: connecting as " + name + "..."));
		SelfTestBot.run(source.getServer(), name, line -> LOGGER.info("[SelfTest] {}", line), stay);
		return 1;
	}

	private static int botResult(CommandSourceStack source, boolean ok) {
		if (!ok) source.sendFailure(Component.literal("No bot with that name is online (start one with: rpgbridge selftest <name> stay)"));
		return ok ? 1 : 0;
	}
}
