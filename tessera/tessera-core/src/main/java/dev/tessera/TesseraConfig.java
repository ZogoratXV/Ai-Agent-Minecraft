package dev.tessera;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Configurazione di Tessera in config/tessera.properties (creata al primo avvio). */
public record TesseraConfig(Path pluginsDir, boolean strictApi, boolean bridgeJoinQuitMessages, boolean logUnsupported) {

    private static final String HEADER = """
            Tessera - configurazione
            plugins-dir: cartella dei plugin Bukkit/Paper
            strict-api: true = un metodo API non implementato lancia un'eccezione,
                        false = registra un avviso e restituisce un valore neutro
            bridge-join-quit-messages: lascia ai plugin il controllo dei messaggi di entrata/uscita""";

    public static TesseraConfig load(Path file) {
        Properties p = new Properties();
        p.setProperty("plugins-dir", "plugins");
        p.setProperty("strict-api", "false");
        p.setProperty("bridge-join-quit-messages", "true");
        p.setProperty("log-unsupported", "true");
        try {
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file)) {
                    p.load(r);
                }
            }
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                p.store(w, HEADER);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Impossibile leggere " + file, e);
        }
        return new TesseraConfig(
                Path.of(p.getProperty("plugins-dir")),
                Boolean.parseBoolean(p.getProperty("strict-api")),
                Boolean.parseBoolean(p.getProperty("bridge-join-quit-messages")),
                Boolean.parseBoolean(p.getProperty("log-unsupported")));
    }
}
