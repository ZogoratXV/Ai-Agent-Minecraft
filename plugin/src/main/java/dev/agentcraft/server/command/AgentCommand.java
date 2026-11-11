package dev.agentcraft.server.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.agentcraft.server.AgentCraftPlugin;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * {@code /agent}. Phase 2 only has {@code status} and {@code help}; the other subcommands arrive
 * with the bridge (phase 3) and the real agents (phase 4).
 */
public final class AgentCommand {
    public static final String PERM_USE = "agentcraft.use";

    private AgentCommand() {}

    public static LiteralCommandNode<CommandSourceStack> build(AgentCraftPlugin plugin) {
        return Commands.literal("agent")
                .requires(src -> src.getSender().hasPermission(PERM_USE))
                .executes(ctx -> help(ctx.getSource()))
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource())))
                .then(Commands.literal("status").executes(ctx -> {
                    StatusView.render(
                                    plugin.getPluginMeta().getVersion(),
                                    plugin.settings(),
                                    plugin.integrations(),
                                    false)
                            .forEach(ctx.getSource().getSender()::sendMessage);
                    return Command.SINGLE_SUCCESS;
                }))
                .build();
    }

    private static int help(CommandSourceStack src) {
        src.getSender()
                .sendMessage(MiniMessage.miniMessage()
                        .deserialize("<gold>/agent status</gold> <gray>- stato del plugin e del foreman</gray>\n"
                                + "<dark_gray>Gli altri comandi arrivano nelle prossime fasi.</dark_gray>"));
        return Command.SINGLE_SUCCESS;
    }
}
