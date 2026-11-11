package dev.agentcraft.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.server.state.TeamView;
import dev.agentcraft.server.state.Views;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeamViewTest {

    private static JsonObject fixture(String type) throws Exception {
        Path dir = Path.of(System.getProperty("agentcraft.protocolDir", "../protocol"), "examples");
        return JsonParser.parseString(Files.readString(dir.resolve(type + ".json")))
                .getAsJsonObject();
    }

    @Test
    void appliesSnapshotAndUpserts() throws Exception {
        TeamView v = new TeamView();
        List<String> events = new ArrayList<>();
        v.addListener(new TeamView.Listener() {
            @Override
            public void onSnapshot(TeamView view) {
                events.add("snapshot");
            }

            @Override
            public void onAgent(Views.Agent before, Views.Agent now) {
                events.add("agent:" + (before == null ? "new" : "changed"));
            }

            @Override
            public void onDecision(Views.Decision before, Views.Decision now) {
                events.add("decision:" + now.status());
            }
        });
        assertTrue(v.apply("snapshot", fixture("snapshot")));
        assertEquals("Briciola", v.agent("briciola").orElseThrow().name());
        assertEquals(1, v.openDecisions().size());
        assertEquals("sim", v.status().orElseThrow().backend());
        assertEquals(15.0, v.status().orElseThrow().budget().dailyUsd());

        assertTrue(v.apply("agent.upsert", fixture("agent.upsert")));
        assertTrue(v.apply("decision.upsert", fixture("decision.upsert")));
        assertTrue(v.apply("feed.add", fixture("feed.add")));
        assertEquals(2, v.feed().size());
        assertFalse(v.apply("tool.call", fixture("tool.call")));
        assertEquals(List.of("snapshot", "agent:changed", "decision:open"), events);
    }

    @Test
    void findsAgentsByIdOrNameWithOptionalAt() throws Exception {
        TeamView v = new TeamView();
        v.apply("snapshot", fixture("snapshot"));
        assertTrue(v.findAgent("@Briciola").isPresent());
        assertTrue(v.findAgent("BRICIOLA").isPresent());
        assertTrue(v.findAgent("nessuno").isEmpty());
    }

    @Test
    void optionalFieldsAreNull() throws Exception {
        TeamView v = new TeamView();
        v.apply("snapshot", fixture("snapshot"));
        assertNull(v.agent("briciola").orElseThrow().skin());
    }

    @Test
    void feedIsCapped() {
        TeamView v = new TeamView();
        for (int i = 0; i < TeamView.FEED_LIMIT + 50; i++) {
            JsonObject item = new JsonObject();
            item.addProperty("ts", i);
            item.addProperty("kind", "system");
            item.addProperty("text", "x" + i);
            JsonObject msg = new JsonObject();
            msg.add("item", item);
            v.apply("feed.add", msg);
        }
        assertEquals(TeamView.FEED_LIMIT, v.feed().size());
        assertEquals("x" + (TeamView.FEED_LIMIT + 49), v.feed().getLast().text());
    }
}
