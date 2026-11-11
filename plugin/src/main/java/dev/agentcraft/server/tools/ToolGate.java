package dev.agentcraft.server.tools;

import dev.agentcraft.server.config.ServerRole;
import dev.agentcraft.server.protocol.ProtocolInfo;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * First check on every tool call, before any argument is looked at: the tool must exist, staging
 * tools are refused on production, and each agent has a calls-per-minute budget.
 */
public final class ToolGate {

    public record Refusal(String code, String message) {}

    private static final long WINDOW_MS = 60_000;

    private final ServerRole role;
    private final int perMinute;
    private final LongSupplier clock;
    private final Map<String, Deque<Long>> calls = new HashMap<>();

    public ToolGate(ServerRole role, int perMinute, LongSupplier clock) {
        this.role = role;
        this.perMinute = perMinute;
        this.clock = clock;
    }

    public synchronized Optional<Refusal> check(String agentId, String tool) {
        if (!ProtocolInfo.TOOLS.contains(tool)) {
            return Optional.of(new Refusal("invalid_args", "unknown tool '" + tool + "'"));
        }
        if (role == ServerRole.PRODUCTION && ProtocolInfo.STAGING_ONLY_TOOLS.contains(tool)) {
            return Optional.of(new Refusal("not_staging", tool + " is only allowed on the staging server"));
        }
        long now = clock.getAsLong();
        Deque<Long> q = calls.computeIfAbsent(agentId, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) {
            q.pollFirst();
        }
        if (q.size() >= perMinute) {
            return Optional.of(new Refusal("rate_limited", "more than " + perMinute + " tool calls per minute"));
        }
        q.addLast(now);
        return Optional.empty();
    }
}
