package dev.agentcraft.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.server.config.ServerRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class PluginLoadTest {

    private ServerMock server;
    private AgentCraftPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(AgentCraftPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void enablesWithTheShippedConfig() {
        assertTrue(plugin.isEnabled());
        assertEquals(ServerRole.STAGING, plugin.settings().role());
        assertNotNull(plugin.audit());
        assertEquals(false, plugin.integrations().citizens());
    }
}
