package dev.agentcraft.server.command;

import dev.agentcraft.server.Integrations;
import dev.agentcraft.server.config.PluginSettings;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/** Text of {@code /agent status}. Kept separate from Brigadier so it can be tested directly. */
public final class StatusView {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private StatusView() {}

    public static List<Component> render(
            String pluginVersion, PluginSettings settings, Integrations integrations, boolean connected) {
        List<Component> lines = new ArrayList<>();
        lines.add(MM.deserialize(
                "<gold>AgentCraft Server</gold> <gray>v<version></gray>",
                Placeholder.unparsed("version", pluginVersion)));
        lines.add(MM.deserialize(
                "<gray>Server:</gray> <white><id></white> <gray>(<role>)</gray>",
                Placeholder.unparsed("id", settings.serverId()),
                Placeholder.unparsed("role", settings.role().wireName())));
        lines.add(MM.deserialize(
                connected
                        ? "<gray>Foreman:</gray> <green>connesso</green> <dark_gray><url></dark_gray>"
                        : "<gray>Foreman:</gray> <red>non connesso</red> <dark_gray><url></dark_gray>",
                Placeholder.unparsed("url", settings.bridgeUri().toString())));
        lines.add(MM.deserialize(
                "<gray>Integrazioni:</gray> <list>",
                Placeholder.parsed(
                        "list",
                        String.join(
                                " ",
                                flag("Citizens", integrations.citizens()),
                                flag("WorldGuard", integrations.worldGuard()),
                                flag("WorldEdit", integrations.worldEdit()),
                                flag("LuckPerms", integrations.luckPerms()),
                                flag("spark", integrations.spark())))));
        return lines;
    }

    private static String flag(String name, boolean present) {
        return (present ? "<green>" : "<dark_gray>") + name + (present ? "</green>" : "</dark_gray>");
    }
}
