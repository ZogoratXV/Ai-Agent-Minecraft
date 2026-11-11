package dev.agentcraft.server.audit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Append-only JSON Lines log of every agent action on the world or server. Writes happen on a
 * dedicated thread so the server thread never waits for disk.
 */
public final class AuditLog implements AutoCloseable {

    /** One audit line. {@code requestedBy} is the player (or agent) that started the work. */
    public record Entry(String agentId, String requestedBy, String action, String target, String outcome) {}

    private static final Gson GSON = new Gson();

    private final ExecutorService writer;
    private final BufferedWriter out;
    private final Clock clock;
    private final Logger logger;

    public AuditLog(Path file, Clock clock, Logger logger) {
        this.clock = clock;
        this.logger = logger;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            this.out = Files.newBufferedWriter(
                    file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open audit log " + file, e);
        }
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AgentCraft-Audit");
            t.setDaemon(true);
            return t;
        });
    }

    public void record(Entry entry) {
        JsonObject line = new JsonObject();
        line.addProperty("ts", clock.millis());
        line.addProperty("agent", entry.agentId());
        line.addProperty("requestedBy", entry.requestedBy());
        line.addProperty("action", entry.action());
        line.addProperty("target", entry.target());
        line.addProperty("outcome", entry.outcome());
        String json = GSON.toJson(line);
        writer.execute(() -> {
            try {
                out.write(json);
                out.newLine();
                out.flush();
            } catch (IOException e) {
                logger.log(Level.WARNING, "could not write audit log", e);
            }
        });
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warning("audit log did not flush in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            out.close();
        } catch (IOException e) {
            logger.log(Level.WARNING, "could not close audit log", e);
        }
    }
}
