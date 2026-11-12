package dev.agentcraft.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class AgentGuiTest {

    private ServerMock server;
    private AgentCraftPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(AgentCraftPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static JsonObject fixture(String name) throws Exception {
        return JsonParser.parseString(Files.readString(Path.of("../protocol/examples/" + name + ".json")))
                .getAsJsonObject();
    }

    private static String plain(net.kyori.adventure.text.Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void showsAgentsAndOpenDecisionsAndOpensTheOptions() throws Exception {
        assertTrue(plugin.teamView().apply("snapshot", fixture("snapshot")));
        PlayerMock p = server.addPlayer();
        plugin.gui().openMain(p);
        InventoryView view = p.getOpenInventory();
        assertEquals("AgentCraft: la squadra", plain(view.title()));
        assertEquals(Material.PLAYER_HEAD, view.getTopInventory().getItem(0).getType());
        assertEquals(
                "Briciola",
                plain(view.getTopInventory().getItem(0).getItemMeta().displayName()));
        assertEquals(Material.ANVIL, view.getTopInventory().getItem(9).getType()); // the open merge decision

        InventoryClickEvent click = new InventoryClickEvent(
                view, InventoryType.SlotType.CONTAINER, 9, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(click);
        assertTrue(click.isCancelled()); // nothing can be taken out
        server.getScheduler().performTicks(2);
        InventoryView decision = p.getOpenInventory();
        assertEquals("Decisione d3", plain(decision.title()));
        assertNotNull(decision.getTopInventory().getItem(10));
        assertEquals(
                "1. Merge",
                plain(decision.getTopInventory().getItem(10).getItemMeta().displayName()));
    }
}
