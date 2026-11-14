package dev.rpgbridge;

import dev.rpgbridge.net.ConfigurationHandshake;
import io.papermc.paper.network.ChannelInitializeListenerHolder;
import net.kyori.adventure.key.Key;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Level;

public final class RpgBridgePlugin extends JavaPlugin {
	private static final Key CHANNEL_LISTENER = Key.key("rpgbridge", "handshake");
	private Dump dump;
	private int injectedItems;

	@Override
	public void onLoad() {
		try {
			dump = Dump.load(getDataFolder().toPath().resolve("dump"));
			RegistryInjector injector = new RegistryInjector(getLogger());
			if (!injector.verifyVanillaPrefix(dump)) {
				throw new IllegalStateException("The dump was made for a different Minecraft version");
			}
			injectedItems = injector.injectItems(dump);
			if (!injector.verifyVanillaPrefix(dump)) throw new IllegalStateException("Injection produced different raw ids");
			getLogger().info("Injected " + injectedItems + " modded items");
		} catch (Exception e) {
			dump = null;
			getLogger().log(Level.SEVERE, "RPG Bridge disabled: " + e.getMessage(), e);
		}
	}

	@Override
	public void onEnable() {
		if (dump == null) {
			getServer().getPluginManager().disablePlugin(this);
			return;
		}
		try {
			MinecraftServer server = MinecraftServer.getServer();
			getServer().getPluginManager().registerEvents(new ConfigurationHandshake(getLogger(), dump, server), this);
			ChannelInitializeListenerHolder.addListener(CHANNEL_LISTENER, ConfigurationHandshake::install);
		} catch (Exception e) {
			getLogger().log(Level.SEVERE, "Cannot start the handshake", e);
			getServer().getPluginManager().disablePlugin(this);
		}
	}

	@Override
	public void onDisable() {
		if (ChannelInitializeListenerHolder.hasListener(CHANNEL_LISTENER)) {
			ChannelInitializeListenerHolder.removeListener(CHANNEL_LISTENER);
		}
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length >= 1 && args[0].equalsIgnoreCase("status")) {
			sender.sendMessage("RPG Bridge: " + injectedItems + " modded items injected");
			return true;
		}
		if (args.length >= 2 && args[0].equalsIgnoreCase("give")) {
			Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : (sender instanceof Player p ? p : null);
			if (target == null) {
				sender.sendMessage("Player not found");
				return true;
			}
			Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(args[1])).orElse(null);
			if (item == null) {
				sender.sendMessage("Unknown item " + args[1]);
				return true;
			}
			((CraftPlayer) target).getHandle().getInventory().add(new ItemStack(item));
			sender.sendMessage("Given " + args[1] + " to " + target.getName());
			return true;
		}
		return false;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) return List.of("status", "give");
		if (args.length == 2 && args[0].equalsIgnoreCase("give") && dump != null) {
			return dump.registryIds.getOrDefault("minecraft:item", List.of()).stream()
					.filter(id -> !id.startsWith("minecraft:") && id.startsWith(args[1])).limit(50).toList();
		}
		return null;
	}
}
