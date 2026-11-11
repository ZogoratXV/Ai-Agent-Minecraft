package dev.agentcraft.server.bridge;

import com.google.gson.JsonObject;
import dev.agentcraft.server.protocol.Envelope;
import dev.agentcraft.server.protocol.ProtocolException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * WebSocket client to the foreman. Runs entirely off the server thread: connecting, reading,
 * writing and reconnecting (exponential backoff) happen on its own threads. Incoming messages are
 * handed to {@code onMessage} on the reader thread; the caller moves them to the right scheduler.
 */
public final class BridgeClient implements WebSocket.Listener {

    private final URI uri;
    private final String token;
    private final Supplier<JsonObject> hello;
    private final Consumer<Envelope> onMessage;
    private final Consumer<Boolean> onConnection;
    private final Logger log;
    private final long minBackoffMs;
    private final long maxBackoffMs;

    private final HttpClient http;
    private final ScheduledExecutorService timer;
    private final Map<String, CompletableFuture<JsonObject>> pendingAcks = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final StringBuilder partial = new StringBuilder();

    private volatile WebSocket ws;
    private volatile boolean stopped;
    private volatile boolean connected;
    private volatile boolean warnedUnauthorized;
    private long backoffMs;
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    public BridgeClient(
            URI uri,
            String token,
            Supplier<JsonObject> hello,
            Consumer<Envelope> onMessage,
            Consumer<Boolean> onConnection,
            Logger log,
            Duration minBackoff,
            Duration maxBackoff) {
        this.uri = uri;
        this.token = token;
        this.hello = hello;
        this.onMessage = onMessage;
        this.onConnection = onConnection;
        this.log = log;
        this.minBackoffMs = minBackoff.toMillis();
        this.maxBackoffMs = maxBackoff.toMillis();
        this.backoffMs = minBackoffMs;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AgentCraft-Bridge");
            t.setDaemon(true);
            return t;
        });
        this.http = HttpClient.newBuilder()
                .proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(timer)
                .build();
    }

    public boolean isConnected() {
        return connected;
    }

    public void start() {
        timer.execute(this::connect);
    }

    public void stop() {
        stopped = true;
        WebSocket w = ws;
        if (w != null) {
            w.sendClose(WebSocket.NORMAL_CLOSURE, "server stopping").orTimeout(2, TimeUnit.SECONDS);
        }
        pendingAcks.values().forEach(f -> f.completeExceptionally(new IllegalStateException("bridge stopped")));
        timer.shutdown();
    }

    private void connect() {
        if (stopped) {
            return;
        }
        http.newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, this)
                .whenComplete((socket, err) -> {
                    if (err != null) {
                        String why = String.valueOf(err.getMessage());
                        if (why.contains("401") && !warnedUnauthorized) {
                            warnedUnauthorized = true;
                            log.severe("the process on " + uri + " refuses our token: an old foreman still"
                                    + " running? Stop the server, close it (Node.js in the task manager) and restart.");
                        } else {
                            log.fine("foreman not reachable: " + why);
                        }
                        scheduleReconnect();
                        return;
                    }
                    warnedUnauthorized = false;
                    ws = socket;
                    backoffMs = minBackoffMs;
                    send(hello.get());
                });
    }

    private void scheduleReconnect() {
        if (stopped || timer.isShutdown()) {
            return;
        }
        long delay = backoffMs;
        backoffMs = Math.min(maxBackoffMs, backoffMs * 2);
        timer.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    private void markConnected(boolean value) {
        if (connected != value) {
            connected = value;
            onConnection.accept(value);
        }
    }

    /** Sends one message (thread-safe, in order). Silently dropped while disconnected. */
    public synchronized void send(JsonObject msg) {
        WebSocket w = ws;
        if (w == null || stopped) {
            return;
        }
        String text = msg.toString();
        sendChain = sendChain
                .exceptionally(e -> null)
                .thenCompose(v -> w.sendText(text, true))
                .whenComplete((v, e) -> {
                    if (e != null) {
                        log.log(Level.FINE, "send failed", e);
                    }
                });
    }

    /** Sends a message with a fresh id and completes with the foreman's {@code ack}. */
    public CompletableFuture<JsonObject> request(JsonObject msg) {
        if (!connected) {
            return CompletableFuture.failedFuture(new IllegalStateException("foreman non connesso"));
        }
        String id = "p" + nextId.getAndIncrement();
        msg.addProperty("id", id);
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        pendingAcks.put(id, f);
        f.orTimeout(15, TimeUnit.SECONDS).whenComplete((v, e) -> pendingAcks.remove(id));
        send(msg);
        return f;
    }

    // ------------------------------------------------------------------ WebSocket.Listener

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (partial.length() > Envelope.MAX_FRAME_CHARS) {
            partial.setLength(0);
            webSocket.sendClose(1009, "frame too large");
            return null;
        }
        if (last) {
            String frame = partial.toString();
            partial.setLength(0);
            handleFrame(frame);
        }
        webSocket.request(1);
        return null;
    }

    private void handleFrame(String frame) {
        Envelope env;
        try {
            env = Envelope.parse(frame);
        } catch (ProtocolException e) {
            log.warning("ignored invalid message from foreman: " + e.getMessage());
            return;
        }
        if (!env.isFromForeman()) {
            log.warning("ignored unexpected message type from foreman: " + env.type());
            return;
        }
        switch (env.type()) {
            case "snapshot" -> markConnected(true);
            case "error" -> log.warning("foreman error: " + env.body().get("message"));
            case "ack" -> {
                String re = env.body().has("re") ? env.body().get("re").getAsString() : "";
                CompletableFuture<JsonObject> f = pendingAcks.remove(re);
                if (f != null) {
                    f.complete(env.body());
                }
                return;
            }
            default -> {}
        }
        try {
            onMessage.accept(env);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "error handling " + env.type(), e);
        }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        ws = null;
        markConnected(false);
        if (statusCode == 4401) {
            log.severe("foreman refused the token (check AGENTCRAFT_TOKEN on both sides)");
        }
        scheduleReconnect();
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        ws = null;
        markConnected(false);
        log.fine("bridge error: " + error.getMessage());
        scheduleReconnect();
    }
}
