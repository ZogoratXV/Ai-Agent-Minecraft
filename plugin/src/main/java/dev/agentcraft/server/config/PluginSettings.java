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
        int visibleBlocksPerSecond,
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
        Foreman foreman,
        Deploy deploy,
        Qa qa,
        String discordWebhookFile) {

    /**
     * Installing plugin jars built by the agents (staging only, after an admin's approval) and
     * restarting the server.
     */
    public record Deploy(
            String fromDir,
            List<String> protectedPlugins,
            RestartMethod restart,
            String pelicanUrl,
            String pelicanKeyFile,
            String pelicanServerId) {
        public Deploy {
            protectedPlugins = List.copyOf(protectedPlugins);
        }
    }

    public enum RestartMethod {
        AUTO,
        PELICAN,
        SCRIPT,
        NONE
    }

    /** The test player of player.simulate (a Citizens NPC that is a real Player entity). */
    public record Qa(String testerName, List<String> testerPermissions) {
        public Qa {
            testerPermissions = List.copyOf(testerPermissions);
        }
    }

    /** The foreman the plugin starts itself ({@code managed}), or an external one. */
    public record Foreman(
            boolean managed,
            String node,
            String npm,
            String backend,
            List<String> extraArgs,
            String apiKeyFile,
            String claudeTokenFile) {
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
