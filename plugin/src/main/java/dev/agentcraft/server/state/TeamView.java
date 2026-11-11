package dev.agentcraft.server.state;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the plugin knows about the team, rebuilt from {@code snapshot} and kept current by the
 * upserts. Only touched from the global region thread.
 */
public final class TeamView {

    /** Callbacks fired after the view changed. {@code before} is null for new entities. */
    public interface Listener {
        default void onSnapshot(TeamView view) {}

        default void onAgent(Views.Agent before, Views.Agent now) {}

        default void onDecision(Views.Decision before, Views.Decision now) {}

        default void onFeed(Views.FeedItem item) {}
    }

    public static final int FEED_LIMIT = 200;
    private static final Gson GSON = new Gson();

    private final Map<String, Views.Agent> agents = new LinkedHashMap<>();
    private final Map<String, Views.Task> tasks = new LinkedHashMap<>();
    private final Map<String, Views.Decision> decisions = new LinkedHashMap<>();
    private final Map<String, Views.Goal> goals = new LinkedHashMap<>();
    private final Map<String, Views.Repo> repos = new LinkedHashMap<>();
    private final Deque<Views.FeedItem> feed = new ArrayDeque<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private Views.ForemanStatus status;

    public void addListener(Listener l) {
        listeners.add(l);
    }

    /** Applies one state message; returns false for types this view does not handle. */
    public boolean apply(String type, JsonObject msg) {
        switch (type) {
            case "snapshot" -> {
                agents.clear();
                tasks.clear();
                decisions.clear();
                goals.clear();
                repos.clear();
                feed.clear();
                status = GSON.fromJson(msg.get("foreman"), Views.ForemanStatus.class);
                each(msg, "agents", Views.Agent.class).forEach(a -> agents.put(a.id(), a));
                each(msg, "tasks", Views.Task.class).forEach(t -> tasks.put(t.id(), t));
                each(msg, "decisions", Views.Decision.class).forEach(d -> decisions.put(d.id(), d));
                each(msg, "goals", Views.Goal.class).forEach(g -> goals.put(g.id(), g));
                each(msg, "repos", Views.Repo.class).forEach(r -> repos.put(r.id(), r));
                each(msg, "feed", Views.FeedItem.class).forEach(this::pushFeed);
                listeners.forEach(l -> l.onSnapshot(this));
            }
            case "agent.upsert" -> {
                Views.Agent a = GSON.fromJson(msg.get("agent"), Views.Agent.class);
                Views.Agent before = agents.put(a.id(), a);
                listeners.forEach(l -> l.onAgent(before, a));
            }
            case "task.upsert" -> {
                Views.Task t = GSON.fromJson(msg.get("task"), Views.Task.class);
                tasks.put(t.id(), t);
            }
            case "decision.upsert" -> {
                Views.Decision d = GSON.fromJson(msg.get("decision"), Views.Decision.class);
                Views.Decision before = decisions.put(d.id(), d);
                listeners.forEach(l -> l.onDecision(before, d));
            }
            case "goal.upsert" -> {
                Views.Goal g = GSON.fromJson(msg.get("goal"), Views.Goal.class);
                goals.put(g.id(), g);
            }
            case "repo.upsert" -> {
                Views.Repo r = GSON.fromJson(msg.get("repo"), Views.Repo.class);
                repos.put(r.id(), r);
            }
            case "feed.add" -> {
                Views.FeedItem f = GSON.fromJson(msg.get("item"), Views.FeedItem.class);
                pushFeed(f);
                listeners.forEach(l -> l.onFeed(f));
            }
            case "foreman.status" -> status = GSON.fromJson(msg.get("status"), Views.ForemanStatus.class);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void pushFeed(Views.FeedItem f) {
        feed.addLast(f);
        while (feed.size() > FEED_LIMIT) {
            feed.removeFirst();
        }
    }

    private static <T> List<T> each(JsonObject msg, String key, Class<T> type) {
        List<T> out = new ArrayList<>();
        if (msg.has(key) && msg.get(key).isJsonArray()) {
            for (JsonElement e : msg.getAsJsonArray(key)) {
                out.add(GSON.fromJson(e, type));
            }
        }
        return out;
    }

    public Collection<Views.Agent> agents() {
        return agents.values();
    }

    public Optional<Views.Agent> agent(String id) {
        return Optional.ofNullable(agents.get(id));
    }

    /** By id or by display name, case-insensitive; a leading '@' is ignored. */
    public Optional<Views.Agent> findAgent(String idOrName) {
        String key = idOrName.startsWith("@") ? idOrName.substring(1) : idOrName;
        return agents.values().stream()
                .filter(a -> a.id().equalsIgnoreCase(key) || a.name().equalsIgnoreCase(key))
                .findFirst();
    }

    public Collection<Views.Task> tasks() {
        return tasks.values();
    }

    public Optional<Views.Task> task(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    public Collection<Views.Decision> decisions() {
        return decisions.values();
    }

    public Optional<Views.Decision> decision(String id) {
        return Optional.ofNullable(decisions.get(id));
    }

    public List<Views.Decision> openDecisions() {
        return decisions.values().stream()
                .filter(Views.Decision::isOpen)
                .sorted(Comparator.comparing(Views.Decision::id))
                .toList();
    }

    public Collection<Views.Repo> repos() {
        return repos.values();
    }

    public Collection<Views.Goal> goals() {
        return goals.values();
    }

    /** Most recent last. */
    public List<Views.FeedItem> feed() {
        return List.copyOf(feed);
    }

    public Optional<Views.ForemanStatus> status() {
        return Optional.ofNullable(status);
    }
}
