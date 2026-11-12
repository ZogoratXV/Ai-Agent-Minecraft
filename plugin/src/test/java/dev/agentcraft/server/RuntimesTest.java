package dev.agentcraft.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.agentcraft.server.foreman.Runtimes;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimesTest {

    private static final byte[] BODY = "pretend this is node.tar.gz".getBytes(StandardCharsets.UTF_8);
    private HttpServer http;

    @BeforeEach
    void serve() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/a.tar.gz", ex -> {
            ex.sendResponseHeaders(200, BODY.length);
            ex.getResponseBody().write(BODY);
            ex.close();
        });
        http.start();
    }

    @AfterEach
    void stop() {
        http.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/a.tar.gz";
    }

    private static void download(Runtimes.Archive a, Path to) throws Exception {
        Method m = Runtimes.class.getDeclaredMethod("download", Runtimes.Archive.class, Path.class);
        m.setAccessible(true);
        try {
            m.invoke(null, a, to);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void acceptsAnArchiveWithTheExpectedChecksum(@TempDir Path dir) throws Exception {
        String sha =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BODY));
        Path to = dir.resolve("a.tar.gz");
        download(new Runtimes.Archive(url(), "SHA-256", sha, "a"), to);
        assertEquals(BODY.length, Files.size(to));
    }

    @Test
    void refusesAndDeletesAnArchiveWithAnotherChecksum(@TempDir Path dir) {
        Path to = dir.resolve("a.tar.gz");
        IOException e = assertThrows(
                IOException.class, () -> download(new Runtimes.Archive(url(), "SHA-256", "00".repeat(32), "a"), to));
        assertTrue(e.getMessage().contains("checksum mismatch"));
        assertFalse(Files.exists(to), "a rejected download must not stay on disk");
    }

    @Test
    void unzipRefusesPathsOutsideTheTarget(@TempDir Path dir) throws Exception {
        Path zip = dir.resolve("evil.zip");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("../escaped.txt"));
            out.write(1);
            out.closeEntry();
        }
        Method m = Runtimes.class.getDeclaredMethod("unzip", Path.class, Path.class);
        m.setAccessible(true);
        Path into = Files.createDirectories(dir.resolve("into"));
        var e = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> m.invoke(null, zip, into));
        assertTrue(e.getCause().getMessage().contains("unsafe path"));
        assertFalse(Files.exists(dir.resolve("escaped.txt")));
    }

    @Test
    void picksThePinnedNodeBuildOnlyForLinuxX64AndArm64() {
        assertTrue(Runtimes.nodeFor("Linux", "amd64").orElseThrow().url().contains("linux-x64"));
        assertTrue(Runtimes.nodeFor("Linux", "aarch64").orElseThrow().url().contains("linux-arm64"));
        assertTrue(Runtimes.nodeFor("Windows 11", "amd64").orElseThrow().url().endsWith("win-x64.zip"));
        assertEquals("", Runtimes.nodeFor("Windows 11", "amd64").orElseThrow().binDir());
        assertTrue(Runtimes.nodeFor("Mac OS X", "aarch64").orElseThrow().url().contains("darwin-arm64"));
        assertTrue(Runtimes.nodeFor("Linux", "riscv64").isEmpty());
        assertEquals("SHA-512", Runtimes.MAVEN.algorithm());
    }

    private static byte[] header(String name, char type, long size, int mode, String link) {
        byte[] h = new byte[512];
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(n, 0, h, 0, Math.min(100, n.length));
        byte[] m = String.format("%07o\0", mode).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(m, 0, h, 100, m.length);
        byte[] sz = String.format("%011o\0", size).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(sz, 0, h, 124, sz.length);
        h[156] = (byte) type;
        byte[] l = link.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(l, 0, h, 157, l.length);
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 6);
        return h;
    }

    private static void entry(java.io.OutputStream out, String name, char type, byte[] data, int mode, String link)
            throws IOException {
        out.write(header(name, type, data.length, mode, link));
        out.write(data);
        out.write(new byte[(512 - data.length % 512) % 512]);
    }

    private static Path tarGz(Path dir, java.util.function.Consumer<java.io.OutputStream> body) throws IOException {
        Path file = dir.resolve("a.tar.gz");
        try (java.io.OutputStream out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(file))) {
            body.accept(out);
            out.write(new byte[1024]);
        }
        return file;
    }

    @Test
    void untarsLongNamesExecutablesAndLinks(@TempDir Path dir) throws Exception {
        String longName = "node/lib/node_modules/npm/" + "x".repeat(120) + "/file.js";
        String pax = "path=" + longName + "\n";
        String rec = (pax.length() + 4) + " " + pax;
        rec = (rec.length()) + " " + pax;
        String record = rec;
        Path archive = tarGz(dir, out -> {
            try {
                entry(out, "node/", '5', new byte[0], 0755, "");
                entry(out, "node/bin/node", '0', "bin".getBytes(StandardCharsets.UTF_8), 0755, "");
                entry(out, "node/bin/npm", '2', new byte[0], 0777, "../lib/npm-cli.js");
                entry(out, "PaxHeader", 'x', record.getBytes(StandardCharsets.UTF_8), 0644, "");
                entry(out, "ignored", '0', "deep".getBytes(StandardCharsets.UTF_8), 0644, "");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        Path into = Files.createDirectory(dir.resolve("out"));
        Runtimes.untarGz(archive, into);
        assertEquals("bin", Files.readString(into.resolve("node/bin/node")));
        assertTrue(Files.isExecutable(into.resolve("node/bin/node")));
        assertEquals("deep", Files.readString(into.resolve(longName)));
        assertTrue(Files.isSymbolicLink(into.resolve("node/bin/npm")));
    }

    @Test
    void untarRefusesPathsOutsideTheTarget(@TempDir Path dir) throws Exception {
        Path archive = tarGz(dir, out -> {
            try {
                entry(out, "../evil.txt", '0', "x".getBytes(StandardCharsets.UTF_8), 0644, "");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        Path into = Files.createDirectory(dir.resolve("out"));
        assertThrows(IOException.class, () -> Runtimes.untarGz(archive, into));
        assertFalse(Files.exists(dir.resolve("evil.txt")));
    }
}
