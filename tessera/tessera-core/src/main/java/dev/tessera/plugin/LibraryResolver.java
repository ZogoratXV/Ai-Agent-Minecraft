package dev.tessera.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.plugin.PluginDescriptionFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supporto minimo per la sezione {@code libraries:} di plugin.yml: scarica ogni coordinata
 * {@code gruppo:artefatto:versione} da Maven Central in {@code libraries/}. A differenza di Paper
 * NON risolve le dipendenze transitive: vanno elencate esplicitamente.
 */
final class LibraryResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/Libraries");
    private static final String REPO = System.getProperty("tessera.mavenRepo", "https://repo.maven.apache.org/maven2/");

    static List<URL> resolve(PluginDescriptionFile desc, Path librariesDir) throws IOException {
        List<URL> urls = new ArrayList<>();
        if (desc.getLibraries().isEmpty()) return urls;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        for (String coordinate : desc.getLibraries()) {
            String[] parts = coordinate.split(":");
            if (parts.length < 3) {
                LOGGER.warn("{}: libreria non valida '{}'", desc.getName(), coordinate);
                continue;
            }
            String path = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + parts[1] + "-" + parts[2] + ".jar";
            Path target = librariesDir.resolve(path);
            if (!Files.exists(target)) {
                LOGGER.info("{}: scarico la libreria {}", desc.getName(), coordinate);
                Files.createDirectories(target.getParent());
                try {
                    HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(REPO + path)).build(),
                            HttpResponse.BodyHandlers.ofInputStream());
                    if (response.statusCode() != 200) {
                        throw new IOException("HTTP " + response.statusCode() + " per " + coordinate);
                    }
                    Path tmp = Files.createTempFile(target.getParent(), "dl", ".part");
                    try (InputStream in = response.body()) {
                        Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Download interrotto", e);
                }
            }
            urls.add(target.toUri().toURL());
        }
        return urls;
    }

    private LibraryResolver() {
    }
}
