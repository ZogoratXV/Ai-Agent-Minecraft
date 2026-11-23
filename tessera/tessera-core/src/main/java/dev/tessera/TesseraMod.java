package dev.tessera;

import dev.tessera.api.Unsupported;
import dev.tessera.bridge.JulBridge;
import dev.tessera.bukkit.TesseraServer;
import dev.tessera.command.CommandBridge;
import dev.tessera.event.EventBridge;
import java.nio.file.Path;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.bukkit.Bukkit;
import org.bukkit.event.server.ServerLoadEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint Fabric. Ciclo di vita:
 * <ol>
 *   <li>SERVER_STARTING: crea il Server Bukkit e carica i plugin (onLoad)</li>
 *   <li>SERVER_STARTED: mondi pronti, abilita i plugin (onEnable) e registra i loro comandi</li>
 *   <li>ogni tick: esegue lo scheduler Bukkit</li>
 *   <li>SERVER_STOPPING: disabilita i plugin (onDisable)</li>
 * </ol>
 */
public final class TesseraMod implements DedicatedServerModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger(Tessera.NAME);
    private static TesseraConfig config;

    public static TesseraConfig config() {
        return config;
    }

    @Override
    public void onInitializeServer() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        config = TesseraConfig.load(configDir.resolve("tessera.properties"));
        Unsupported.setStrict(config.strictApi());
        JulBridge.install();
        LOGGER.info("Tessera {} per Minecraft {}: plugin Bukkit/Paper su Fabric (prototipo)", Tessera.VERSION, Tessera.MINECRAFT_VERSION);

        EventBridge.register();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            TesseraServer bukkit = new TesseraServer(server, config);
            Tessera.attach(server, bukkit);
            Bukkit.setServer(bukkit);
            bukkit.loadPlugins();
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            TesseraServer bukkit = Tessera.bukkit();
            bukkit.enablePlugins();
            CommandBridge.syncCommands(server);
            bukkit.getPluginManager().callEvent(new ServerLoadEvent(ServerLoadEvent.LoadType.STARTUP));
            LOGGER.info("Tessera pronto: {} plugin attivi, {} mod Fabric caricate",
                    java.util.Arrays.stream(bukkit.getPluginManager().getPlugins()).filter(org.bukkit.plugin.Plugin::isEnabled).count(),
                    FabricLoader.getInstance().getAllMods().size());
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> Tessera.bukkit().getScheduler().tick());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (Tessera.isRunning()) {
                Tessera.bukkit().disablePlugins();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> Tessera.detach());
    }
}
