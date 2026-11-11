package dev.agentcraft.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.server.config.PluginSettings;
import dev.agentcraft.server.config.ServerRole;
import dev.agentcraft.server.config.SettingsException;
import dev.agentcraft.server.config.SettingsLoader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SettingsLoaderTest {

    private static YamlConfiguration defaults() {
        var in = Objects.requireNonNull(SettingsLoaderTest.class.getResourceAsStream("/config.yml"));
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    @Test
    void shippedConfigIsValid() {
        PluginSettings s = SettingsLoader.load(defaults());
        assertEquals(ServerRole.STAGING, s.role());
        assertEquals("ws://127.0.0.1:7878", s.bridgeUri().toString());
        assertEquals(200, s.blocksPerTick());
        assertTrue(s.blockBlacklist().contains("minecraft:tnt"));
        assertTrue(s.blockBlacklist().contains("minecraft:command_block"));
        assertTrue(s.commandWhitelist().contains("time query"));
    }

    @Test
    void refusesPublicBridgeAddressesAndNames() {
        for (String url : new String[] {"ws://8.8.8.8:7878", "ws://example.com:7878", "ws://0.0.0.0:7878"}) {
            YamlConfiguration c = defaults();
            c.set("bridge.url", url);
            assertThrows(SettingsException.class, () -> SettingsLoader.load(c), url);
        }
        YamlConfiguration c = defaults();
        c.set("bridge.url", "ws://192.168.1.10:7878");
        c.set("foreman.managed", false);
        assertEquals("192.168.1.10", SettingsLoader.load(c).bridgeUri().getHost());
    }

    @Test
    void classifiesHosts() {
        assertTrue(SettingsLoader.isPrivateHost("127.0.0.1"));
        assertTrue(SettingsLoader.isPrivateHost("localhost"));
        assertTrue(SettingsLoader.isPrivateHost("[::1]"));
        assertTrue(SettingsLoader.isPrivateHost("10.0.0.4"));
        assertTrue(SettingsLoader.isPrivateHost("fd12::1"));
        assertFalse(SettingsLoader.isPrivateHost("1.1.1.1"));
        assertFalse(SettingsLoader.isPrivateHost("evil.example"));
    }

    @Test
    void reportsEveryProblemAtOnce() {
        YamlConfiguration c = defaults();
        c.set("server.role", "prod");
        c.set("world.blocks-per-tick", 0);
        c.set("bridge.url", "http://127.0.0.1:7878");
        SettingsException e = assertThrows(SettingsException.class, () -> SettingsLoader.load(c));
        assertEquals(3, e.problems().size(), e.problems().toString());
    }

    @Test
    void managedForemanDefaultsAndChecks() {
        PluginSettings s = SettingsLoader.load(defaults());
        assertTrue(s.foreman().managed());
        assertEquals("sim", s.foreman().backend());

        YamlConfiguration lan = defaults();
        lan.set("bridge.url", "ws://192.168.1.10:7878");
        assertThrows(SettingsException.class, () -> SettingsLoader.load(lan), "managed foreman must be local");
        lan.set("foreman.managed", false);
        assertEquals("192.168.1.10", SettingsLoader.load(lan).bridgeUri().getHost());

        YamlConfiguration bad = defaults();
        bad.set("foreman.backend", "gpt");
        bad.set("foreman.extra-args", java.util.List.of("--port", "9"));
        assertEquals(
                2,
                assertThrows(SettingsException.class, () -> SettingsLoader.load(bad))
                        .problems()
                        .size());
    }

    @Test
    void apiKeyAndSubscriptionTokenAreExclusive() {
        YamlConfiguration c = defaults();
        c.set("foreman.claude-token-file", "claude.token");
        assertEquals("claude.token", SettingsLoader.load(c).foreman().claudeTokenFile());
        c.set("foreman.api-key-file", "anthropic.key");
        assertThrows(SettingsException.class, () -> SettingsLoader.load(c));
    }

    @Test
    void roleIsRequired() {
        YamlConfiguration c = defaults();
        c.set("server.role", null);
        assertThrows(SettingsException.class, () -> SettingsLoader.load(c));
    }
}
