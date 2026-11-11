package dev.agentcraft.server.config;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/** Reads and validates config.yml. Collects every problem so the admin can fix them in one go. */
public final class SettingsLoader {

    private SettingsLoader() {}

    public static PluginSettings load(ConfigurationSection cfg) {
        List<String> errors = new ArrayList<>();

        String serverId = cfg.getString("server.id", "server");
        if (serverId == null || !serverId.matches("[A-Za-z0-9._:-]{1,64}")) {
            errors.add("server.id must be 1-64 characters of [A-Za-z0-9._:-]");
        }

        ServerRole role = ServerRole.PRODUCTION;
        try {
            role = ServerRole.parse(cfg.getString("server.role"));
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }

        URI bridge = null;
        String url = cfg.getString("bridge.url", "ws://127.0.0.1:7878");
        try {
            bridge = new URI(url);
            if (!"ws".equals(bridge.getScheme())) {
                errors.add("bridge.url must use ws:// (the foreman runs on the same machine or private network)");
            } else if (bridge.getHost() == null || !isPrivateHost(bridge.getHost())) {
                errors.add("bridge.url host must be loopback or a private network address");
            }
        } catch (URISyntaxException e) {
            errors.add("bridge.url is not a valid URI: " + e.getMessage());
        }

        int reconnectMin = positive(cfg, "bridge.reconnect-min-seconds", 2, errors);
        int reconnectMax = positive(cfg, "bridge.reconnect-max-seconds", 60, errors);
        if (reconnectMax < reconnectMin) {
            errors.add("bridge.reconnect-max-seconds must be >= reconnect-min-seconds");
        }

        int blocksPerTick = positive(cfg, "world.blocks-per-tick", 200, errors);
        if (blocksPerTick > 5000) {
            errors.add("world.blocks-per-tick must be <= 5000");
        }
        double maxMspt = cfg.getDouble("world.pause-when-mspt-above", 45.0);
        if (maxMspt <= 0) {
            errors.add("world.pause-when-mspt-above must be > 0");
        }

        Set<String> blacklist = new LinkedHashSet<>();
        for (String id : cfg.getStringList("world.block-blacklist")) {
            blacklist.add(BlockIds.normalize(id));
        }

        List<String> whitelist = new ArrayList<>();
        for (String entry : cfg.getStringList("commands.whitelist")) {
            String normalized = CommandText.normalize(entry);
            if (normalized.isEmpty()) {
                errors.add("commands.whitelist contains an empty entry");
            } else {
                whitelist.add(normalized);
            }
        }

        PluginSettings.CommandRunAs runAs = parseEnum(
                PluginSettings.CommandRunAs.class,
                cfg.getString("commands.run-as", "console"),
                "commands.run-as",
                errors);
        PluginSettings.NpcBackend npc =
                parseEnum(PluginSettings.NpcBackend.class, cfg.getString("npc.backend", "auto"), "npc.backend", errors);

        String auditFile = cfg.getString("audit.file", "audit.jsonl");
        if (auditFile == null || auditFile.isBlank() || auditFile.contains("..")) {
            errors.add("audit.file must be a file name inside the plugin folder");
        }

        boolean managed = cfg.getBoolean("foreman.managed", true);
        String node = cfg.getString("foreman.node", "auto");
        String backend = cfg.getString("foreman.backend", "sim");
        if (!"sim".equals(backend) && !"claude".equals(backend)) {
            errors.add("foreman.backend must be 'sim' or 'claude'");
        }
        if (node == null || node.isBlank()) {
            errors.add("foreman.node must name the Node executable");
        }
        String apiKeyFile = cfg.getString("foreman.api-key-file", "");
        if (apiKeyFile != null && (apiKeyFile.contains("..") || apiKeyFile.startsWith("/"))) {
            errors.add("foreman.api-key-file must be a file name inside the plugin folder");
        }
        String claudeTokenFile = cfg.getString("foreman.claude-token-file", "");
        if (claudeTokenFile != null && (claudeTokenFile.contains("..") || claudeTokenFile.startsWith("/"))) {
            errors.add("foreman.claude-token-file must be a file name inside the plugin folder");
        }
        if (apiKeyFile != null && !apiKeyFile.isBlank() && claudeTokenFile != null && !claudeTokenFile.isBlank()) {
            errors.add("set only one of foreman.api-key-file and foreman.claude-token-file");
        }
        List<String> extraArgs = cfg.getStringList("foreman.extra-args");
        for (String a : extraArgs) {
            if (a.length() > 200 || a.chars().anyMatch(c -> c < 0x20)) {
                errors.add("foreman.extra-args entries must be short plain text");
            }
            if (a.startsWith("--host")
                    || a.startsWith("--port")
                    || a.startsWith("--home")
                    || a.startsWith("--backend")) {
                errors.add("foreman.extra-args must not set " + a.split("=")[0]
                        + " (set it in bridge.url / foreman.backend)");
            }
        }
        if (managed && bridge != null && bridge.getHost() != null) {
            String h = bridge.getHost();
            if (!h.equals("127.0.0.1") && !h.equalsIgnoreCase("localhost") && !h.equals("[::1]")) {
                errors.add("foreman.managed needs bridge.url on 127.0.0.1 or localhost (or set managed: false)");
            }
        }

        if (!errors.isEmpty()) {
            throw new SettingsException(errors);
        }
        return new PluginSettings(
                serverId,
                role,
                bridge,
                cfg.getString("bridge.token-file", ""),
                reconnectMin,
                reconnectMax,
                blocksPerTick,
                positive(cfg, "world.build-blocks-per-second", 10, errors),
                maxMspt,
                positive(cfg, "world.max-job-volume", 200_000, errors),
                positive(cfg, "world.max-inspect-volume", 65_536, errors),
                cfg.getBoolean("world.protect-containers", true),
                blacklist,
                whitelist,
                runAs,
                positive(cfg, "limits.tool-calls-per-minute", 60, errors),
                auditFile,
                npc,
                new PluginSettings.Foreman(
                        managed,
                        node,
                        cfg.getString("foreman.npm", "auto"),
                        backend,
                        extraArgs,
                        apiKeyFile == null ? "" : apiKeyFile,
                        claudeTokenFile == null ? "" : claudeTokenFile));
    }

    /** Loopback or private ranges only, without any DNS lookup. */
    public static boolean isPrivateHost(String host) {
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (h.equalsIgnoreCase("localhost")) {
            return true;
        }
        if (!h.matches("[0-9.]+") && !h.contains(":")) {
            return false; // a name would need DNS; refuse
        }
        try {
            InetAddress a = InetAddress.ofLiteral(h);
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || isUniqueLocalV6(a);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isUniqueLocalV6(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    private static int positive(ConfigurationSection cfg, String path, int def, List<String> errors) {
        int v = cfg.getInt(path, def);
        if (v <= 0) {
            errors.add(path + " must be > 0");
            return def;
        }
        return v;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String path, List<String> errors) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(path + " has an invalid value: " + value);
            return type.getEnumConstants()[0];
        }
    }
}
