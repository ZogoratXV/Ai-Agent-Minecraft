package dev.agentcraft.server.foreman;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Node and Maven for the foreman, downloaded into the plugin folder when the machine does not have
 * them (the Java container of Pelican/Pterodactyl has neither, and no root to install them). Every
 * archive is pinned to a version and checked against its official checksum before it is unpacked;
 * a mismatch is refused.
 */
public final class Runtimes {

    /**
     * A pinned download: official URL, digest algorithm and expected digest, folder inside the
     * archive, and the sub-folder holding the executables ("bin", or "" for Node on Windows).
     */
    public record Archive(String url, String algorithm, String digest, String folder, String binDir) {
        public Archive(String url, String algorithm, String digest, String folder) {
            this(url, algorithm, digest, folder, "bin");
        }
    }

    public static final String NODE_VERSION = "22.23.4";
    public static final String MAVEN_VERSION = "3.9.16";
    private static final long MAX_BYTES = 200L * 1024 * 1024;

    // https://nodejs.org/dist/v22.23.4/SHASUMS256.txt
    private static final Archive NODE_X64 = new Archive(
            "https://nodejs.org/dist/v22.23.4/node-v22.23.4-linux-x64.tar.gz",
            "SHA-256",
            "b8492284c76a54baf58d61668dd0e41ee0fc98adabf9503d30f05d08f55b4406",
            "node-v22.23.4-linux-x64");
    private static final Archive NODE_ARM64 = new Archive(
            "https://nodejs.org/dist/v22.23.4/node-v22.23.4-linux-arm64.tar.gz",
            "SHA-256",
            "f7a1e486dbc8b286847815b477d4fb5e9fb963ed4489c3e1588853d685f589f9",
            "node-v22.23.4-linux-arm64");
    private static final Archive NODE_MAC_X64 = new Archive(
            "https://nodejs.org/dist/v22.23.4/node-v22.23.4-darwin-x64.tar.gz",
            "SHA-256",
            "efcc2b0db6f94bf53eddb038fe2b370277a042bd29e4f19b8f8e23598795b819",
            "node-v22.23.4-darwin-x64");
    private static final Archive NODE_MAC_ARM64 = new Archive(
            "https://nodejs.org/dist/v22.23.4/node-v22.23.4-darwin-arm64.tar.gz",
            "SHA-256",
            "c9e1dbc748b33ed2d14f42445191ecaf14c56259c7a9a26d0042c49ec2b66428",
            "node-v22.23.4-darwin-arm64");
    private static final Archive NODE_WIN_X64 = new Archive(
            "https://nodejs.org/dist/v22.23.4/node-v22.23.4-win-x64.zip",
            "SHA-256",
            "cbc74b5e0982c9dbb41525fd8fb5dcd32fbee237863f7829072f4639a1ce5f20",
            "node-v22.23.4-win-x64",
            "");
    private static final Archive NODE_WIN_ARM64 = new Archive(
            "https://nodejs.org/dist/v22.23.4/node-v22.23.4-win-arm64.zip",
            "SHA-256",
            "a8b8cd79cbbb3062435f449591d98dd63730bbbf30ce2d7cc3a6376c236cb111",
            "node-v22.23.4-win-arm64",
            "");
    // https://repo1.maven.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.tar.gz.sha512
    public static final Archive MAVEN = new Archive(
            "https://repo1.maven.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.tar.gz",
            "SHA-512",
            "831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6",
            "apache-maven-3.9.16");

    private final Path dir;
    private final Logger log;

    public Runtimes(Path dir, Logger log) {
        this.dir = dir.toAbsolutePath();
        this.log = log;
    }

    /** The Node archive for this machine: Linux, macOS and Windows on x64 or arm64. */
    public static Optional<Archive> nodeFor(String os, String arch) {
        String o = os.toLowerCase(Locale.ROOT);
        boolean x64 = switch (arch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> true;
            default -> false;
        };
        boolean arm64 = switch (arch.toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> true;
            default -> false;
        };
        if (!x64 && !arm64) {
            return Optional.empty();
        }
        if (o.contains("linux")) {
            return Optional.of(x64 ? NODE_X64 : NODE_ARM64);
        }
        if (o.contains("mac") || o.contains("darwin")) {
            return Optional.of(x64 ? NODE_MAC_X64 : NODE_MAC_ARM64);
        }
        if (o.contains("windows")) {
            return Optional.of(x64 ? NODE_WIN_X64 : NODE_WIN_ARM64);
        }
        return Optional.empty();
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    /** "node" or "node.exe". */
    public static String nodeExecutable() {
        return windows() ? "node.exe" : "node";
    }

    /** "npm" or "npm.cmd". */
    public static String npmExecutable() {
        return windows() ? "npm.cmd" : "npm";
    }

    /** bin folder of Node, downloading it if needed. */
    public Path ensureNode() throws IOException, InterruptedException {
        Archive a = nodeFor(System.getProperty("os.name"), System.getProperty("os.arch"))
                .orElseThrow(() -> new IOException("no pinned Node build for " + System.getProperty("os.name") + "/"
                        + System.getProperty("os.arch") + ": install Node " + ForemanLauncher.MIN_NODE_MAJOR
                        + "+ and set foreman.node"));
        Path home = ensure(a, "Node " + NODE_VERSION);
        return a.binDir().isEmpty() ? home : home.resolve(a.binDir());
    }

    /** bin folder of Maven, downloading it if needed. Maven needs only Java, which the server has. */
    public Path ensureMaven() throws IOException, InterruptedException {
        return ensure(MAVEN, "Maven " + MAVEN_VERSION).resolve("bin");
    }

    private Path ensure(Archive a, String label) throws IOException, InterruptedException {
        Path target = dir.resolve(a.folder());
        Path bin = a.binDir().isEmpty() ? target : target.resolve(a.binDir());
        if (Files.isDirectory(bin)) {
            return target;
        }
        Files.createDirectories(dir);
        Path tmp = dir.resolve(a.folder() + ".download");
        log.info("downloading " + label + " (first start only)...");
        download(a, tmp);
        Path unpack = Files.createTempDirectory(dir, "unpack-");
        if (a.url().endsWith(".zip")) {
            unzip(tmp, unpack);
        } else {
            Process p = new ProcessBuilder("tar", "-xzf", tmp.toString(), "-C", unpack.toString())
                    .redirectErrorStream(true)
                    .start();
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readNBytes(2000));
            }
            if (!p.waitFor(5, TimeUnit.MINUTES) || p.exitValue() != 0) {
                throw new IOException("cannot unpack " + label + ": " + out.strip());
            }
        }
        Path unpacked = unpack.resolve(a.folder());
        if (!Files.isDirectory(a.binDir().isEmpty() ? unpacked : unpacked.resolve(a.binDir()))) {
            throw new IOException(label + " archive does not contain " + a.folder());
        }
        Files.move(unpacked, target, StandardCopyOption.ATOMIC_MOVE);
        Files.deleteIfExists(tmp);
        deleteTree(unpack);
        log.info(label + " ready in " + target);
        return target;
    }

    /** Streams the archive to {@code to}, computing its digest on the way; deletes it on mismatch. */
    static void download(Archive a, Path to) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpResponse<InputStream> res = http.send(
                HttpRequest.newBuilder(URI.create(a.url()))
                        .timeout(Duration.ofMinutes(10))
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            throw new IOException("download of " + a.url() + " failed: HTTP " + res.statusCode());
        }
        MessageDigest md = digest(a.algorithm());
        long total = 0;
        try (InputStream in = new DigestInputStream(res.body(), md);
                OutputStream out = Files.newOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) {
                    throw new IOException("download of " + a.url() + " is larger than expected");
                }
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            Files.deleteIfExists(to);
            throw e;
        }
        String got = HexFormat.of().formatHex(md.digest());
        if (!got.equalsIgnoreCase(a.digest())) {
            Files.deleteIfExists(to);
            throw new IOException("checksum mismatch for " + a.url() + ": refusing it");
        }
    }

    /** Extracts a zip, refusing entries that would land outside {@code into} ("zip slip"). */
    static void unzip(Path zip, Path into) throws IOException {
        Path root = into.toAbsolutePath().normalize();
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            java.util.zip.ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                Path out = root.resolve(e.getName()).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("unsafe path in archive: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (var walk = Files.walk(p)) {
            for (Path x : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(x);
            }
        }
    }
}
