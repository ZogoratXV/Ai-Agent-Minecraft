package dev.agentcraft.server;

import com.google.gson.JsonObject;
import dev.agentcraft.server.audit.AuditLog;
import dev.agentcraft.server.body.Bodies;
import dev.agentcraft.server.body.BodyManager;
import dev.agentcraft.server.body.Stations;
import dev.agentcraft.server.bridge.BridgeClient;
import dev.agentcraft.server.bridge.MessageRouter;
import dev.agentcraft.server.bridge.TokenSource;
import dev.agentcraft.server.build.BuildJobs;
import dev.agentcraft.server.build.BuildService;
import dev.agentcraft.server.build.PreviewManager;
import dev.agentcraft.server.build.Regions;
import dev.agentcraft.server.command.AgentCommand;
import dev.agentcraft.server.config.PluginSettings;
import dev.agentcraft.server.config.SettingsException;
import dev.agentcraft.server.config.SettingsLoader;
import dev.agentcraft.server.foreman.ForemanLauncher;
import dev.agentcraft.server.notify.Notifier;
import dev.agentcraft.server.state.TeamView;
import dev.agentcraft.server.tools.ToolExecutor;
import dev.agentcraft.server.tools.ToolGate;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point. The plugin is the "body": NPCs, world tools and commands. The foreman is the brain.
 * Not final: MockBukkit subclasses it in tests.
 */
public class AgentCraftPlugin extends JavaPlugin {

    private PluginSettings settings;
    private Integrations integrations;
    private AuditLog audit;
    private TeamView teamView;
    private Stations stations;
    private BodyManager bodies;
    private BridgeClient bridge;
    private ForemanLauncher launcher;
    private Regions regions;
    private PreviewManager previews;
    private BuildJobs jobs;
    private String token;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            settings = SettingsLoader.load(getConfig());
        } catch (SettingsException e) {
            e.problems().forEach(p -> getLogger().severe("config.yml: " + p));
            getLogger().severe("AgentCraft is disabled until config.yml is fixed.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        integrations = Integrations.detect(getServer().getPluginManager());
        audit = new AuditLog(getDataPath().resolve(settings.auditFile()), Clock.systemUTC(), getLogger());

        teamView = new TeamView();
        stations = new Stations(getDataPath().resolve("stations.yml").toFile(), getLogger());
        bodies = new BodyManager(this, stations, new Bodies(settings.npcBackend(), getLogger()), getLogger());
        Notifier notifier = new Notifier(teamView);
        teamView.addListener(bodies);
        teamView.addListener(notifier);
        getServer().getPluginManager().registerEvents(notifier, this);

        ToolGate gate = new ToolGate(settings.role(), settings.toolCallsPerMinute(), System::currentTimeMillis);
        ToolExecutor tools = new ToolExecutor(gate, bodies, audit, this::send);
        regions = new Regions(getDataPath().resolve("regions.yml").toFile(), getLogger());
        previews = new PreviewManager(this);
        jobs = new BuildJobs(
                this,
                getDataPath().resolve("jobs"),
                bodies,
                audit,
                this::send,
                getLogger(),
                settings.blocksPerTick(),
                settings.visibleBlocksPerSecond(),
                settings.pauseWhenMsptAbove());
        jobs.start();
        tools.setBuildService(new BuildService(regions, previews, jobs, teamView, settings));
        MessageRouter router = new MessageRouter(teamView, tools, notifier, getLogger());
        router.setJobs(jobs);

        getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event ->
                                event.registrar().register(AgentCommand.build(this), "AgentCraft: agenti AI come NPC"));

        Bukkit.getGlobalRegionScheduler().execute(this, () -> {
            bodies.removeStrays();
            Location base = stations.base();
            base.getWorld().addPluginChunkTicket(base.getBlockX() >> 4, base.getBlockZ() >> 4, this);
        });

        startBridge(router);
        getLogger()
                .info("AgentCraft ready: server '" + settings.serverId() + "' ("
                        + settings.role().wireName() + "), foreman at " + settings.bridgeUri() + ", integrations "
                        + integrations);
    }

    private void startBridge(MessageRouter router) {
        Optional<String> resolved;
        try {
            resolved = TokenSource.resolve(System.getenv(), getDataPath(), settings.tokenFile());
        } catch (IOException e) {
            getLogger().severe("bridge token: " + e.getMessage());
            return;
        }
        if (resolved.isEmpty() && settings.foreman().managed()) {
            try {
                java.nio.file.Path file = getDataPath().resolve("token.txt");
                resolved = Files.exists(file)
                        ? TokenSource.resolve(java.util.Map.of(), getDataPath(), "token.txt")
                        : Optional.of(ForemanLauncher.createTokenFile(file));
            } catch (IOException e) {
                getLogger().severe("cannot create the bridge token: " + e.getMessage());
                return;
            }
        }
        if (resolved.isEmpty()) {
            getLogger().warning("No bridge token: set AGENTCRAFT_TOKEN (or bridge.token-file). Agents stay offline.");
            return;
        }
        this.token = resolved.get();
        if (settings.foreman().managed()) {
            PluginSettings.Foreman f = settings.foreman();
            launcher = new ForemanLauncher(
                    new ForemanLauncher.Options(
                            f.node(),
                            f.npm(),
                            f.backend(),
                            settings.bridgeUri(),
                            f.extraArgs(),
                            getDataPath().resolve("foreman"),
                            token,
                            f.apiKeyFile().isBlank() ? null : getDataPath().resolve(f.apiKeyFile()),
                            f.claudeTokenFile().isBlank() ? null : getDataPath().resolve(f.claudeTokenFile())),
                    () -> getResource("foreman/foreman.mjs"),
                    getLogger());
            if (!f.claudeTokenFile().isBlank()) {
                getLogger()
                        .warning("Agents use the Claude subscription of whoever made the token (personal use"
                                + " only): do not use it on a server where other people give the agents work.");
            }
            launcher.start();
        }
        bridge = new BridgeClient(
                settings.bridgeUri(),
                this.token,
                this::hello,
                env -> {
                    if (isEnabled()) {
                        Bukkit.getGlobalRegionScheduler().execute(this, () -> router.handle(env));
                    }
                },
                up -> getLogger().info(up ? "connected to the foreman" : "foreman connection lost, retrying"),
                getLogger(),
                Duration.ofSeconds(settings.reconnectMinSeconds()),
                Duration.ofSeconds(settings.reconnectMaxSeconds()));
        bridge.start();
    }

    private JsonObject hello() {
        JsonObject server = new JsonObject();
        server.addProperty("id", settings.serverId());
        server.addProperty("role", settings.role().wireName());
        server.addProperty("mcVersion", Bukkit.getMinecraftVersion());
        String software = (Bukkit.getName() + " " + Bukkit.getVersion()).strip();
        server.addProperty("software", software.length() > 64 ? software.substring(0, 64) : software);

        JsonObject caps = new JsonObject();
        caps.addProperty("citizens", integrations.citizens());
        caps.addProperty("worldguard", integrations.worldGuard());
        caps.addProperty("worldedit", integrations.worldEdit());
        caps.addProperty("luckperms", integrations.luckPerms());
        caps.addProperty("spark", integrations.spark());

        JsonObject limits = new JsonObject();
        limits.addProperty("blocksPerTick", settings.blocksPerTick());
        limits.addProperty("maxJobVolume", settings.maxJobVolume());
        limits.addProperty("maxInspectVolume", settings.maxInspectVolume());
        limits.addProperty("toolCallsPerMinute", settings.toolCallsPerMinute());

        JsonObject hello = new JsonObject();
        hello.addProperty("v", 1);
        hello.addProperty("type", "hello");
        hello.addProperty("token", token);
        hello.addProperty("pluginVersion", getPluginMeta().getVersion());
        hello.add("server", server);
        hello.add("capabilities", caps);
        hello.add("limits", limits);
        return hello;
    }

    @Override
    public void onDisable() {
        if (bridge != null) {
            bridge.stop();
        }
        if (launcher != null) {
            launcher.stop();
        }
        if (previews != null) {
            previews.clearAll();
        }
        if (jobs != null) {
            jobs.stop();
        }
        if (bodies != null) {
            bodies.removeAll();
        }
        if (audit != null) {
            audit.close();
        }
    }

    /** Sends a message to the foreman (dropped while disconnected). */
    public void send(JsonObject msg) {
        if (bridge != null) {
            bridge.send(msg);
        }
    }

    /** Sends an intent and completes with the foreman's ack. */
    public CompletableFuture<JsonObject> request(JsonObject msg) {
        if (bridge == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("bridge non configurato (manca AGENTCRAFT_TOKEN)"));
        }
        return bridge.request(msg);
    }

    public boolean isBridgeConnected() {
        return bridge != null && bridge.isConnected();
    }

    public PluginSettings settings() {
        return settings;
    }

    public Integrations integrations() {
        return integrations;
    }

    public AuditLog audit() {
        return audit;
    }

    public TeamView teamView() {
        return teamView;
    }

    public Stations stations() {
        return stations;
    }

    public Regions regions() {
        return regions;
    }

    public BuildJobs jobs() {
        return jobs;
    }
}
