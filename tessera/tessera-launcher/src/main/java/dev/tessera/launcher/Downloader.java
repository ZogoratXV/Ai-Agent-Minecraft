package dev.tessera.launcher;

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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/** Download HTTP con verifica SHA-1 opzionale e scrittura atomica. */
final class Downloader {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    String text(String url) throws IOException {
        HttpResponse<String> r = send(url, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return r.body();
    }

    /** Scarica {@code url} in {@code target} se manca o se lo SHA-1 non corrisponde. */
    void file(String url, Path target, String sha1) throws IOException {
        if (Files.exists(target) && (sha1 == null || sha1.equalsIgnoreCase(sha1(target)))) {
            return;
        }
        Files.createDirectories(target.getParent());
        System.out.println("[Tessera] Download " + target.getFileName() + " ...");
        Path tmp = Files.createTempFile(target.getParent(), ".download-", ".part");
        try {
            HttpResponse<InputStream> r = send(url, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (sha1 != null && !sha1.equalsIgnoreCase(sha1(tmp))) {
                throw new IOException("Checksum SHA-1 errato per " + url);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private <T> HttpResponse<T> send(String url, HttpResponse.BodyHandler<T> handler) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(5))
                        .header("User-Agent", "Tessera-Launcher")
                        .build();
                HttpResponse<T> r = http.send(req, handler);
                if (r.statusCode() == 200) return r;
                last = new IOException("HTTP " + r.statusCode() + " da " + url);
                if (r.statusCode() == 404) break;
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrotto", e);
            }
            try {
                Thread.sleep(1000L * attempt * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw last;
    }

    static String sha1(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
