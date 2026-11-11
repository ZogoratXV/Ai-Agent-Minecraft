package dev.agentcraft.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.server.foreman.ForemanLauncher;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForemanLauncherTest {

    @Test
    void parsesNodeVersions() {
        assertEquals(Optional.of(22), ForemanLauncher.nodeMajor("v22.23.4\n"));
        assertEquals(Optional.of(24), ForemanLauncher.nodeMajor("v24.1.0"));
        assertEquals(Optional.empty(), ForemanLauncher.nodeMajor("bash: node: command not found"));
    }

    @Test
    void buildsTheCommandLineWithoutTheToken(@TempDir Path dir) {
        var o = new ForemanLauncher.Options(
                "node",
                "npm",
                "sim",
                URI.create("ws://127.0.0.1:7999"),
                List.of("--step-ms", "400"),
                dir,
                "secret-token-1234567",
                null);
        List<String> cmd = ForemanLauncher.command(o, dir.resolve("foreman.mjs"));
        assertEquals(
                List.of(
                        "node",
                        dir.resolve("foreman.mjs").toString(),
                        "--backend",
                        "sim",
                        "--host",
                        "127.0.0.1",
                        "--port",
                        "7999",
                        "--home",
                        dir.resolve("home").toString(),
                        "--step-ms",
                        "400"),
                cmd);
        assertFalse(String.join(" ", cmd).contains("secret"));
    }

    @Test
    void createsAPrivateRandomToken(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("token.txt");
        String a = ForemanLauncher.createTokenFile(f);
        String b = ForemanLauncher.createTokenFile(dir.resolve("other.txt"));
        assertEquals(48, a.length());
        assertTrue(!a.equals(b));
        assertEquals(a, Files.readString(f).strip());
        assertEquals(
                "rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
    }
}
