package dev.agentcraft.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.server.config.PluginSettings;
import dev.agentcraft.server.config.SettingsException;
import dev.agentcraft.server.config.SettingsLoader;
import dev.agentcraft.server.notify.DiscordNotifier;
import dev.agentcraft.server.ops.DeployService;
import dev.agentcraft.server.ops.ServerOps;
import dev.agentcraft.server.security.CommandPolicy;
import dev.agentcraft.server.tools.ToolFailure;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpsTest {

    private static YamlConfiguration defaults() throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        try (InputStream in = OpsTest.class.getResourceAsStream("/config.yml")) {
            c.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return c;
    }

    private static Path jar(Path dir, String entry, String yml) throws Exception {
        Path p = dir.resolve("demo.jar");
        try (OutputStream o = Files.newOutputStream(p);
                ZipOutputStream z = new ZipOutputStream(o)) {
            if (entry != null) {
                z.putNextEntry(new ZipEntry(entry));
                z.write(yml.getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
            z.putNextEntry(new ZipEntry("demo/Main.class"));
            z.write(new byte[] {1, 2, 3});
            z.closeEntry();
        }
        return p;
    }

    @Test
    void readsThePluginNameAndVersionOfAJar(@TempDir Path dir) throws Exception {
        DeployService.JarInfo info =
                DeployService.inspect(jar(dir, "plugin.yml", "name: Demo\nversion: 1.2.0\nmain: demo.Main\n"));
        assertEquals(new DeployService.JarInfo("Demo", "1.2.0"), info);
        assertEquals(
                "Paper",
                DeployService.inspect(jar(dir, "paper-plugin.yml", "name: Paper\nversion: '2'\n"))
                        .name());
    }

    @Test
    void refusesFilesThatAreNotPluginJars(@TempDir Path dir) throws Exception {
        assertThrows(ToolFailure.class, () -> DeployService.inspect(jar(dir, null, "")));
        assertThrows(ToolFailure.class, () -> DeployService.inspect(jar(dir, "plugin.yml", "name: ../evil\n")));
        Path txt = Files.writeString(dir.resolve("a.txt"), "x");
        assertThrows(ToolFailure.class, () -> DeployService.inspect(txt));
    }

    @Test
    void hidesAddressesAndSecretsFromTheAgents() {
        String r = ServerOps.redact("Player joined from 192.168.1.20 with token=abc123 and password: hunter2");
        assertFalse(r.contains("192.168.1.20"));
        assertFalse(r.contains("abc123"));
        assertFalse(r.contains("hunter2"));
        assertTrue(r.contains("Player joined"));
    }

    @Test
    void theTestPlayerSkipsTheWhitelistButNeverTheDenyList() {
        CommandPolicy p = new CommandPolicy(List.of("list"));
        assertInstanceOf(CommandPolicy.Denied.class, p.check("shop open"));
        assertInstanceOf(CommandPolicy.Allowed.class, p.checkAsPlayer("shop open"));
        assertInstanceOf(CommandPolicy.Denied.class, p.checkAsPlayer("op AgentTester"));
        assertInstanceOf(CommandPolicy.Denied.class, p.checkAsPlayer("minecraft:ban x"));
    }

    @Test
    void validatesDeployQaAndNotifySettings() throws Exception {
        PluginSettings s = SettingsLoader.load(defaults());
        assertEquals("foreman/home/deploy", s.deploy().fromDir());
        assertEquals(PluginSettings.RestartMethod.AUTO, s.deploy().restart());
        assertEquals("AgentTester", s.qa().testerName());

        YamlConfiguration c = defaults();
        c.set("deploy.restart", "pelican");
        assertThrows(SettingsException.class, () -> SettingsLoader.load(c), "pelican without url and key");
        c.set("deploy.pelican.url", "https://panel.example.com/");
        c.set("deploy.pelican.api-key-file", "pelican.key");
        assertEquals(
                "https://panel.example.com", SettingsLoader.load(c).deploy().pelicanUrl());

        YamlConfiguration q = defaults();
        q.set("qa.tester-permissions", List.of("shop.use", "*"));
        assertThrows(SettingsException.class, () -> SettingsLoader.load(q));
        q.set("qa.tester-permissions", List.of("luckperms.user.info"));
        assertThrows(SettingsException.class, () -> SettingsLoader.load(q));
        q.set("qa.tester-permissions", List.of("shop.use"));
        assertEquals(List.of("shop.use"), SettingsLoader.load(q).qa().testerPermissions());
    }

    @Test
    void acceptsOnlyDiscordWebhookUrls() {
        assertTrue(DiscordNotifier.isDiscordWebhook("https://discord.com/api/webhooks/123456/abc-DEF_9"));
        assertFalse(DiscordNotifier.isDiscordWebhook("https://evil.example.com/api/webhooks/1/x"));
        assertFalse(DiscordNotifier.isDiscordWebhook("http://discord.com/api/webhooks/1/x"));
    }

    @Test
    void theProductionConfigIsValidAndSafe() throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(Files.readString(Path.of("../server-config/produzione/plugins/AgentCraftServer/config.yml")));
        PluginSettings s = SettingsLoader.load(c);
        assertEquals(dev.agentcraft.server.config.ServerRole.PRODUCTION, s.role());
        assertEquals(PluginSettings.RestartMethod.NONE, s.deploy().restart());
        assertEquals("", s.foreman().claudeTokenFile());
        assertFalse(s.commandWhitelist().contains("say"));
    }
}
