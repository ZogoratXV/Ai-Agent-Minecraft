package dev.agentcraft.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.server.body.StatusLine;
import dev.agentcraft.server.bridge.TokenSource;
import dev.agentcraft.server.notify.Notifier;
import dev.agentcraft.server.state.Views;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextsTest {

    private static Views.Agent agent(String state, boolean active, boolean paused) {
        return new Views.Agent(
                "mattone",
                "Mattone",
                "builder",
                "Capomastro",
                "#b5651d",
                null,
                state,
                "costruendo Torre #t4",
                "worksite",
                "t4",
                paused,
                active,
                "sim",
                null);
    }

    @Test
    void statusLineShowsStateIconOrPauseOrOffShift() {
        assertEquals("🔨 costruendo Torre #t4", StatusLine.statusText(agent("building", true, false)));
        assertEquals("⏸ in pausa", StatusLine.statusText(agent("building", true, true)));
        assertEquals("💤 fuori turno", StatusLine.statusText(agent("idle", false, false)));
        assertEquals("❓", StatusLine.icon("waiting_user"));
    }

    @Test
    void decisionMessageHasOneClickableCommandPerOption() {
        Views.Decision d = new Views.Decision(
                "d3",
                "bussola",
                "merge",
                "Unire t2?",
                List.of("Merge", "Request changes", "Reject"),
                "2 file",
                "open",
                "t2",
                null);
        String plain = PlainTextComponentSerializer.plainText().serialize(Notifier.decisionMessage(d, null));
        assertTrue(plain.contains("[1 Merge]"));
        assertTrue(plain.contains("[3 Reject]"));
        assertTrue(plain.contains("Unire t2?"));
    }

    @Test
    void tokenComesFromEnvOrFileAndMustBeLongEnough(@TempDir Path dir) throws IOException {
        assertEquals(
                "a".repeat(20),
                TokenSource.resolve(Map.of("AGENTCRAFT_TOKEN", "a".repeat(20)), dir, "")
                        .orElseThrow());
        Files.writeString(dir.resolve("token.txt"), "b".repeat(24) + "\n");
        assertEquals(
                "b".repeat(24), TokenSource.resolve(Map.of(), dir, "token.txt").orElseThrow());
        assertTrue(TokenSource.resolve(Map.of(), dir, "").isEmpty());
        assertThrows(IOException.class, () -> TokenSource.resolve(Map.of("AGENTCRAFT_TOKEN", "short"), dir, ""));
    }
}
