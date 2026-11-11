package dev.agentcraft.server.state;

import java.util.List;

/**
 * Read-only mirrors of the foreman's entities (see docs/protocol.md). Optional fields are null.
 * Deserialized with Gson; unknown fields are ignored.
 */
public final class Views {
    private Views() {}

    public record Skin(String name, String texture, String signature) {}

    public record Agent(
            String id,
            String name,
            String role,
            String title,
            String color,
            Skin skin,
            String state,
            String activity,
            String station,
            String taskId,
            boolean paused,
            boolean active,
            String model,
            Double costUsd) {}

    public record Task(
            String id,
            String title,
            String kind,
            String status,
            String assignee,
            List<String> deps,
            String goalId,
            int priority,
            String branch,
            String module,
            String ci,
            String blockedReason,
            String summary) {}

    public record Answer(String option, String text, String by, long ts) {}

    public record Decision(
            String id,
            String agentId,
            String kind,
            String question,
            List<String> options,
            String context,
            String status,
            String taskId,
            Answer answer) {
        public boolean isOpen() {
            return "open".equals(status);
        }
    }

    public record Repo(
            String id, String name, String path, String branch, String head, boolean dirty, String versioning) {}

    public record Goal(String id, String text, String status, double progress) {}

    public record FeedItem(long ts, String kind, String text, String agentId, String to) {}

    public record Budget(double spentTodayUsd, Double dailyUsd, double spentMonthUsd, Double monthlyUsd) {}

    public record ForemanStatus(
            String version, String backend, String auth, boolean paused, String message, Budget budget) {}
}
