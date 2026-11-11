package dev.agentcraft.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.server.audit.AuditLog;
import dev.agentcraft.server.config.ServerRole;
import dev.agentcraft.server.tools.ToolExecutor;
import dev.agentcraft.server.tools.ToolGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolGateTest {

    @Test
    void refusesUnknownToolsAndStagingToolsOnProduction() {
        ToolGate prod = new ToolGate(ServerRole.PRODUCTION, 10, () -> 0);
        assertEquals("invalid_args", prod.check("a", "server.op").orElseThrow().code());
        assertEquals(
                "not_staging", prod.check("a", "player.simulate").orElseThrow().code());
        assertTrue(prod.check("a", "body.say").isEmpty());
        assertTrue(new ToolGate(ServerRole.STAGING, 10, () -> 0)
                .check("a", "player.simulate")
                .isEmpty());
    }

    @Test
    void limitsCallsPerAgentPerMinute() {
        AtomicLong now = new AtomicLong(0);
        ToolGate g = new ToolGate(ServerRole.STAGING, 3, now::get);
        for (int i = 0; i < 3; i++) {
            assertTrue(g.check("a", "body.say").isEmpty());
        }
        assertEquals("rate_limited", g.check("a", "body.say").orElseThrow().code());
        assertTrue(g.check("b", "body.say").isEmpty(), "budget is per agent");
        now.set(60_000);
        assertTrue(g.check("a", "body.say").isEmpty(), "window slides");
    }

    @Test
    void executorAnswersEveryCallAndAuditsIt(@TempDir Path dir) throws Exception {
        List<JsonObject> replies = new ArrayList<>();
        AuditLog audit = new AuditLog(dir.resolve("a.jsonl"), Clock.systemUTC(), Logger.getAnonymousLogger());
        ToolExecutor ex = new ToolExecutor(new ToolGate(ServerRole.PRODUCTION, 60, () -> 0), null, audit, replies::add);

        ex.handle(call("player.simulate", "{\"actions\":[{\"kind\":\"wait\",\"ticks\":1}]}"));
        ex.handle(call("body.say", "{\"text\":\"\"}"));
        ex.handle(call("body.move", "{\"to\":{\"station\":\"tetto\"}}"));
        ex.handle(call("world.inspect", "{\"region\":{\"region\":\"piazza\"},\"detail\":\"summary\"}"));
        ex.handle(JsonParser.parseString("{\"id\":\"f9\",\"agentId\":7}").getAsJsonObject());
        audit.close();

        List<String> codes = replies.stream()
                .map(r -> r.getAsJsonObject("error").get("code").getAsString())
                .toList();
        assertEquals(List.of("not_staging", "invalid_args", "invalid_args", "internal", "invalid_args"), codes);
        assertTrue(replies.stream().allMatch(r -> r.get("type").getAsString().equals("tool.result")));
        assertEquals("f9", replies.getLast().get("re").getAsString());
        assertEquals(5, Files.readAllLines(dir.resolve("a.jsonl")).size());
    }

    private static JsonObject call(String tool, String args) {
        return JsonParser.parseString("{\"v\":1,\"type\":\"tool.call\",\"id\":\"f1\",\"agentId\":\"mattone\","
                        + "\"requestedBy\":\"test\",\"deadlineMs\":1000,\"call\":{\"tool\":\"" + tool + "\",\"args\":"
                        + args + "}}")
                .getAsJsonObject();
    }
}
