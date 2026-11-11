package dev.agentcraft.server.config;

import java.net.URI;
import java.util.List;
import java.util.Set;

/** Validated, immutable view of config.yml. */
public record PluginSettings(
        String serverId,
        ServerRole role,
        URI bridgeUri,
        String tokenFile,
        int reconnectMinSeconds,
        int reconnectMaxSeconds,
        int blocksPerTick,
        double pauseWhenMsptAbove,
        int maxJobVolume,
        int maxInspectVolume,
        boolean protectContainers,
        Set<String> blockBlacklist,
        List<String> commandWhitelist,
        CommandRunAs commandRunAs,
        int toolCallsPerMinute,
        String auditFile,
        NpcBackend npcBackend,
        Foreman foreman) {

    /** The foreman the plugin starts itself ({@code managed}), or an external one. */
    public record Foreman(boolean managed, String node, String backend, List<String> extraArgs) {
        public Foreman {
            extraArgs = List.copyOf(extraArgs);
        }
    }

    public enum CommandRunAs {
        CONSOLE,
        FAKEPLAYER
    }

    public enum NpcBackend {
        AUTO,
        CITIZENS,
        MANNEQUIN
    }

    public PluginSettings {
        blockBlacklist = Set.copyOf(blockBlacklist);
        commandWhitelist = List.copyOf(commandWhitelist);
    }
}
