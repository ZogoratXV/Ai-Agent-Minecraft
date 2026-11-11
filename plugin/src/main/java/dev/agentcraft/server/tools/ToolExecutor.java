package dev.agentcraft.server.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentcraft.server.audit.AuditLog;
import dev.agentcraft.server.body.BodyManager;
import dev.agentcraft.server.body.Stations;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Runs {@code tool.call} requests from the foreman. Every call is gated, validated here (the
 * arguments are never trusted), audited, and answered with exactly one {@code tool.result}.
 * Runs on the global region thread.
 */
public final class ToolExecutor {

    private final ToolGate gate;
    private final BodyManager bodies;
    private final AuditLog audit;
    private final Consumer<JsonObject> reply;

    public ToolExecutor(ToolGate gate, BodyManager bodies, AuditLog audit, Consumer<JsonObject> reply) {
        this.gate = gate;
        this.bodies = bodies;
        this.audit = audit;
        this.reply = reply;
    }

    /** Thrown for malformed arguments; becomes {@code invalid_args}. */
    static final class BadArgs extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BadArgs(String message) {
            super(message);
        }
    }

    public void handle(JsonObject msg) {
        String id;
        String agentId;
        String requestedBy;
        String tool;
        JsonObject args;
        try {
            id = str(msg, "id");
            agentId = str(msg, "agentId");
            requestedBy = str(msg, "requestedBy");
            JsonObject call = obj(msg, "call");
            tool = str(call, "tool");
            args = obj(call, "args");
        } catch (BadArgs e) {
            if (msg.has("id") && msg.get("id").isJsonPrimitive()) {
                finish(msg.get("id").getAsString(), "?", "?", "?", "", null, "invalid_args", e.getMessage());
            }
            return;
        }

        Optional<ToolGate.Refusal> refusal = gate.check(agentId, tool);
        if (refusal.isPresent()) {
            finish(
                    id,
                    agentId,
                    requestedBy,
                    tool,
                    "",
                    null,
                    refusal.get().code(),
                    refusal.get().message());
            return;
        }
        String target = "";
        try {
            switch (tool) {
                case "body.move" -> {
                    JsonObject to = obj(args, "to");
                    Optional<String> err;
                    if (to.has("station")) {
                        String station = str(to, "station");
                        if (!Stations.NAMES.contains(station) && !"user".equals(station)) {
                            throw new BadArgs("unknown station '" + station + "'");
                        }
                        target = station;
                        err = bodies.moveToStation(agentId, station);
                    } else if (to.has("pos")) {
                        Location at = location(obj(to, "pos"));
                        target = at.getWorld().getName() + " " + at.getBlockX() + "," + at.getBlockY() + ","
                                + at.getBlockZ();
                        err = bodies.moveToPosition(agentId, at);
                    } else if (to.has("followPlayer")) {
                        UUID player = uuid(str(to, "followPlayer"));
                        target = "follow " + player;
                        err = bodies.follow(agentId, player);
                    } else {
                        throw new BadArgs("to needs station, pos or followPlayer");
                    }
                    if (err.isPresent()) {
                        finish(id, agentId, requestedBy, tool, target, null, "not_found", err.get());
                        return;
                    }
                }
                case "body.say" -> {
                    String text = str(args, "text");
                    if (text.isBlank() || text.length() > 256) {
                        throw new BadArgs("text must be 1-256 characters");
                    }
                    target = text.length() > 60 ? text.substring(0, 60) + "…" : text;
                    Optional<String> err = bodies.say(agentId, text);
                    if (err.isPresent()) {
                        finish(id, agentId, requestedBy, tool, target, null, "not_found", err.get());
                        return;
                    }
                }
                default -> {
                    finish(id, agentId, requestedBy, tool, "", null, "internal", tool + " is not available yet");
                    return;
                }
            }
        } catch (BadArgs e) {
            finish(id, agentId, requestedBy, tool, target, null, "invalid_args", e.getMessage());
            return;
        }
        finish(id, agentId, requestedBy, tool, target, new JsonObject(), null, null);
    }

    private void finish(
            String id,
            String agentId,
            String requestedBy,
            String tool,
            String target,
            JsonObject result,
            String errorCode,
            String errorMessage) {
        audit.record(new AuditLog.Entry(
                agentId, requestedBy, tool, target, errorCode == null ? "ok" : errorCode + ": " + errorMessage));
        JsonObject out = new JsonObject();
        out.addProperty("v", 1);
        out.addProperty("type", "tool.result");
        out.addProperty("re", id);
        out.addProperty("ok", errorCode == null);
        if (errorCode == null) {
            out.add("result", result);
        } else {
            JsonObject err = new JsonObject();
            err.addProperty("code", errorCode);
            err.addProperty("message", errorMessage);
            out.add("error", err);
        }
        reply.accept(out);
    }

    // ------------------------------------------------------------------ defensive JSON access

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new BadArgs("missing string '" + key + "'");
        }
        return e.getAsString();
    }

    private static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonObject()) {
            throw new BadArgs("missing object '" + key + "'");
        }
        return e.getAsJsonObject();
    }

    private static double num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new BadArgs("missing number '" + key + "'");
        }
        double v = e.getAsDouble();
        if (!Double.isFinite(v) || Math.abs(v) > 30_000_000) {
            throw new BadArgs("'" + key + "' out of range");
        }
        return v;
    }

    private static Location location(JsonObject pos) {
        World w = Bukkit.getWorld(str(pos, "world"));
        if (w == null) {
            throw new BadArgs("unknown world");
        }
        return new Location(w, num(pos, "x"), num(pos, "y"), num(pos, "z"));
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new BadArgs("invalid player uuid");
        }
    }
}
