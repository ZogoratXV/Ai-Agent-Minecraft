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
}
