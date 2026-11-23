package dev.tessera.plugin;

import com.destroystokyo.paper.utils.PaperPluginLogger;
import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import io.papermc.paper.plugin.provider.classloader.PluginClassLoaderGroup;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import org.bukkit.Server;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Un classloader per plugin. Ordine di ricerca: classi di Minecraft/Fabric/API (genitore), il jar
 * del plugin, le sue librerie, poi le classi degli altri plugin (per le dipendenze tra plugin).
 */
public final class TesseraPluginClassLoader extends URLClassLoader implements ConfiguredPluginClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final TesseraPluginManager manager;
    private final PluginDescriptionFile description;
    private final File file;
    private final File dataFolder;
    private final Server server;
    private @Nullable JavaPlugin plugin;

    TesseraPluginClassLoader(ClassLoader parent, TesseraPluginManager manager, Server server, PluginDescriptionFile description,
            File file, File dataFolder, List<URL> libraries) throws IOException {
        super("plugin/" + description.getName(), urls(file, libraries), parent);
        this.manager = manager;
        this.server = server;
        this.description = description;
        this.file = file;
        this.dataFolder = dataFolder;
    }

    private static URL[] urls(File file, List<URL> libraries) throws IOException {
        URL[] urls = new URL[libraries.size() + 1];
        urls[0] = file.toURI().toURL();
        for (int i = 0; i < libraries.size(); i++) urls[i + 1] = libraries.get(i);
        return urls;
    }

    JavaPlugin instantiate() throws InvalidPluginException {
        try {
            Class<?> main = Class.forName(description.getMain(), true, this);
            Class<? extends JavaPlugin> pluginClass = main.asSubclass(JavaPlugin.class);
            // Il costruttore di JavaPlugin chiama init(this) qui sotto.
            return pluginClass.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new InvalidPluginException("Classe principale '" + description.getMain() + "' non trovata", e);
        } catch (ClassCastException e) {
            throw new InvalidPluginException("'" + description.getMain() + "' non estende JavaPlugin", e);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new InvalidPluginException("Impossibile istanziare " + description.getMain(), e);
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        return loadClass(name, resolve, true, true);
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve, boolean checkGlobal, boolean checkLibraries) throws ClassNotFoundException {
        try {
            return super.loadClass(name, resolve);
        } catch (ClassNotFoundException e) {
            if (checkGlobal) {
                Class<?> other = manager.findClassInOtherPlugins(name, this);
                if (other != null) return other;
            }
            throw e;
        }
    }

    /** Cerca solo dentro questo plugin (usato dagli altri classloader). */
    @Nullable Class<?> findLocal(String name) {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c != null) return c;
            try {
                return findClass(name);
            } catch (ClassNotFoundException | LinkageError e) {
                return null;
            }
        }
    }

    @Override
    public PluginMeta getConfiguration() {
        return description;
    }

    @Override
    public void init(JavaPlugin javaPlugin) {
        if (this.plugin != null) {
            throw new IllegalStateException("Il plugin " + description.getName() + " è già stato inizializzato");
        }
        this.plugin = javaPlugin;
        javaPlugin.init(server, description, dataFolder, file, this, description, PaperPluginLogger.getLogger(description));
    }

    @Override
    public @Nullable JavaPlugin getPlugin() {
        return plugin;
    }

    @Override
    public @Nullable PluginClassLoaderGroup getGroup() {
        return null;
    }

    PluginDescriptionFile description() {
        return description;
    }
}
