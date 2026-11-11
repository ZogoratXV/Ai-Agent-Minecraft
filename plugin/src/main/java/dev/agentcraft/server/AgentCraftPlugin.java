package dev.agentcraft.server;

import dev.agentcraft.server.audit.AuditLog;
import dev.agentcraft.server.command.AgentCommand;
import dev.agentcraft.server.config.PluginSettings;
import dev.agentcraft.server.config.SettingsException;
import dev.agentcraft.server.config.SettingsLoader;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.time.Clock;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point. The plugin is the "body": NPCs, world tools and commands. The foreman is the brain.
 * Not final: MockBukkit subclasses it in tests.
 */
public class AgentCraftPlugin extends JavaPlugin {

    private PluginSettings settings;
    private Integrations integrations;
    private AuditLog audit;

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

        getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event ->
                                event.registrar().register(AgentCommand.build(this), "AgentCraft: agenti AI come NPC"));

        getLogger()
                .info("AgentCraft ready: server '" + settings.serverId() + "' ("
                        + settings.role().wireName() + "), foreman at " + settings.bridgeUri() + ", integrations "
                        + integrations);
    }

    @Override
    public void onDisable() {
        if (audit != null) {
            audit.close();
        }
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
}
