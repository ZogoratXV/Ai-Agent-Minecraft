package dev.tessera.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Impostazioni del launcher in {@code tessera-launcher.properties}. Ogni chiave si può anche
 * sovrascrivere con una variabile d'ambiente: {@code memory.max} -> {@code TESSERA_MEMORY_MAX}
 * (comodo in Docker e Pterodactyl).
 */
final class LauncherConfig {

    static final String FILE = "tessera-launcher.properties";

    private static final String HEADER = """
             Tessera launcher
             minecraft.version / fabric.*.version: versioni installate (il launcher le scarica se mancano)
             memory.min / memory.max: heap della JVM del server (es. 1G, 4096M)
             jvm.args: argomenti JVM aggiuntivi, separati da spazi
             server.args: argomenti passati al server (nogui consigliato)
             auto-restart: riavvia il server se termina con un errore
             restart.delay-seconds: attesa prima del riavvio
             restart.max-per-hour: oltre questo numero di crash in un'ora il launcher si ferma
             eula: true se accetti la EULA di Minecraft (https://aka.ms/MinecraftEULA)
             Ogni chiave si può sovrascrivere con una variabile d'ambiente TESSERA_<CHIAVE> (es. TESSERA_MEMORY_MAX).""";

    private final Properties props = new Properties();

    static LauncherConfig load(Path dir) throws IOException {
        LauncherConfig config = new LauncherConfig();
        Properties defaults = new Properties();
        try (InputStream in = LauncherConfig.class.getResourceAsStream("/launcher-defaults.properties")) {
            if (in != null) defaults.load(in);
        }
        Properties p = config.props;
        p.setProperty("minecraft.version", defaults.getProperty("minecraft.version", "26.2"));
        p.setProperty("fabric.loader.version", defaults.getProperty("fabric.loader.version", "0.20.1"));
        p.setProperty("fabric.api.version", defaults.getProperty("fabric.api.version", "0.161.0+26.2"));
        p.setProperty("memory.min", "1G");
        p.setProperty("memory.max", "4G");
        p.setProperty("jvm.args", "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200");
        p.setProperty("server.args", "nogui");
        p.setProperty("auto-restart", "true");
        p.setProperty("restart.delay-seconds", "10");
        p.setProperty("restart.max-per-hour", "5");
        p.setProperty("eula", "false");

        Path file = dir.resolve(FILE);
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file)) {
                p.load(r);
            }
        }
        // Le versioni seguono sempre quelle incluse nel launcher, a meno che l'utente le fissi.
        if (!"true".equals(p.getProperty("pin-versions"))) {
            for (String key : List.of("minecraft.version", "fabric.loader.version", "fabric.api.version")) {
                if (defaults.getProperty(key) != null) p.setProperty(key, defaults.getProperty(key));
            }
        }
        config.save(dir);
        return config;
    }

    void save(Path dir) throws IOException {
        try (Writer w = Files.newBufferedWriter(dir.resolve(FILE))) {
            props.store(w, HEADER);
        }
    }

    String get(String key) {
        String env = System.getenv("TESSERA_" + key.toUpperCase().replace('.', '_').replace('-', '_'));
        return env != null && !env.isBlank() ? env.strip() : props.getProperty(key, "").strip();
    }

    void set(String key, String value) {
        props.setProperty(key, value);
    }

    boolean flag(String key) {
        return Boolean.parseBoolean(get(key));
    }

    int integer(String key, int fallback) {
        try {
            return Integer.parseInt(get(key));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    List<String> list(String key) {
        List<String> out = new ArrayList<>();
        for (String s : get(key).split("\\s+")) {
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }
}
