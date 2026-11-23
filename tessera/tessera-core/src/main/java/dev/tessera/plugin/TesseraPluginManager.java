package dev.tessera.plugin;

import dev.tessera.stub.StubPluginManager;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import org.bukkit.Server;
import org.bukkit.command.PluginCommandYamlParser;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoadOrder;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gestore dei plugin: scoperta dei jar, ordine di caricamento per dipendenze, abilitazione,
 * eventi e permessi. Riprende la logica di SimplePluginManager di Bukkit (che in Paper delega al
 * server e quindi non è riutilizzabile così com'è).
 */
public final class TesseraPluginManager extends StubPluginManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/Plugins");

    private final Server server;
    private final SimpleCommandMap commandMap;
    private final File directory;
    private final Map<String, JavaPlugin> plugins = new LinkedHashMap<>();
    private final List<TesseraPluginClassLoader> loaders = new CopyOnWriteArrayList<>();

    private final Map<String, Permission> permissions = new HashMap<>();
    private final Map<Boolean, Set<Permission>> defaultPerms = new HashMap<>();
    private final Map<String, Map<Permissible, Boolean>> permSubs = new HashMap<>();
    private final Map<Boolean, Map<Permissible, Boolean>> defSubs = new HashMap<>();

    public TesseraPluginManager(Server server, SimpleCommandMap commandMap, File directory) {
        this.server = server;
        this.commandMap = commandMap;
        this.directory = directory;
        defaultPerms.put(true, new LinkedHashSet<>());
        defaultPerms.put(false, new LinkedHashSet<>());
    }

    @Override
    protected String tesseraStubName() {
        return "PluginManager";
    }

    // ================================================================ caricamento

    private record Candidate(File file, PluginDescriptionFile description) {
    }

    public void loadAll() {
        try {
            Files.createDirectories(directory.toPath());
        } catch (IOException e) {
            LOGGER.error("Impossibile creare la cartella {}", directory, e);
            return;
        }
        Plugin[] loaded = loadPlugins(directory);
        LOGGER.info("Caricati {} plugin da {}", loaded.length, directory);
    }

    @Override
    public Plugin[] loadPlugins(File dir) {
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (files == null) return new Plugin[0];
        Arrays.sort(files);
        return loadPlugins(files);
    }

    @Override
    public Plugin[] loadPlugins(File[] files) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (File file : files) {
            try {
                PluginDescriptionFile desc = readDescription(file);
                if (desc == null) {
                    LOGGER.warn("{}: nessun plugin.yml, ignorato (se è una mod Fabric va in mods/)", file.getName());
                    continue;
                }
                Candidate prev = candidates.putIfAbsent(desc.getName().toLowerCase(Locale.ROOT), new Candidate(file, desc));
                if (prev != null) {
                    LOGGER.error("Plugin duplicato '{}' in {} e {}", desc.getName(), prev.file().getName(), file.getName());
                }
            } catch (Exception e) {
                LOGGER.error("Descrizione non valida in {}", file.getName(), e);
            }
        }

        List<Plugin> result = new ArrayList<>();
        for (Candidate c : sortByDependencies(candidates)) {
            try {
                JavaPlugin plugin = loadPlugin(c);
                result.add(plugin);
            } catch (Throwable t) {
                LOGGER.error("Impossibile caricare {} ({})", c.description().getFullName(), c.file().getName(), t);
            }
        }
        return result.toArray(new Plugin[0]);
    }

    @Override
    public @Nullable Plugin loadPlugin(File file) {
        try {
            PluginDescriptionFile desc = readDescription(file);
            return desc == null ? null : loadPlugin(new Candidate(file, desc));
        } catch (Exception e) {
            throw new IllegalArgumentException("Impossibile caricare " + file, e);
        }
    }

    private static @Nullable PluginDescriptionFile readDescription(File file) throws IOException, InvalidDescriptionException {
        try (JarFile jar = new JarFile(file)) {
            ZipEntry entry = jar.getEntry("plugin.yml");
            if (entry == null) {
                // I "paper plugin" usano paper-plugin.yml: i campi base (name, version, main) sono compatibili.
                entry = jar.getEntry("paper-plugin.yml");
            }
            if (entry == null) return null;
            try (InputStream in = jar.getInputStream(entry)) {
                return new PluginDescriptionFile(in);
            }
        }
    }

    /** Ordine topologico: prima le dipendenze (depend/softdepend), rispettando loadbefore. */
    private List<Candidate> sortByDependencies(Map<String, Candidate> candidates) {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (var e : candidates.entrySet()) {
            Set<String> deps = new LinkedHashSet<>();
            PluginDescriptionFile d = e.getValue().description();
            for (String dep : d.getDepend()) {
                String key = dep.toLowerCase(Locale.ROOT);
                if (!candidates.containsKey(key)) {
                    LOGGER.error("{} richiede il plugin '{}' che non è presente: non verrà caricato", d.getName(), dep);
                    deps.add("\0missing");
                }
                deps.add(key);
            }
            for (String dep : d.getSoftDepend()) deps.add(dep.toLowerCase(Locale.ROOT));
            edges.put(e.getKey(), deps);
        }
        for (var e : candidates.entrySet()) {
            for (String before : e.getValue().description().getLoadBefore()) {
                Set<String> deps = edges.get(before.toLowerCase(Locale.ROOT));
                if (deps != null) deps.add(e.getKey());
            }
        }
        List<Candidate> ordered = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String key : candidates.keySet()) {
            visit(key, candidates, edges, done, visiting, ordered);
        }
        return ordered;
    }

    private void visit(String key, Map<String, Candidate> candidates, Map<String, Set<String>> edges,
            Set<String> done, Set<String> visiting, List<Candidate> out) {
        if (done.contains(key) || !candidates.containsKey(key)) return;
        if (!visiting.add(key)) {
            LOGGER.warn("Dipendenza circolare che coinvolge '{}'", key);
            return;
        }
        Set<String> deps = edges.getOrDefault(key, Set.of());
        if (deps.contains("\0missing")) {
            visiting.remove(key);
            done.add(key);
            return;
        }
        for (String dep : deps) visit(dep, candidates, edges, done, visiting, out);
        visiting.remove(key);
        done.add(key);
        out.add(candidates.get(key));
    }

    private JavaPlugin loadPlugin(Candidate c) throws Exception {
        PluginDescriptionFile desc = c.description();
        File dataFolder = new File(directory, desc.getName());
        List<URL> libraries = LibraryResolver.resolve(desc, directory.toPath().getParent() == null
                ? Path.of("libraries") : directory.toPath().getParent().resolve("libraries"));
        TesseraPluginClassLoader loader = new TesseraPluginClassLoader(
                TesseraPluginManager.class.getClassLoader(), this, server, desc, c.file(), dataFolder, libraries);
        loaders.add(loader);
        JavaPlugin plugin;
        try {
            plugin = loader.instantiate();
        } catch (Exception e) {
            loaders.remove(loader);
            loader.close();
            throw e;
        }
        plugins.put(desc.getName().toLowerCase(Locale.ROOT), plugin);
        LOGGER.info("Caricamento di {}", desc.getFullName());
        plugin.onLoad();
        return plugin;
    }

    @Nullable Class<?> findClassInOtherPlugins(String name, TesseraPluginClassLoader requester) {
        for (TesseraPluginClassLoader loader : loaders) {
            if (loader == requester) continue;
            Class<?> c = loader.findLocal(name);
            if (c != null) return c;
        }
        return null;
    }

    // ================================================================ abilitazione

    public void enableAll() {
        // Bukkit abilita prima i plugin STARTUP e poi i POSTWORLD: qui i mondi sono già pronti.
        for (PluginLoadOrder order : PluginLoadOrder.values()) {
            for (JavaPlugin plugin : List.copyOf(plugins.values())) {
                if (plugin.getDescription().getLoad() == order) enablePlugin(plugin);
            }
        }
    }

    @Override
    public void enablePlugin(Plugin plugin) {
        if (plugin.isEnabled() || !(plugin instanceof JavaPlugin jp)) return;
        PluginDescriptionFile desc = jp.getDescription();
        LOGGER.info("Abilitazione di {}", desc.getFullName());
        for (Permission perm : desc.getPermissions()) {
            try {
                addPermission(perm, false);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("{}: permesso già registrato {}", desc.getName(), perm.getName());
            }
        }
        dirtyPermissibles();
        commandMap.registerAll(desc.getName(), PluginCommandYamlParser.parse(jp));
        try {
            jp.setEnabled(true);
        } catch (Throwable t) {
            LOGGER.error("Errore durante l'abilitazione di {}: il plugin viene disabilitato", desc.getFullName(), t);
            disablePlugin(plugin);
        }
    }

    @Override
    public void disablePlugins() {
        List<JavaPlugin> list = new ArrayList<>(plugins.values());
        Collections.reverse(list);
        for (JavaPlugin plugin : list) disablePlugin(plugin);
    }

    @Override
    public void disablePlugin(Plugin plugin) {
        if (!plugin.isEnabled() || !(plugin instanceof JavaPlugin jp)) return;
        LOGGER.info("Disabilitazione di {}", jp.getDescription().getFullName());
        try {
            jp.setEnabled(false);
        } catch (Throwable t) {
            LOGGER.error("Errore durante la disabilitazione di {}", jp.getName(), t);
        }
        server.getScheduler().cancelTasks(plugin);
        server.getServicesManager().unregisterAll(plugin);
        server.getMessenger().unregisterIncomingPluginChannel(plugin);
        server.getMessenger().unregisterOutgoingPluginChannel(plugin);
        HandlerList.unregisterAll(plugin);
    }

    @Override
    public void clearPlugins() {
        disablePlugins();
        plugins.clear();
        for (TesseraPluginClassLoader loader : loaders) {
            try {
                loader.close();
            } catch (IOException ignored) {
                // niente da fare
            }
        }
        loaders.clear();
        HandlerList.unregisterAll();
        permissions.clear();
        defaultPerms.get(true).clear();
        defaultPerms.get(false).clear();
    }

    @Override
    public @Nullable Plugin getPlugin(String name) {
        return plugins.get(name.replace(' ', '_').toLowerCase(Locale.ROOT));
    }

    @Override
    public Plugin[] getPlugins() {
        return plugins.values().toArray(new Plugin[0]);
    }

    @Override
    public boolean isPluginEnabled(String name) {
        Plugin p = getPlugin(name);
        return p != null && p.isEnabled();
    }

    @Override
    public boolean isPluginEnabled(@Nullable Plugin plugin) {
        return plugin != null && plugins.containsValue(plugin) && plugin.isEnabled();
    }

    @Override
    public boolean useTimings() {
        return false;
    }

    @Override
    public boolean isTransitiveDependency(io.papermc.paper.plugin.configuration.PluginMeta plugin,
            io.papermc.paper.plugin.configuration.PluginMeta dependency) {
        return plugin.getPluginDependencies().contains(dependency.getName())
                || plugin.getPluginSoftDependencies().contains(dependency.getName());
    }

    // ================================================================ eventi

    @Override
    public void callEvent(Event event) {
        HandlerList handlers = event.getHandlers();
        for (RegisteredListener listener : handlers.getRegisteredListeners()) {
            if (!listener.getPlugin().isEnabled()) continue;
            try {
                listener.callEvent(event);
            } catch (Throwable t) {
                listener.getPlugin().getLogger().log(Level.SEVERE,
                        "Errore nel listener di " + event.getEventName() + " (" + listener.getListener().getClass().getName() + ")", t);
            }
        }
    }

    @Override
    public void registerEvents(Listener listener, Plugin plugin) {
        if (!plugin.isEnabled()) {
            throw new IllegalPluginAccessException("Il plugin " + plugin.getName() + " non è abilitato");
        }
        Set<Method> methods = new LinkedHashSet<>();
        try {
            methods.addAll(Arrays.asList(listener.getClass().getMethods()));
            methods.addAll(Arrays.asList(listener.getClass().getDeclaredMethods()));
        } catch (NoClassDefFoundError e) {
            plugin.getLogger().severe("Impossibile registrare il listener " + listener.getClass().getName()
                    + ": classe mancante " + e.getMessage());
            return;
        }
        for (Method method : methods) {
            EventHandler eh = method.getAnnotation(EventHandler.class);
            if (eh == null || method.isBridge() || method.isSynthetic()) continue;
            if (method.getParameterCount() != 1 || !Event.class.isAssignableFrom(method.getParameterTypes()[0])) {
                plugin.getLogger().severe("Firma @EventHandler non valida: " + method);
                continue;
            }
            Class<? extends Event> eventClass = method.getParameterTypes()[0].asSubclass(Event.class);
            EventExecutor executor = EventExecutor.create(method, eventClass);
            handlerList(eventClass).register(new RegisteredListener(listener, executor, eh.priority(), plugin, eh.ignoreCancelled()));
        }
    }

    @Override
    public void registerEvent(Class event, Listener listener, EventPriority priority, EventExecutor executor, Plugin plugin) {
        registerEvent(event, listener, priority, executor, plugin, false);
    }

    @Override
    public void registerEvent(Class event, Listener listener, EventPriority priority, EventExecutor executor, Plugin plugin,
            boolean ignoreCancelled) {
        if (!plugin.isEnabled()) {
            throw new IllegalPluginAccessException("Il plugin " + plugin.getName() + " non è abilitato");
        }
        @SuppressWarnings("unchecked")
        Class<? extends Event> type = (Class<? extends Event>) event;
        handlerList(type).register(new RegisteredListener(listener, executor, priority, plugin, ignoreCancelled));
    }

    private static HandlerList handlerList(Class<? extends Event> type) {
        Class<?> clazz = type;
        while (clazz != null && Event.class.isAssignableFrom(clazz)) {
            try {
                Method m = clazz.getDeclaredMethod("getHandlerList");
                if (Modifier.isStatic(m.getModifiers())) {
                    m.setAccessible(true);
                    return (HandlerList) m.invoke(null);
                }
            } catch (NoSuchMethodException ignored) {
                // risali alla superclasse
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("getHandlerList non accessibile in " + clazz.getName(), e);
            }
            clazz = clazz.getSuperclass();
        }
        throw new IllegalArgumentException("Nessun getHandlerList statico per " + type.getName());
    }

    // ================================================================ permessi

    @Override
    public @Nullable Permission getPermission(String name) {
        return permissions.get(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public void addPermission(Permission perm) {
        addPermission(perm, true);
    }

    private void addPermission(Permission perm, boolean dirty) {
        String name = perm.getName().toLowerCase(Locale.ROOT);
        if (permissions.containsKey(name)) {
            throw new IllegalArgumentException("Il permesso " + name + " esiste già");
        }
        permissions.put(name, perm);
        calculatePermissionDefault(perm, dirty);
    }

    @Override
    public Set<Permission> getDefaultPermissions(boolean op) {
        return Set.copyOf(defaultPerms.get(op));
    }

    @Override
    public void removePermission(Permission perm) {
        removePermission(perm.getName());
    }

    @Override
    public void removePermission(String name) {
        permissions.remove(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public void recalculatePermissionDefaults(Permission perm) {
        if (permissions.containsKey(perm.getName().toLowerCase(Locale.ROOT))) {
            defaultPerms.get(true).remove(perm);
            defaultPerms.get(false).remove(perm);
            calculatePermissionDefault(perm, true);
        }
    }

    private void calculatePermissionDefault(Permission perm, boolean dirty) {
        PermissionDefault def = perm.getDefault();
        if (def == PermissionDefault.OP || def == PermissionDefault.TRUE) {
            defaultPerms.get(true).add(perm);
            if (dirty) dirtyPermissibles(true);
        }
        if (def == PermissionDefault.NOT_OP || def == PermissionDefault.TRUE) {
            defaultPerms.get(false).add(perm);
            if (dirty) dirtyPermissibles(false);
        }
    }

    private void dirtyPermissibles() {
        dirtyPermissibles(true);
        dirtyPermissibles(false);
    }

    private void dirtyPermissibles(boolean op) {
        for (Permissible p : getDefaultPermSubscriptions(op)) p.recalculatePermissions();
    }

    @Override
    public void subscribeToPermission(String permission, Permissible permissible) {
        permSubs.computeIfAbsent(permission.toLowerCase(Locale.ROOT), k -> new WeakHashMap<>()).put(permissible, true);
    }

    @Override
    public void unsubscribeFromPermission(String permission, Permissible permissible) {
        Map<Permissible, Boolean> map = permSubs.get(permission.toLowerCase(Locale.ROOT));
        if (map != null) {
            map.remove(permissible);
            if (map.isEmpty()) permSubs.remove(permission.toLowerCase(Locale.ROOT));
        }
    }

    @Override
    public Set<Permissible> getPermissionSubscriptions(String permission) {
        Map<Permissible, Boolean> map = permSubs.get(permission.toLowerCase(Locale.ROOT));
        return map == null ? Set.of() : Set.copyOf(map.keySet());
    }

    @Override
    public void subscribeToDefaultPerms(boolean op, Permissible permissible) {
        defSubs.computeIfAbsent(op, k -> new WeakHashMap<>()).put(permissible, true);
    }

    @Override
    public void unsubscribeFromDefaultPerms(boolean op, Permissible permissible) {
        Map<Permissible, Boolean> map = defSubs.get(op);
        if (map != null) map.remove(permissible);
    }

    @Override
    public Set<Permissible> getDefaultPermSubscriptions(boolean op) {
        Map<Permissible, Boolean> map = defSubs.get(op);
        return map == null ? Set.of() : Set.copyOf(map.keySet());
    }

    @Override
    public Set<Permission> getPermissions() {
        return new HashSet<>(permissions.values());
    }
}
