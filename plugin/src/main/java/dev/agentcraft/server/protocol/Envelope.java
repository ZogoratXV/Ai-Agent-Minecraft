package dev.agentcraft.server.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.Optional;

/** The common part of every bridge message: {@code {v, type, id?}} plus the raw payload. */
public record Envelope(int version, String type, Optional<String> id, JsonObject body) {

    public static final int MAX_FRAME_CHARS = 1 << 20;

    /** Parses one text frame. Throws {@link ProtocolException} for anything malformed. */
    public static Envelope parse(String frame) {
        if (frame.length() > MAX_FRAME_CHARS) {
            throw new ProtocolException("frame too large");
        }
        JsonElement el;
        try {
            el = JsonParser.parseString(frame);
        } catch (JsonParseException e) {
            throw new ProtocolException("not valid JSON");
        }
        if (!el.isJsonObject()) {
            throw new ProtocolException("message must be a JSON object");
        }
        JsonObject o = el.getAsJsonObject();
        if (!o.has("v")
                || !o.get("v").isJsonPrimitive()
                || !o.getAsJsonPrimitive("v").isNumber()) {
            throw new ProtocolException("missing protocol version");
        }
        int v = o.get("v").getAsInt();
        if (v != ProtocolInfo.VERSION) {
            throw new ProtocolException("unsupported protocol version " + v);
        }
        if (!o.has("type") || !o.get("type").isJsonPrimitive()) {
            throw new ProtocolException("missing type");
        }
        String type = o.get("type").getAsString();
        Optional<String> id = o.has("id") && o.get("id").isJsonPrimitive()
                ? Optional.of(o.get("id").getAsString())
                : Optional.empty();
        return new Envelope(v, type, id, o);
    }

    /** Whether the plugin should accept this message from the foreman. */
    public boolean isFromForeman() {
        return ProtocolInfo.FOREMAN_TO_PLUGIN.contains(type);
    }
}
