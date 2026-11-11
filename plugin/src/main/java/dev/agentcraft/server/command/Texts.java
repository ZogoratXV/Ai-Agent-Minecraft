package dev.agentcraft.server.command;

import dev.agentcraft.server.body.StatusLine;
import dev.agentcraft.server.state.TeamView;
import dev.agentcraft.server.state.Views;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/** Chat output of the read-only subcommands. */
public final class Texts {
    private Texts() {}

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault())
            .withLocale(Locale.ITALIAN);

    static Component header(String title) {
        return Component.text("— " + title + " —", NamedTextColor.GOLD);
    }

    public static List<Component> agents(TeamView v) {
        List<Component> out = new ArrayList<>();
        out.add(header("Agenti"));
        for (Views.Agent a : v.agents()) {
            out.add(Component.text()
                    .append(StatusLine.name(a))
                    .append(Component.text(
                            " " + a.role() + (a.title() == null ? "" : " · " + a.title()), NamedTextColor.GRAY))
                    .append(Component.text("  " + StatusLine.statusText(a), NamedTextColor.WHITE))
                    .append(Component.text(a.taskId() == null ? "" : "  [" + a.taskId() + "]", NamedTextColor.AQUA))
                    .build());
        }
        if (v.agents().isEmpty()) {
            out.add(Component.text("nessun agente (foreman non connesso?)", NamedTextColor.DARK_GRAY));
        }
        return out;
    }

    public static List<Component> tasks(TeamView v) {
        List<Component> out = new ArrayList<>();
        out.add(header("Task"));
        List<Views.Task> visible = v.tasks().stream()
                .filter(t -> !"cancelled".equals(t.status()))
                .sorted(Comparator.comparing(Views.Task::id))
                .toList();
        for (Views.Task t : visible) {
            NamedTextColor color = switch (t.status()) {
                case "done" -> NamedTextColor.GREEN;
                case "doing" -> NamedTextColor.YELLOW;
                case "review" -> NamedTextColor.AQUA;
                case "blocked" -> NamedTextColor.RED;
                default -> NamedTextColor.GRAY;
            };
            String deps = t.deps() == null || t.deps().isEmpty() ? "" : " ← " + String.join(",", t.deps());
            out.add(Component.text()
                    .append(Component.text(t.id() + " ", NamedTextColor.WHITE))
                    .append(Component.text("[" + t.status() + "] ", color))
                    .append(Component.text(t.title(), NamedTextColor.WHITE))
                    .append(Component.text(
                            (t.assignee() == null ? "" : " @" + t.assignee())
                                    + deps
                                    + (t.blockedReason() == null ? "" : " — " + t.blockedReason()),
                            NamedTextColor.GRAY))
                    .build());
        }
        if (visible.isEmpty()) {
            out.add(Component.text("nessun task", NamedTextColor.DARK_GRAY));
        }
        return out;
    }

    public static List<Component> feed(TeamView v, int last) {
        List<Component> out = new ArrayList<>();
        out.add(header("Attività"));
        List<Views.FeedItem> items = v.feed();
        for (Views.FeedItem f : items.subList(Math.max(0, items.size() - last), items.size())) {
            String who = f.agentId() == null
                    ? ""
                    : v.agent(f.agentId()).map(Views.Agent::name).orElse(f.agentId()) + ": ";
            out.add(Component.text()
                    .append(Component.text(TIME.format(Instant.ofEpochMilli(f.ts())) + " ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(who, NamedTextColor.YELLOW))
                    .append(Component.text(
                            f.text(), "error".equals(f.kind()) ? NamedTextColor.RED : NamedTextColor.WHITE))
                    .build());
        }
        if (items.isEmpty()) {
            out.add(Component.text("niente da mostrare", NamedTextColor.DARK_GRAY));
        }
        return out;
    }

    public static List<Component> decisions(TeamView v) {
        List<Component> out = new ArrayList<>();
        out.add(header("Decisioni in attesa"));
        for (Views.Decision d : v.openDecisions()) {
            out.add(dev.agentcraft.server.notify.Notifier.decisionMessage(
                    d, v.agent(d.agentId()).orElse(null)));
        }
        if (v.openDecisions().isEmpty()) {
            out.add(Component.text("nessuna decisione in attesa", NamedTextColor.DARK_GRAY));
        }
        return out;
    }

    public static List<Component> goals(TeamView v) {
        List<Component> out = new ArrayList<>();
        for (Views.Goal g : v.goals()) {
            if ("active".equals(g.status()) || "planning".equals(g.status())) {
                out.add(Component.text()
                        .append(Component.text("Obiettivo " + g.id() + " ", NamedTextColor.GRAY))
                        .append(Component.text(Math.round(g.progress() * 100) + "% ", NamedTextColor.GREEN))
                        .append(Component.text(g.text(), NamedTextColor.WHITE))
                        .build());
            }
        }
        int open = v.openDecisions().size();
        if (open > 0) {
            out.add(Component.text(open + " decisioni in attesa ", NamedTextColor.YELLOW)
                    .append(Component.text("[vedi]", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.runCommand("/agent decisions"))));
        }
        return out;
    }
}
