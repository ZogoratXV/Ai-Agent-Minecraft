package dev.agentcraft.server.ops;

import com.google.gson.JsonObject;
import dev.agentcraft.server.audit.AuditLog;
import dev.agentcraft.server.config.PluginSettings;
import dev.agentcraft.server.state.TeamView;
import dev.agentcraft.server.state.Views;
import dev.agentcraft.server.tools.ToolFailure;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * server.deploy and server.restart, staging only (the ToolGate refuses them in production) and only
 * after an admin answered the matching {@code deploy} decision.
 */
public final class DeployService {

    /** Options of the deploy decision, in this order (the foreman uses the same texts). */
    public static final String INSTALL_AND_RESTART = "Installa e riavvia";

    public static final String INSTALL_ONLY = "Installa, riavvio io";
    public static final long MAX_JAR_BYTES = 64L * 1024 * 1024;
    /** Created before a scripted restart; the start scripts start the server again when it exists. */
    public static final String RESTART_FLAG = ".agentcraft-restart";

    private final Plugin self;
    private final Path dataDir;
    private final PluginSettings.Deploy settings;
    private final TeamView view;
    private final AuditLog audit;
    private final Logger log;
    private final Map<String, String> env;
    private boolean restarting;

    public DeployService(
            Plugin self,
            Path dataDir,
            PluginSettings.Deploy settings,
            TeamView view,
            AuditLog audit,
            Logger log,
            Map<String, String> env) {
        this.self = self;
        this.dataDir = dataDir;
        this.settings = settings;
        this.view = view;
        this.audit = audit;
        this.log = log;
        this.env = env;
    }

    public CompletableFuture<JsonObject> handle(String tool, JsonObject args, String agentId, String by) {
        return switch (tool) {
            case "server.deploy" -> CompletableFuture.completedFuture(deploy(args));
            case "server.restart" -> CompletableFuture.completedFuture(restart(args));
            default -> throw new ToolFailure("invalid_args", "unknown tool " + tool);
        };
    }

    private Views.Decision approved(String approvalId, boolean restart) {
        Views.Decision d = view.decision(approvalId)
                .orElseThrow(() -> new ToolFailure("needs_approval", "unknown decision " + approvalId));
        String option = d.answer() == null ? null : d.answer().option();
        boolean ok = "deploy".equals(d.kind())
                && "answered".equals(d.status())
                && option != null
                && (option.equals(INSTALL_AND_RESTART) || (!restart && option.equals(INSTALL_ONLY)));
        if (!ok) {
            throw new ToolFailure(
                    "needs_approval",
                    "decision " + approvalId + " did not approve this " + (restart ? "restart" : "install"));
        }
        return d;
    }

    /** What a deployable jar declares. */
    public record JarInfo(String name, String version) {}

    /** Reads plugin.yml / paper-plugin.yml; refuses anything that is not a plugin jar. */
    public static JarInfo inspect(Path jar) throws IOException {
        if (!Files.isRegularFile(jar)
                || !jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new ToolFailure("invalid_args", "not a .jar file");
        }
        if (Files.size(jar) > MAX_JAR_BYTES) {
            throw new ToolFailure("too_large", "jar larger than 64 MB");
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry e = zip.getEntry("paper-plugin.yml");
            if (e == null) {
                e = zip.getEntry("plugin.yml");
            }
            if (e == null) {
                throw new ToolFailure("invalid_args", "the jar has no plugin.yml or paper-plugin.yml");
            }
            String text;
            try (InputStream in = zip.getInputStream(e)) {
                text = new String(in.readNBytes(256 * 1024), StandardCharsets.UTF_8);
            }
            YamlConfiguration y = new YamlConfiguration();
            try {
                y.loadFromString(text);
            } catch (InvalidConfigurationException ex) {
                throw new ToolFailure("invalid_args", "invalid " + e.getName());
            }
            String name = y.getString("name", "");
            if (!name.matches("[A-Za-z0-9_.-]{1,64}")) {
                throw new ToolFailure("invalid_args", "invalid plugin name '" + name + "'");
            }
            return new JarInfo(name, y.getString("version", "?"));
        }
    }

    private JsonObject deploy(JsonObject args) {
        String approval = ServerOps.str(args, "approvalId");
        approved(approval, false);
        Path root = dataDir.resolve(settings.fromDir()).toAbsolutePath().normalize();
        Path file = root.resolve(ServerOps.str(args, "file")).toAbsolutePath().normalize();
        try {
            if (!file.startsWith(root) || !file.toRealPath().startsWith(root.toRealPath())) {
                throw new ToolFailure("forbidden", "only jars in " + settings.fromDir() + " can be installed");
            }
            JarInfo info = inspect(file);
            if (info.name().equalsIgnoreCase(self.getName())) {
                throw new ToolFailure("forbidden", "AgentCraft cannot replace itself");
            }
            for (String p : settings.protectedPlugins()) {
                if (p.equalsIgnoreCase(info.name())) {
                    throw new ToolFailure("forbidden", info.name() + " is in deploy.protected-plugins");
                }
            }
            Path pluginsDir = self.getDataFolder()
                    .getParentFile()
                    .toPath()
                    .toAbsolutePath()
                    .normalize();
            Plugin existing = Bukkit.getPluginManager().getPlugin(info.name());
            Path target;
            if (existing != null) {
                Path current = Path.of(existing.getClass()
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI());
                target = Bukkit.getUpdateFolderFile().toPath().resolve(current.getFileName());
            } else {
                target = pluginsDir.resolve(info.name() + ".jar");
                if (Files.exists(target)) {
                    target = Bukkit.getUpdateFolderFile().toPath().resolve(info.name() + ".jar");
                }
            }
            target = target.toAbsolutePath().normalize();
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            Files.copy(file, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("installed " + info.name() + " " + info.version() + " as " + target + " (approval " + approval
                    + ")");
            JsonObject out = new JsonObject();
            out.addProperty("plugin", info.name());
            out.addProperty("version", info.version());
            out.addProperty(
                    "installedAs", pluginsDir.relativize(target).toString().replace('\\', '/'));
            out.addProperty("active", "after the next restart");
            out.addProperty(
                    "previousVersion",
                    existing == null ? "" : existing.getPluginMeta().getVersion());
            return out;
        } catch (IOException | java.net.URISyntaxException e) {
            throw new ToolFailure("internal", "install failed: " + e.getMessage());
        }
    }

    /** How a restart would be done here (shown by /agent status too). */
    public PluginSettings.RestartMethod method() {
        if (settings.restart() != PluginSettings.RestartMethod.AUTO) {
            return settings.restart();
        }
        if (!settings.pelicanUrl().isBlank() && !settings.pelicanKeyFile().isBlank()) {
            return PluginSettings.RestartMethod.PELICAN;
        }
        if (env.containsKey("P_SERVER_UUID")) {
            return PluginSettings.RestartMethod.NONE; // a panel without API access: restart from the panel
        }
        return PluginSettings.RestartMethod.SCRIPT;
    }

    private JsonObject restart(JsonObject args) {
        String approval = ServerOps.str(args, "approvalId");
        approved(approval, true);
        if (restarting) {
            throw new ToolFailure("busy", "a restart is already scheduled");
        }
        int delay = args.has("delaySeconds")
                ? Math.max(5, Math.min(300, args.get("delaySeconds").getAsInt()))
                : 15;
        PluginSettings.RestartMethod m = method();
        if (m == PluginSettings.RestartMethod.NONE) {
            throw new ToolFailure(
                    "not_found",
                    "this server cannot restart itself (deploy.restart: none, or a panel without"
                            + " deploy.pelican settings): restart it from the panel");
        }
        String panelKey = null;
        String serverId = null;
        if (m == PluginSettings.RestartMethod.PELICAN) {
            try {
                panelKey = Files.readString(dataDir.resolve(settings.pelicanKeyFile()))
                        .strip();
            } catch (IOException e) {
                throw new ToolFailure("not_found", "cannot read deploy.pelican.api-key-file");
            }
            serverId = settings.pelicanServerId().isBlank()
                    ? env.getOrDefault("P_SERVER_UUID", "")
                    : settings.pelicanServerId();
            if (serverId.isBlank() || panelKey.isBlank()) {
                throw new ToolFailure("not_found", "set deploy.pelican.server-id (and the API key file)");
            }
        }
        restarting = true;
        Bukkit.getServer()
                .broadcast(Component.text(
                        "[AgentCraft] Il server si riavvia tra " + delay + " secondi per aggiornare i plugin.",
                        NamedTextColor.GOLD));
        String key = panelKey;
        String id = serverId;
        Bukkit.getGlobalRegionScheduler().runDelayed(self, t -> doRestart(m, key, id), delay * 20L);
        JsonObject out = new JsonObject();
        out.addProperty("method", m.name().toLowerCase(Locale.ROOT));
        out.addProperty("inSeconds", delay);
        return out;
    }

    private void doRestart(PluginSettings.RestartMethod m, String key, String serverId) {
        if (m == PluginSettings.RestartMethod.SCRIPT) {
            try {
                Files.writeString(Path.of(RESTART_FLAG), "restart requested by AgentCraft\n");
            } catch (IOException e) {
                log.warning("cannot write " + RESTART_FLAG + ": " + e.getMessage());
            }
            log.info("restarting: the start script starts the server again");
            Bukkit.shutdown();
            return;
        }
        HttpClient http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(settings.pelicanUrl() + "/api/client/servers/" + serverId + "/power"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + key)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"signal\":\"restart\"}"))
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((res, err) -> {
            if (err != null || res.statusCode() >= 300) {
                String why = err != null ? err.getMessage() : "HTTP " + res.statusCode();
                log.warning("the panel refused the restart (" + why + "): restart from the panel");
                audit.record(new AuditLog.Entry("plugin", "plugin", "server.restart", "pelican", "failed: " + why));
                restarting = false;
            } else {
                log.info("restart requested to the panel");
            }
        });
    }
}
