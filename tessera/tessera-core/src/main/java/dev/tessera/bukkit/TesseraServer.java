package dev.tessera.bukkit;

import dev.tessera.Tessera;
import dev.tessera.TesseraConfig;
import dev.tessera.bridge.Text;
import dev.tessera.plugin.TesseraPluginManager;
import dev.tessera.scheduler.TesseraScheduler;
import dev.tessera.stub.StubServer;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.SimpleServicesManager;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.StandardMessenger;
import org.jspecify.annotations.Nullable;

/** Implementazione di {@link org.bukkit.Server} sopra il MinecraftServer vanilla. */
public final class TesseraServer extends StubServer {

    private final MinecraftServer server;
    private final TesseraConfig config;
    private final Logger logger = Logger.getLogger("Minecraft");
    private final SimpleCommandMap commandMap;
    private final TesseraPluginManager pluginManager;
    private final TesseraScheduler scheduler = new TesseraScheduler();
    private final ServicesManager servicesManager = new SimpleServicesManager();
    private final Messenger messenger = new StandardMessenger();
    private final TesseraConsole console = new TesseraConsole(this);
    private final TesseraUnsafe unsafe = new TesseraUnsafe();
    private final Map<UUID, TesseraPlayer> players = new ConcurrentHashMap<>();
    private final Map<ServerLevel, TesseraWorld> worlds = new IdentityHashMap<>();

    public TesseraServer(MinecraftServer server, TesseraConfig config) {
        this.server = server;
        this.config = config;
        this.commandMap = new SimpleCommandMap(this, new HashMap<>());
        this.pluginManager = new TesseraPluginManager(this, commandMap, config.pluginsDir().toFile());
        dev.tessera.command.TesseraCommands.register(commandMap);
    }

    @Override
    protected String tesseraStubName() {
        return "Server";
    }

    public MinecraftServer handle() {
        return server;
    }

    // ---------------------------------------------------------------- ciclo di vita plugin

    public void loadPlugins() {
        pluginManager.loadAll();
    }

    public void enablePlugins() {
        pluginManager.enableAll();
    }

    public void disablePlugins() {
        scheduler.cancelAll();
        pluginManager.disablePlugins();
    }

    // ---------------------------------------------------------------- wrapper

    public TesseraPlayer wrap(ServerPlayer player) {
        return players.computeIfAbsent(player.getUUID(), id -> new TesseraPlayer(this, id, player.getGameProfile().name()));
    }

    public void forget(ServerPlayer player) {
        players.remove(player.getUUID());
    }

    public synchronized TesseraWorld wrap(ServerLevel level) {
        return worlds.computeIfAbsent(level, l -> new TesseraWorld(this, l));
    }

    // ---------------------------------------------------------------- identità

    @Override
    public String getName() {
        return Tessera.NAME;
    }

    @Override
    public String getVersion() {
        return Tessera.VERSION + " (MC: " + Tessera.MINECRAFT_VERSION + ")";
    }

    @Override
    public String getBukkitVersion() {
        return Tessera.MINECRAFT_VERSION + "-R0.1-SNAPSHOT";
    }

    @Override
    public String getMinecraftVersion() {
        return Tessera.MINECRAFT_VERSION;
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

    // ---------------------------------------------------------------- giocatori

    @Override
    public Collection<? extends Player> getOnlinePlayers() {
        List<Player> list = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            list.add(wrap(p));
        }
        return List.copyOf(list);
    }

    @Override
    public @Nullable Player getPlayer(String name) {
        Player exact = getPlayerExact(name);
        if (exact != null) return exact;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        Player found = null;
        int delta = Integer.MAX_VALUE;
        for (Player p : getOnlinePlayers()) {
            if (p.getName().toLowerCase(java.util.Locale.ROOT).startsWith(lower)) {
                int d = p.getName().length() - lower.length();
                if (d < delta) {
                    found = p;
                    delta = d;
                }
            }
        }
        return found;
    }

    @Override
    public @Nullable Player getPlayerExact(String name) {
        ServerPlayer p = server.getPlayerList().getPlayerByName(name);
        return p == null ? null : wrap(p);
    }

    @Override
    public @Nullable Player getPlayer(UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        return p == null ? null : wrap(p);
    }

    @Override
    public int getMaxPlayers() {
        return server.getPlayerList().getMaxPlayers();
    }

    @Override
    public boolean getOnlineMode() {
        return server.usesAuthentication();
    }

    @Override
    public int getPort() {
        return server.getPort();
    }

    @Override
    public String getMotd() {
        return server.getMotd();
    }

    @Override
    public boolean isHardcore() {
        return server.isHardcore();
    }

    @Override
    public GameMode getDefaultGameMode() {
        return GameMode.valueOf(server.getDefaultGameType().name());
    }

    // ---------------------------------------------------------------- mondi

    @Override
    public List<World> getWorlds() {
        List<World> list = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            list.add(wrap(level));
        }
        return list;
    }

    @Override
    public @Nullable World getWorld(String name) {
        for (World w : getWorlds()) {
            if (w.getName().equalsIgnoreCase(name)) return w;
        }
        return null;
    }

    @Override
    public @Nullable World getWorld(UUID uid) {
        for (World w : getWorlds()) {
            if (w.getUID().equals(uid)) return w;
        }
        return null;
    }

    // ---------------------------------------------------------------- servizi

    @Override
    public TesseraPluginManager getPluginManager() {
        return pluginManager;
    }

    @Override
    public TesseraScheduler getScheduler() {
        return scheduler;
    }

    @Override
    public ServicesManager getServicesManager() {
        return servicesManager;
    }

    @Override
    public Messenger getMessenger() {
        return messenger;
    }

    @Override
    public TesseraUnsafe getUnsafe() {
        return unsafe;
    }

    @Override
    public SimpleCommandMap getCommandMap() {
        return commandMap;
    }

    @Override
    public File getPluginsFolder() {
        return config.pluginsDir().toFile();
    }

    @Override
    public File getWorldContainer() {
        return new File(".");
    }

    @Override
    public boolean isPrimaryThread() {
        return server.isSameThread();
    }

    @Override
    public boolean isStopping() {
        return !server.isRunning();
    }

    @Override
    public int getCurrentTick() {
        return server.getTickCount();
    }

    @Override
    public void shutdown() {
        server.halt(false);
    }

    // ---------------------------------------------------------------- comandi

    @Override
    public @Nullable PluginCommand getPluginCommand(String name) {
        Command command = commandMap.getCommand(name);
        return command instanceof PluginCommand pc ? pc : null;
    }

    @Override
    public boolean dispatchCommand(CommandSender sender, String commandLine) throws CommandException {
        if (commandMap.dispatch(sender, commandLine)) {
            return true;
        }
        // Non è un comando Bukkit: lo passiamo al dispatcher vanilla (comandi Minecraft e mod Fabric).
        var source = sender instanceof TesseraPlayer p && p.handle() != null
                ? p.handle().createCommandSourceStack()
                : server.createCommandSourceStack();
        server.getCommands().performPrefixedCommand(source, commandLine);
        return true;
    }

    @Override
    public ConsoleCommandSender getConsoleSender() {
        return console;
    }

    // ---------------------------------------------------------------- messaggi

    @Override
    public Iterable<? extends Audience> audiences() {
        List<Audience> list = new ArrayList<>(getOnlinePlayers());
        list.add(console);
        return list;
    }

    @Override
    public int broadcastMessage(String message) {
        return broadcast(Text.legacy(message));
    }

    @Override
    public int broadcast(String message, String permission) {
        return broadcast(Text.legacy(message), permission);
    }

    @Override
    public int broadcast(Component message) {
        server.getPlayerList().broadcastSystemMessage(Text.toVanilla(message), false);
        console.sendMessage(message);
        return getOnlinePlayers().size();
    }

    @Override
    public int broadcast(Component message, String permission) {
        int count = 0;
        for (Player p : getOnlinePlayers()) {
            if (p.hasPermission(permission)) {
                p.sendMessage(message);
                count++;
            }
        }
        console.sendMessage(message);
        return count;
    }

    public static TesseraServer get() {
        return (TesseraServer) Bukkit.getServer();
    }
}
