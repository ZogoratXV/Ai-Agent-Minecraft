package dev.agentcraft.server.protocol;

import java.util.List;

/**
 * Mirror of protocol/schema/manifest.json (generated from the zod schemas). A test fails if the
 * two drift apart, so a protocol change always reaches both sides.
 */
public final class ProtocolInfo {
    private ProtocolInfo() {}

    public static final int VERSION = 1;

    public static final List<String> PLUGIN_TO_FOREMAN = List.of(
            "hello",
            "goal.submit",
            "user.message",
            "decision.answer",
            "task.action",
            "agent.action",
            "diff.request",
            "repo.add",
            "repo.push",
            "tool.result",
            "job.progress",
            "world.event");

    public static final List<String> FOREMAN_TO_PLUGIN = List.of(
            "snapshot",
            "agent.upsert",
            "task.upsert",
            "decision.upsert",
            "goal.upsert",
            "repo.upsert",
            "feed.add",
            "foreman.status",
            "notify",
            "diff",
            "tool.call",
            "job.control",
            "ack",
            "error");

    public static final List<String> TOOLS = List.of(
            "world.regions",
            "world.inspect",
            "world.preview",
            "world.place",
            "world.undo",
            "server.command",
            "server.logs",
            "server.metrics",
            "player.simulate",
            "body.move",
            "body.say");

    public static final List<String> STAGING_ONLY_TOOLS = List.of("player.simulate");
}
