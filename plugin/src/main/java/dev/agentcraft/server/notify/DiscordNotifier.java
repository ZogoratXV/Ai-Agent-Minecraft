package dev.agentcraft.server.notify;

import com.google.gson.JsonObject;
import dev.agentcraft.server.state.TeamView;
import dev.agentcraft.server.state.Views;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Optional: posts new decisions and warnings to a Discord channel through a webhook, so admins who
 * are not in game know the agents are waiting. The webhook URL is read from a file (it is a secret).
 */
public final class DiscordNotifier implements TeamView.Listener {

    private final URI webhook;
    private final String serverId;
    private final Logger log;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private DiscordNotifier(URI webhook, String serverId, Logger log) {
        this.webhook = webhook;
        this.serverId = serverId;
        this.log = log;
    }

    /** Empty when not configured; logs why the file was refused. */
    public static Optional<DiscordNotifier> create(Path file, String serverId, Logger log) {
        try {
            String url = Files.readString(file).strip();
            if (!isDiscordWebhook(url)) {
                log.warning("notify.discord-webhook-file does not hold a Discord webhook URL: ignored");
                return Optional.empty();
            }
            return Optional.of(new DiscordNotifier(URI.create(url), serverId, log));
        } catch (IOException e) {
            log.warning("cannot read notify.discord-webhook-file: " + e.getMessage());
            return Optional.empty();
        }
    }

    public static boolean isDiscordWebhook(String url) {
        return url.matches("https://(discord\\.com|discordapp\\.com|ptb\\.discord\\.com|canary\\.discord\\.com)"
                + "/api/webhooks/\\d+/[A-Za-z0-9_-]+");
    }

    @Override
    public void onDecision(Views.Decision before, Views.Decision now) {
        if ((before == null || !before.isOpen()) && now.isOpen()) {
            StringBuilder b = new StringBuilder("**[" + serverId + "] Decisione " + now.id() + "**: ");
            b.append(now.question());
            if (now.options() != null && !now.options().isEmpty()) {
                b.append("\nOpzioni: ");
                for (int i = 0; i < now.options().size(); i++) {
                    b.append(i == 0 ? "" : " · ")
                            .append(i + 1)
                            .append(") ")
                            .append(now.options().get(i));
                }
            }
            b.append("\nRispondi in gioco: `/agent decisions`");
            post(b.toString());
        }
    }

    /** Warnings and errors from the foreman (budget reached, merge failed...). */
    public void onNotify(String level, String text) {
        if ("warn".equals(level) || "error".equals(level)) {
            post("**[" + serverId + "]** " + text);
        }
    }

    private void post(String content) {
        JsonObject body = new JsonObject();
        body.addProperty("content", content.length() > 1900 ? content.substring(0, 1900) + "…" : content);
        body.addProperty("username", "AgentCraft");
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new com.google.gson.JsonArray()); // never ping @everyone or roles
        body.add("allowed_mentions", mentions);
        HttpRequest req = HttpRequest.newBuilder(webhook)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((res, err) -> {
            if (err != null || res.statusCode() >= 300) {
                log.warning("Discord notification failed: "
                        + (err != null ? err.getMessage() : "HTTP " + res.statusCode()));
            }
        });
    }
}
