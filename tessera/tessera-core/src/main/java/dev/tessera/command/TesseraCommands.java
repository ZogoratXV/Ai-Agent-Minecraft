package dev.tessera.command;

import dev.tessera.Tessera;
import dev.tessera.api.Unsupported;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/** Comandi del server: /plugins e /tessera. */
public final class TesseraCommands {

    public static void register(CommandMap map) {
        map.register("tessera", new PluginsCommand());
        map.register("tessera", new TesseraCommand());
    }

    static final class PluginsCommand extends Command {
        PluginsCommand() {
            super("plugins", "Elenca i plugin Bukkit installati", "/plugins", List.of("pl"));
            setPermission("bukkit.command.plugins");
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!testPermission(sender)) return true;
            Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
            List<String> names = new ArrayList<>();
            for (Plugin p : plugins) {
                names.add((p.isEnabled() ? ChatColor.GREEN : ChatColor.RED) + p.getName() + ChatColor.WHITE);
            }
            sender.sendMessage("Plugin Bukkit (" + plugins.length + "): " + String.join(", ", names));
            long mods = FabricLoader.getInstance().getAllMods().stream()
                    .filter(m -> m.getContainingMod().isEmpty() && !m.getMetadata().getType().equals("builtin")).count();
            sender.sendMessage("Mod Fabric: " + mods + " (dettagli con /tessera mods)");
            return true;
        }
    }

    static final class TesseraCommand extends Command {
        TesseraCommand() {
            super("tessera", "Informazioni su Tessera", "/tessera [version|mods|unsupported]", List.of());
            setPermission("tessera.command");
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!testPermission(sender)) return true;
            String sub = args.length == 0 ? "version" : args[0].toLowerCase(java.util.Locale.ROOT);
            switch (sub) {
                case "mods" -> FabricLoader.getInstance().getAllMods().stream()
                        .filter(m -> m.getContainingMod().isEmpty() && !m.getMetadata().getType().equals("builtin"))
                        .forEach(m -> sender.sendMessage(" - " + m.getMetadata().getName() + " " + m.getMetadata().getVersion().getFriendlyString()));
                case "unsupported" -> {
                    var seen = Unsupported.seen();
                    sender.sendMessage("Metodi API chiamati ma non implementati: " + seen.size());
                    seen.stream().sorted().forEach(s -> sender.sendMessage(" - " + s));
                }
                default -> sender.sendMessage(ChatColor.AQUA + Tessera.NAME + " " + Tessera.VERSION + ChatColor.WHITE
                        + " - Minecraft " + Tessera.MINECRAFT_VERSION + ", API Paper/Bukkit su Fabric (prototipo)");
            }
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return args.length == 1 ? List.of("version", "mods", "unsupported") : List.of();
        }
    }

    private TesseraCommands() {
    }
}
