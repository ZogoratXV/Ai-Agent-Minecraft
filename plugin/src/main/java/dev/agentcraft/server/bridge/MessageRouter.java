package dev.agentcraft.server.bridge;

import com.google.gson.JsonObject;
import dev.agentcraft.server.notify.Notifier;
import dev.agentcraft.server.protocol.Envelope;
import dev.agentcraft.server.state.TeamView;
import dev.agentcraft.server.tools.ToolExecutor;
import java.util.logging.Logger;

/** Dispatches foreman messages on the global region thread. */
public final class MessageRouter {
    private final TeamView view;
    private final ToolExecutor tools;
    private final Notifier notifier;
    private final Logger log;
    private dev.agentcraft.server.build.BuildJobs jobs;

    public void setJobs(dev.agentcraft.server.build.BuildJobs jobs) {
        this.jobs = jobs;
    }

    public MessageRouter(TeamView view, ToolExecutor tools, Notifier notifier, Logger log) {
        this.view = view;
        this.tools = tools;
        this.notifier = notifier;
        this.log = log;
    }

    public void handle(Envelope env) {
        JsonObject body = env.body();
        if (view.apply(env.type(), body)) {
            return;
        }
        switch (env.type()) {
            case "tool.call" -> tools.handle(body);
            case "notify" ->
                notifier.onNotify(
                        body.has("level") ? body.get("level").getAsString() : "info",
                        body.has("text") ? body.get("text").getAsString() : "",
                        body.has("decisionId") ? body.get("decisionId").getAsString() : null);
            case "job.control" -> {
                if (jobs != null && body.has("jobId") && body.has("action")) {
                    String id = body.get("jobId").getAsString();
                    switch (body.get("action").getAsString()) {
                        case "pause" -> jobs.pause(id, true);
                        case "resume" -> jobs.pause(id, false);
                        case "cancel" -> jobs.cancel(id);
                        default -> {}
                    }
                }
            }
            case "diff" -> log.fine("diff arrives as the ack of /agent diff");
            default -> {}
        }
    }
}
