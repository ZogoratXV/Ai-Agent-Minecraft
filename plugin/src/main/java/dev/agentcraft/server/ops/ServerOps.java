package dev.agentcraft.server.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.server.security.CommandPolicy;
import dev.agentcraft.server.tools.ToolFailure;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/** server.command, server.logs and server.metrics. Runs on the global region thread. */
public final class ServerOps {

    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b");
    private static final Pattern SECRET =
            Pattern.compile("(?i)(token|password|passwd|secret|api[-_ ]?key|authorization)\\S*\\s*[:=]?\\s*\\S+");
    private static final int MAX_OUTPUT = 8000;

    private final CommandPolicy policy;
    private final Path logFile;
    private final Tester tester;

    public ServerOps(CommandPolicy policy, Path logFile, Tester tester) {
        this.policy = policy;
        this.logFile = logFile;
        this.tester = tester;
    }

    public CompletableFuture<JsonObject> handle(String tool, JsonObject args) {
        return switch (tool) {
            case "server.command" -> command(args);
            case "server.logs" -> CompletableFuture.completedFuture(logs(args));
            case "server.metrics" -> CompletableFuture.completedFuture(metrics());
            default -> throw new ToolFailure("invalid_args", "unknown tool " + tool);
        };
    }

    private CompletableFuture<JsonObject> command(JsonObject args) {
        String raw = str(args, "command");
        String as = args.has("as") ? args.get("as").getAsString() : "console";
        if ("fakeplayer".equals(as)) {
            JsonObject action = new JsonObject();
            action.addProperty("kind", "command");
            action.addProperty("command", raw);
            JsonArray list = new JsonArray();
            list.add(action);
            return tester.run(list);
        }
        if (!"console".equals(as)) {
            throw new ToolFailure("invalid_args", "as must be console or fakeplayer");
        }
        CommandPolicy.Verdict v = policy.check(raw);
        if (v instanceof CommandPolicy.Denied d) {
            throw new ToolFailure("forbidden", d.reason());
        }
        String command = ((CommandPolicy.Allowed) v).command();
        StringBuilder out = new StringBuilder();
        CommandSender sender = Bukkit.createCommandSender(c -> append(out, c));
        boolean ok = Bukkit.dispatchCommand(sender, command);
        JsonObject res = new JsonObject();
        res.addProperty("command", command);
        res.addProperty("ok", ok);
        res.addProperty("output", redact(out.toString()));
        return CompletableFuture.completedFuture(res);
    }

    private static void append(StringBuilder out, Component c) {
        if (out.length() < MAX_OUTPUT) {
            out.append(PlainTextComponentSerializer.plainText().serialize(c)).append('\n');
        }
    }

    private JsonObject logs(JsonObject args) {
        int limit =
                args.has("limit") ? Math.max(1, Math.min(500, args.get("limit").getAsInt())) : 100;
        String filter = args.has("filter") ? args.get("filter").getAsString().toLowerCase(Locale.ROOT) : "";
        if (filter.length() > 200) {
            throw new ToolFailure("invalid_args", "filter too long");
        }
        List<String> lines;
        try {
            lines = tail(logFile, 2 * 1024 * 1024);
        } catch (IOException e) {
            throw new ToolFailure("not_found", "cannot read the server log: " + e.getMessage());
        }
        List<String> picked = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && picked.size() < limit; i--) {
            String l = lines.get(i);
            if (filter.isEmpty() || l.toLowerCase(Locale.ROOT).contains(filter)) {
                picked.add(redact(l.length() > 500 ? l.substring(0, 500) + "…" : l));
            }
        }
        java.util.Collections.reverse(picked);
        JsonObject res = new JsonObject();
        JsonArray arr = new JsonArray();
        picked.forEach(arr::add);
        res.add("lines", arr);
        return res;
    }

    /** IP addresses and anything that looks like a secret are hidden from the agents. */
    public static String redact(String s) {
        return SECRET.matcher(IPV4.matcher(s).replaceAll("x.x.x.x")).replaceAll("$1 [nascosto]");
    }

    static List<String> tail(Path file, int maxBytes) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException(file + " does not exist");
        }
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            long len = f.length();
            long from = Math.max(0, len - maxBytes);
            f.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            f.readFully(buf);
            String text = new String(buf, StandardCharsets.UTF_8).replaceAll("\u001B\\[[;\\d]*m", "");
            List<String> lines = new ArrayList<>(List.of(text.split("\r?\n")));
            if (from > 0 && !lines.isEmpty()) {
                lines.removeFirst(); // partial line
            }
            return lines;
        }
    }

    private static JsonObject metrics() {
        JsonObject res = new JsonObject();
        double[] tps = Bukkit.getTPS();
        JsonArray t = new JsonArray();
        for (double d : tps) {
            t.add(Math.round(d * 100) / 100.0);
        }
        res.add("tps1m5m15m", t);
        res.addProperty("msptAvg", Math.round(Bukkit.getAverageTickTime() * 100) / 100.0);
        res.addProperty("players", Bukkit.getOnlinePlayers().size());
        res.addProperty("maxPlayers", Bukkit.getMaxPlayers());
        Runtime rt = Runtime.getRuntime();
        res.addProperty("memoryUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        res.addProperty("memoryMaxMb", rt.maxMemory() / (1024 * 1024));
        res.addProperty("uptimeMinutes", ManagementFactory.getRuntimeMXBean().getUptime() / 60_000);
        res.addProperty("version", Bukkit.getVersion());
        JsonArray worlds = new JsonArray();
        for (World w : Bukkit.getWorlds()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", w.getName());
            o.addProperty("chunks", w.getLoadedChunks().length);
            o.addProperty("entities", w.getEntityCount());
            o.addProperty("tileEntities", w.getTileEntityCount());
            o.addProperty("players", w.getPlayerCount());
            worlds.add(o);
        }
        res.add("worlds", worlds);
        JsonArray plugins = new JsonArray();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", p.getName());
            o.addProperty("version", p.getPluginMeta().getVersion());
            o.addProperty("enabled", p.isEnabled());
            plugins.add(o);
        }
        res.add("plugins", plugins);
        return res;
    }

    static String str(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) {
            throw new ToolFailure("invalid_args", "missing string '" + key + "'");
        }
        return o.get(key).getAsString();
    }
}
