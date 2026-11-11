package dev.agentcraft.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.server.protocol.Envelope;
import dev.agentcraft.server.protocol.ProtocolException;
import dev.agentcraft.server.protocol.ProtocolInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Keeps the Java side in step with protocol/ (zod is the source of truth). */
class ProtocolContractTest {

    private static Path protocolDir() {
        return Path.of(System.getProperty("agentcraft.protocolDir", "../protocol"));
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        a.forEach(e -> out.add(e.getAsString()));
        return out;
    }

    @Test
    void javaConstantsMatchTheGeneratedManifest() throws IOException {
        JsonObject m = JsonParser.parseString(Files.readString(protocolDir().resolve("schema/manifest.json")))
                .getAsJsonObject();
        assertEquals(ProtocolInfo.VERSION, m.get("version").getAsInt());
        assertEquals(ProtocolInfo.PLUGIN_TO_FOREMAN, strings(m.getAsJsonArray("pluginToForeman")));
        assertEquals(ProtocolInfo.FOREMAN_TO_PLUGIN, strings(m.getAsJsonArray("foremanToPlugin")));
        assertEquals(ProtocolInfo.TOOLS, strings(m.getAsJsonArray("tools")));
        assertEquals(ProtocolInfo.STAGING_ONLY_TOOLS, strings(m.getAsJsonArray("stagingOnlyTools")));
    }

    @Test
    void everyFixtureParsesAndIsKnown() throws IOException {
        Set<String> seen = new HashSet<>();
        try (Stream<Path> files = Files.list(protocolDir().resolve("examples"))) {
            for (Path f : files.toList()) {
                Envelope env = Envelope.parse(Files.readString(f));
                assertEquals(f.getFileName().toString(), env.type() + ".json");
                assertTrue(
                        ProtocolInfo.PLUGIN_TO_FOREMAN.contains(env.type()) || env.isFromForeman(),
                        "unknown type " + env.type());
                seen.add(env.type());
            }
        }
        Set<String> all = new HashSet<>(ProtocolInfo.PLUGIN_TO_FOREMAN);
        all.addAll(ProtocolInfo.FOREMAN_TO_PLUGIN);
        assertEquals(all, seen);
    }

    @Test
    void refusesMalformedFrames() {
        assertThrows(ProtocolException.class, () -> Envelope.parse("not json"));
        assertThrows(ProtocolException.class, () -> Envelope.parse("[1,2]"));
        assertThrows(ProtocolException.class, () -> Envelope.parse("{\"type\":\"ack\"}"));
        assertThrows(ProtocolException.class, () -> Envelope.parse("{\"v\":2,\"type\":\"ack\"}"));
        assertThrows(ProtocolException.class, () -> Envelope.parse("{\"v\":1}"));
    }
}
