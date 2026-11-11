package dev.agentcraft.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import dev.agentcraft.server.audit.AuditLog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditLogTest {

    @Test
    void writesOneJsonLinePerEntryAndAppends(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("audit.jsonl");
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC);
        try (AuditLog log = new AuditLog(file, clock, Logger.getAnonymousLogger())) {
            log.record(new AuditLog.Entry("mattone", "user:Zogorat", "world.place", "piazza", "ok"));
        }
        try (AuditLog log = new AuditLog(file, clock, Logger.getAnonymousLogger())) {
            log.record(new AuditLog.Entry("briciola", "bussola", "server.command", "op x", "denied"));
        }
        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        var first = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertEquals(1234, first.get("ts").getAsLong());
        assertEquals("mattone", first.get("agent").getAsString());
        assertEquals(
                "denied",
                JsonParser.parseString(lines.get(1))
                        .getAsJsonObject()
                        .get("outcome")
                        .getAsString());
    }
}
