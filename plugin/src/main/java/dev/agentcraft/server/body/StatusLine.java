package dev.agentcraft.server.body;

import dev.agentcraft.server.state.Views;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/** Name and status lines above an NPC's head. */
public final class StatusLine {
    private StatusLine() {}

    private static final Map<String, String> ICONS = Map.ofEntries(
            Map.entry("idle", "·"),
            Map.entry("thinking", "💭"),
            Map.entry("reading", "📖"),
            Map.entry("editing", "⌨"),
            Map.entry("running", "▶"),
            Map.entry("testing", "🧪"),
            Map.entry("building", "🔨"),
            Map.entry("inspecting", "🔎"),
            Map.entry("walking", "🚶"),
            Map.entry("waiting_user", "❓"),
            Map.entry("blocked", "⛔"),
            Map.entry("done", "✔"),
            Map.entry("error", "✖"));

    /** States in which the NPC moves its arm as if working. */
    public static final Set<String> WORKING = Set.of("editing", "building", "testing", "running");

    public static String icon(String state) {
        return ICONS.getOrDefault(state, "·");
    }

    public static Component name(Views.Agent a) {
        TextColor color = TextColor.fromHexString(a.color() == null ? "#ffffff" : a.color());
        return Component.text(a.name(), color == null ? NamedTextColor.WHITE : color);
    }

    public static String statusText(Views.Agent a) {
        if (!a.active()) {
            return "💤 fuori turno";
        }
        if (a.paused()) {
            return "⏸ in pausa";
        }
        String activity = a.activity() == null ? "" : a.activity();
        if (activity.length() > 48) {
            activity = activity.substring(0, 47) + "…";
        }
        return icon(a.state()) + " " + activity;
    }

    public static Component status(Views.Agent a) {
        NamedTextColor color = "waiting_user".equals(a.state())
                ? NamedTextColor.GOLD
                : ("error".equals(a.state()) || "blocked".equals(a.state())) ? NamedTextColor.RED : NamedTextColor.GRAY;
        return Component.text(statusText(a), a.active() && !a.paused() ? color : NamedTextColor.DARK_GRAY);
    }
}
