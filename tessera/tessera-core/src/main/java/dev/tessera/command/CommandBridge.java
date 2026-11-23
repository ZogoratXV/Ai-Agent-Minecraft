package dev.tessera.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;
import dev.tessera.Tessera;
import dev.tessera.TesseraMod;
import dev.tessera.bukkit.TesseraServer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;

/**
 * Espone i comandi Bukkit (SimpleCommandMap) nell'albero Brigadier di Minecraft: così compaiono
 * nel completamento del client e convivono con i comandi vanilla e delle mod Fabric.
 * Ogni comando diventa {@code /label [args...]} con un unico argomento "greedy".
 */
public final class CommandBridge {

    public static void syncCommands(MinecraftServer server) {
        TesseraServer bukkit = Tessera.bukkit();
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        int count = 0;
        for (Map.Entry<String, Command> entry : bukkit.getCommandMap().getKnownCommands().entrySet()) {
            String label = entry.getKey();
            if (label.isBlank() || label.contains(" ")) continue;
            CommandNode<CommandSourceStack> existing = dispatcher.getRoot().getChild(label);
            if (existing != null && !(existing.getCommand() instanceof BukkitExecutor)) {
                // Come su Paper, il comando del plugin sostituisce quello vanilla/mod con lo stesso nome.
                dispatcher.getRoot().getChildren().removeIf(n -> n.getName().equals(label));
            }
            BukkitExecutor exec = new BukkitExecutor(label);
            dispatcher.register(Commands.literal(label)
                    .executes(exec)
                    .then(Commands.argument("args", StringArgumentType.greedyString())
                            .suggests(CommandBridge::suggest)
                            .executes(exec)));
            count++;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            server.getCommands().sendCommands(player);
        }
        TesseraMod.LOGGER.info("Registrati {} comandi Bukkit nel dispatcher Brigadier", count);
    }

    static CommandSender sender(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null ? Tessera.bukkit().wrap(player) : Tessera.bukkit().getConsoleSender();
    }

    private static CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String input = builder.getInput();
        String line = input.startsWith("/") ? input.substring(1) : input;
        try {
            List<String> completions = Tessera.bukkit().getCommandMap().tabComplete(sender(ctx.getSource()), line);
            if (completions != null) {
                SuggestionsBuilder offset = builder.createOffset(input.lastIndexOf(' ') + 1);
                completions.forEach(offset::suggest);
                return offset.buildFuture();
            }
        } catch (Throwable t) {
            TesseraMod.LOGGER.warn("Errore nel completamento di '{}'", line, t);
        }
        return builder.buildFuture();
    }

    private record BukkitExecutor(String label) implements com.mojang.brigadier.Command<CommandSourceStack> {
        @Override
        public int run(CommandContext<CommandSourceStack> ctx) {
            String input = ctx.getInput();
            String line = input.startsWith("/") ? input.substring(1) : input;
            CommandSender sender = sender(ctx.getSource());
            try {
                Tessera.bukkit().getCommandMap().dispatch(sender, line);
            } catch (CommandException e) {
                TesseraMod.LOGGER.error("Errore nell'esecuzione di /{}", line, e);
                sender.sendMessage(Component.text("Errore interno durante l'esecuzione del comando.", NamedTextColor.RED));
            }
            return 1;
        }
    }

    private CommandBridge() {
    }
}
