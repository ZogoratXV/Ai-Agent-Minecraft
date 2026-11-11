package dev.agentcraft.server.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.agentcraft.server.AgentCraftPlugin;
import dev.agentcraft.server.body.Stations;
import dev.agentcraft.server.state.TeamView;
import dev.agentcraft.server.state.Views;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /agent} with Tab completion from the live team state; every branch checks its permission. */
public final class AgentCommand {
    public static final String PERM_USE = "agentcraft.use";
    public static final String PERM_GOAL = "agentcraft.goal";
    public static final String PERM_APPROVE = "agentcraft.approve";
    public static final String PERM_MANAGE = "agentcraft.manage";
    public static final String PERM_REGION = "agentcraft.region";

    private final AgentCraftPlugin plugin;

    private AgentCommand(AgentCraftPlugin plugin) {
        this.plugin = plugin;
    }

    public static LiteralCommandNode<CommandSourceStack> build(AgentCraftPlugin plugin) {
        return new AgentCommand(plugin).tree();
    }

    private static Predicate<CommandSourceStack> perm(String p) {
        return src -> src.getSender().hasPermission(p);
    }

    private TeamView view() {
        return plugin.teamView();
    }

    private LiteralCommandNode<CommandSourceStack> tree() {
        return Commands.literal("agent")
                .requires(perm(PERM_USE))
                .executes(ctx -> help(ctx.getSource().getSender()))
                .then(Commands.literal("help")
                        .executes(ctx -> help(ctx.getSource().getSender())))
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource().getSender())))
                .then(Commands.literal("agents").executes(ctx -> show(ctx, Texts.agents(view()))))
                .then(Commands.literal("tasks").executes(ctx -> show(ctx, Texts.tasks(view()))))
                .then(Commands.literal("feed").executes(ctx -> show(ctx, Texts.feed(view(), 12))))
                .then(Commands.literal("decisions").executes(ctx -> show(ctx, Texts.decisions(view()))))
                .then(Commands.literal("goal")
                        .requires(perm(PERM_GOAL))
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> goal(ctx.getSource().getSender(), str(ctx, "text")))))
                .then(Commands.literal("answer")
                        .requires(perm(PERM_APPROVE))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(
                                        suggest(v -> v.openDecisions().stream().map(Views.Decision::id)))
                                .then(Commands.argument("option", StringArgumentType.word())
                                        .suggests(this::suggestOptions)
                                        .executes(ctx -> answer(
                                                ctx.getSource().getSender(), str(ctx, "id"), str(ctx, "option"), null))
                                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                                .executes(ctx -> answer(
                                                        ctx.getSource().getSender(),
                                                        str(ctx, "id"),
                                                        str(ctx, "option"),
                                                        str(ctx, "text")))))))
                .then(Commands.literal("approve")
                        .requires(perm(PERM_APPROVE))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(
                                        suggest(v -> v.openDecisions().stream().map(Views.Decision::id)))
                                .executes(ctx -> approveOrReject(ctx.getSource().getSender(), str(ctx, "id"), true))))
                .then(Commands.literal("reject")
                        .requires(perm(PERM_APPROVE))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(
                                        suggest(v -> v.openDecisions().stream().map(Views.Decision::id)))
                                .executes(ctx -> approveOrReject(ctx.getSource().getSender(), str(ctx, "id"), false))))
                .then(agentAction("pause", true))
                .then(agentAction("resume", true))
                .then(agentAction("stop", false))
                .then(agentAction("spawn", false))
                .then(Commands.literal("task")
                        .requires(perm(PERM_MANAGE))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(suggest(v -> v.tasks().stream().map(Views.Task::id)))
                                .then(Commands.literal("cancel").executes(ctx -> taskAction(ctx, "cancel", null)))
                                .then(Commands.literal("retry").executes(ctx -> taskAction(ctx, "retry", null)))
                                .then(Commands.literal("reassign")
                                        .then(Commands.argument("agent", StringArgumentType.word())
                                                .suggests(suggest(
                                                        v -> v.agents().stream().map(Views.Agent::id)))
                                                .executes(ctx -> taskAction(ctx, "reassign", str(ctx, "agent")))))))
                .then(Commands.literal("diff")
                        .requires(perm(PERM_APPROVE))
                        .then(Commands.argument("task", StringArgumentType.word())
                                .suggests(suggest(v -> v.tasks().stream()
                                        .filter(t -> t.branch() != null)
                                        .map(Views.Task::id)))
                                .executes(ctx -> diff(ctx.getSource().getSender(), str(ctx, "task")))))
                .then(Commands.literal("repos").executes(ctx -> show(ctx, Texts.repos(view()))))
                .then(Commands.literal("push")
                        .requires(perm(PERM_APPROVE))
                        .executes(ctx -> push(ctx.getSource().getSender(), null))
                        .then(Commands.argument("repo", StringArgumentType.word())
                                .suggests(suggest(v -> v.repos().stream().map(Views.Repo::id)))
                                .executes(ctx -> push(ctx.getSource().getSender(), str(ctx, "repo")))))
                .then(Commands.literal("repo")
                        .requires(perm(PERM_MANAGE))
                        .then(Commands.literal("add")
                                .then(Commands.argument("path", StringArgumentType.greedyString())
                                        .executes(ctx -> repoAdd(ctx.getSource().getSender(), str(ctx, "path"))))))
                .then(Commands.literal("jobs").executes(ctx -> {
                    List<String> lines = plugin.jobs().describe();
                    ctx.getSource().getSender().sendMessage(Texts.header("Lavori di costruzione"));
                    if (lines.isEmpty()) {
                        ctx.getSource()
                                .getSender()
                                .sendMessage(Component.text("nessun lavoro", NamedTextColor.DARK_GRAY));
                    }
                    lines.forEach(
                            l -> ctx.getSource().getSender().sendMessage(Component.text(l, NamedTextColor.WHITE)));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("undo")
                        .requires(perm(PERM_REGION))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    plugin.jobs().undoable("").forEach(b::suggest);
                                    return b.buildFuture();
                                })
                                .executes(ctx -> undo(ctx.getSource().getSender(), str(ctx, "id")))))
                .then(Commands.literal("region")
                        .requires(perm(PERM_REGION))
                        .then(Commands.literal("pos1")
                                .executes(ctx -> regionPos(ctx.getSource().getSender(), 0)))
                        .then(Commands.literal("pos2")
                                .executes(ctx -> regionPos(ctx.getSource().getSender(), 1)))
                        .then(Commands.literal("list")
                                .executes(ctx -> regionList(ctx.getSource().getSender())))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((ctx, b) -> {
                                            plugin.regions().all().forEach(r -> b.suggest(r.name()));
                                            return b.buildFuture();
                                        })
                                        .executes(ctx ->
                                                regionRemove(ctx.getSource().getSender(), str(ctx, "name")))))
                        .then(Commands.literal("create")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx ->
                                                regionCreate(ctx.getSource().getSender(), str(ctx, "name"), null))
                                        .then(Commands.argument("coords", StringArgumentType.greedyString())
                                                .executes(ctx -> regionCreate(
                                                        ctx.getSource().getSender(),
                                                        str(ctx, "name"),
                                                        str(ctx, "coords")))))))
                .then(Commands.literal("base")
                        .requires(perm(PERM_MANAGE))
                        .then(Commands.literal("set")
                                .then(Commands.argument("station", StringArgumentType.word())
                                        .suggests((ctx, b) -> {
                                            Stations.NAMES.stream()
                                                    .filter(s -> s.startsWith(b.getRemainingLowerCase()))
                                                    .forEach(b::suggest);
                                            return b.buildFuture();
                                        })
                                        .executes(ctx ->
                                                setStation(ctx.getSource().getSender(), str(ctx, "station"))))))
                // "/agent @name message" and "/agent @all message"
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .requires(perm(PERM_GOAL))
                        .suggests((ctx, b) -> {
                            String rem = b.getRemaining();
                            if (!rem.contains(" ")) {
                                Stream.concat(
                                                Stream.of("all"),
                                                view().agents().stream().map(Views.Agent::id))
                                        .map(id -> "@" + id)
                                        .filter(s -> s.startsWith(rem.toLowerCase(Locale.ROOT)))
                                        .forEach(b::suggest);
                            }
                            return b.buildFuture();
                        })
                        .executes(ctx -> message(ctx.getSource().getSender(), str(ctx, "message"))))
                .build();
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> agentAction(
            String action, boolean defaultAll) {
        var node = Commands.literal(action).requires(perm(PERM_MANAGE));
        if (defaultAll) {
            node.executes(ctx -> agentAction(ctx.getSource().getSender(), action, "all"));
        }
        return node.then(Commands.argument("agent", StringArgumentType.word())
                .suggests(suggest(v -> Stream.concat(
                        defaultAll ? Stream.of("all") : Stream.empty(),
                        v.agents().stream()
                                .filter(a -> !"spawn".equals(action) || !a.active())
                                .map(Views.Agent::id))))
                .executes(ctx -> agentAction(ctx.getSource().getSender(), action, str(ctx, "agent"))));
    }

    // ------------------------------------------------------------------ handlers

    private int help(CommandSender s) {
        String[][] lines = {
            {"status", "stato del foreman e dei lavori"},
            {"agents | tasks | feed | decisions", "squadra, task, attività, decisioni"},
            {"goal <testo>", "nuovo obiettivo per il caposquadra"},
            {"@<nome>|@all <messaggio>", "scrivi a un agente"},
            {"answer <id> <n> [testo] | approve | reject <id>", "rispondi a una decisione"},
            {"pause|resume [nome|all] | stop|spawn <nome>", "gestisci gli agenti"},
            {"task <id> cancel|retry|reassign <nome>", "gestisci i task"},
            {"diff <task>", "riepilogo delle modifiche di un task"},
            {"repos | repo add <percorso>", "repository su cui lavorano gli agenti"},
            {"push [repo]", "pubblica su GitHub il lavoro unito (solo persone)"},
            {"region pos1 | pos2 | create <nome> | list | remove", "zone dove gli agenti possono costruire"},
            {"jobs | undo <lavoro o task>", "lavori di costruzione e annulla"},
            {"base set <stazione>", "posiziona le stazioni della base dove sei"},
        };
        s.sendMessage(Texts.header("AgentCraft"));
        for (String[] l : lines) {
            s.sendMessage(Component.text("/agent " + l[0], NamedTextColor.YELLOW)
                    .append(Component.text(" — " + l[1], NamedTextColor.GRAY)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandSender s) {
        StatusView.render(
                        plugin.getPluginMeta().getVersion(),
                        plugin.settings(),
                        plugin.integrations(),
                        plugin.isBridgeConnected())
                .forEach(s::sendMessage);
        view().status().ifPresent(st -> {
            String budget = st.budget() == null
                    ? ""
                    : String.format(
                            Locale.ROOT,
                            " · oggi %.2f $%s",
                            st.budget().spentTodayUsd(),
                            st.budget().dailyUsd() == null
                                    ? ""
                                    : String.format(
                                            Locale.ROOT,
                                            " / %.0f $",
                                            st.budget().dailyUsd()));
            s.sendMessage(Component.text(
                    "Backend: " + st.backend() + (st.paused() ? " · IN PAUSA" : "") + budget, NamedTextColor.GRAY));
            if ("failed".equals(st.auth())) {
                s.sendMessage(Component.text(
                        "⚠ Agenti fermi: " + (st.message() == null ? "autenticazione non riuscita" : st.message()),
                        NamedTextColor.RED));
            } else if (st.message() != null && st.paused()) {
                s.sendMessage(Component.text(st.message(), NamedTextColor.YELLOW));
            }
        });
        if (!view().repos().isEmpty()) {
            s.sendMessage(Component.text(
                    "Repository: "
                            + view().repos().stream()
                                    .map(r -> r.name() + (r.dirty() ? " (modifiche locali)" : ""))
                                    .collect(java.util.stream.Collectors.joining(", ")),
                    NamedTextColor.GRAY));
        }
        Texts.goals(view()).forEach(s::sendMessage);
        return Command.SINGLE_SUCCESS;
    }

    private int show(CommandContext<CommandSourceStack> ctx, List<Component> lines) {
        lines.forEach(ctx.getSource().getSender()::sendMessage);
        return Command.SINGLE_SUCCESS;
    }

    private int goal(CommandSender s, String text) {
        JsonObject m = intent("goal.submit", s);
        m.addProperty("text", text);
        send(s, m, ack -> "Obiettivo inviato" + resultField(ack, "goalId", " (", ")") + ".");
        return Command.SINGLE_SUCCESS;
    }

    private int message(CommandSender s, String raw) {
        int space = raw.indexOf(' ');
        if (!raw.startsWith("@") || space < 2 || raw.substring(space).isBlank()) {
            s.sendMessage(Component.text("Uso: /agent @<nome> <messaggio>  oppure /agent help", NamedTextColor.RED));
            return 0;
        }
        String target = raw.substring(1, space);
        String to = "all".equalsIgnoreCase(target)
                ? "all"
                : view().findAgent(target).map(Views.Agent::id).orElse(null);
        if (to == null) {
            s.sendMessage(Component.text("Agente sconosciuto: " + target, NamedTextColor.RED));
            return 0;
        }
        JsonObject m = intent("user.message", s);
        m.addProperty("to", to);
        m.addProperty("text", raw.substring(space + 1).strip());
        send(s, m, ack -> "Messaggio inviato a " + ("all".equals(to) ? "tutti" : to) + ".");
        return Command.SINGLE_SUCCESS;
    }

    private int answer(CommandSender s, String id, String option, String text) {
        JsonObject m = intent("decision.answer", s);
        m.addProperty("decisionId", id);
        if (!"-".equals(option)) {
            m.addProperty("option", option);
        }
        if (text != null && !text.isBlank()) {
            m.addProperty("text", text);
        }
        send(s, m, ack -> "Risposta inviata" + resultField(ack, "option", ": ", "") + ".");
        return Command.SINGLE_SUCCESS;
    }

    private int approveOrReject(CommandSender s, String id, boolean approve) {
        Views.Decision d = view().decision(id).orElse(null);
        if (d == null || !d.isOpen() || d.options() == null || d.options().isEmpty()) {
            s.sendMessage(Component.text("Nessuna decisione aperta con id " + id, NamedTextColor.RED));
            return 0;
        }
        String option = approve
                ? d.options().getFirst()
                : d.options().stream()
                        .filter(o -> o.equalsIgnoreCase("reject") || o.equalsIgnoreCase("deny"))
                        .findFirst()
                        .orElse(d.options().getLast());
        return answer(s, id, option, null);
    }

    private int agentAction(CommandSender s, String action, String agent) {
        String id = "all".equalsIgnoreCase(agent)
                ? "all"
                : view().findAgent(agent).map(Views.Agent::id).orElse(agent.toLowerCase(Locale.ROOT));
        JsonObject m = intent("agent.action", s);
        m.addProperty("agentId", id);
        m.addProperty("action", action);
        send(s, m, ack -> "Fatto: " + action + " " + id + ".");
        return Command.SINGLE_SUCCESS;
    }

    private int taskAction(CommandContext<CommandSourceStack> ctx, String action, String agent) {
        CommandSender s = ctx.getSource().getSender();
        JsonObject m = intent("task.action", s);
        m.addProperty("taskId", str(ctx, "id"));
        m.addProperty("action", action);
        if (agent != null) {
            m.addProperty(
                    "agentId", view().findAgent(agent).map(Views.Agent::id).orElse(agent));
        }
        send(s, m, ack -> "Fatto: " + action + " " + str(ctx, "id") + ".");
        return Command.SINGLE_SUCCESS;
    }

    private int diff(CommandSender s, String taskId) {
        JsonObject m = intent("diff.request", s);
        m.addProperty("taskId", taskId);
        send(s, m, ack -> {
            JsonObject r = ack.has("result") && ack.get("result").isJsonObject()
                    ? ack.getAsJsonObject("result")
                    : new JsonObject();
            String files = r.has("files") ? r.get("files").getAsString() : "?";
            String add = r.has("additions") ? r.get("additions").getAsString() : "?";
            String del = r.has("deletions") ? r.get("deletions").getAsString() : "?";
            String path = r.has("path") ? r.get("path").getAsString() : "";
            return "Diff di " + taskId + ": " + files + " file, +" + add + " -" + del
                    + (path.isEmpty() ? "" : "\nDiff completo sulla macchina del foreman: " + path);
        });
        return Command.SINGLE_SUCCESS;
    }

    private int push(CommandSender s, String repoId) {
        JsonObject m = intent("repo.push", s);
        if (repoId != null) {
            m.addProperty("repoId", repoId);
        }
        plugin.audit()
                .record(new dev.agentcraft.server.audit.AuditLog.Entry(
                        "-", Actor.of(s).name(), "repo.push", repoId == null ? "(default)" : repoId, "requested"));
        send(
                s,
                m,
                ack -> "Pubblicato" + resultField(ack, "remote", " su ", "") + resultField(ack, "branch", "/", "")
                        + resultField(ack, "sha", " (", ")") + ".");
        return Command.SINGLE_SUCCESS;
    }

    private int repoAdd(CommandSender s, String path) {
        JsonObject m = intent("repo.add", s);
        m.addProperty("path", path.strip());
        send(
                s,
                m,
                ack -> "Repository registrato" + resultField(ack, "repoId", " (", ")")
                        + resultField(ack, "versioning", ", ", "") + ".");
        return Command.SINGLE_SUCCESS;
    }

    private final java.util.Map<java.util.UUID, org.bukkit.Location[]> selections = new java.util.HashMap<>();

    private int regionPos(CommandSender s, int which) {
        if (!(s instanceof Player p)) {
            s.sendMessage(Component.text(
                    "Dalla console usa: /agent region create <nome> <mondo> x1 y1 z1 x2 y2 z2", NamedTextColor.RED));
            return 0;
        }
        org.bukkit.Location[] sel = selections.computeIfAbsent(p.getUniqueId(), k -> new org.bukkit.Location[2]);
        sel[which] = p.getLocation().toBlockLocation();
        s.sendMessage(Component.text(
                "Angolo " + (which + 1) + " segnato: " + sel[which].getBlockX() + " " + sel[which].getBlockY() + " "
                        + sel[which].getBlockZ()
                        + (sel[0] != null && sel[1] != null ? ". Ora: /agent region create <nome>" : ""),
                NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private int regionCreate(CommandSender s, String name, String coords) {
        dev.agentcraft.server.build.Region r;
        try {
            if (coords != null) {
                String[] c = coords.trim().split("\\s+");
                if (c.length != 7) {
                    throw new IllegalArgumentException("uso: /agent region create <nome> <mondo> x1 y1 z1 x2 y2 z2");
                }
                if (org.bukkit.Bukkit.getWorld(c[0]) == null) {
                    throw new IllegalArgumentException("il mondo " + c[0] + " non esiste");
                }
                r = dev.agentcraft.server.build.Region.of(
                        name,
                        c[0],
                        Integer.parseInt(c[1]),
                        Integer.parseInt(c[2]),
                        Integer.parseInt(c[3]),
                        Integer.parseInt(c[4]),
                        Integer.parseInt(c[5]),
                        Integer.parseInt(c[6]));
            } else {
                org.bukkit.Location[] sel = s instanceof Player p ? selections.get(p.getUniqueId()) : null;
                if (sel == null || sel[0] == null || sel[1] == null) {
                    throw new IllegalArgumentException("segna prima i due angoli con /agent region pos1 e pos2");
                }
                if (!sel[0].getWorld().equals(sel[1].getWorld())) {
                    throw new IllegalArgumentException("i due angoli sono in mondi diversi");
                }
                r = dev.agentcraft.server.build.Region.of(
                        name,
                        sel[0].getWorld().getName(),
                        sel[0].getBlockX(),
                        sel[0].getBlockY(),
                        sel[0].getBlockZ(),
                        sel[1].getBlockX(),
                        sel[1].getBlockY(),
                        sel[1].getBlockZ());
            }
            plugin.regions().create(r);
        } catch (IllegalArgumentException e) {
            s.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
            return 0;
        }
        plugin.audit()
                .record(new dev.agentcraft.server.audit.AuditLog.Entry(
                        "-", Actor.of(s).name(), "region.create", r.describe(), "ok"));
        s.sendMessage(Component.text("Regione creata: " + r.describe(), NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private int regionList(CommandSender s) {
        s.sendMessage(Texts.header("Regioni di costruzione"));
        if (plugin.regions().all().isEmpty()) {
            s.sendMessage(Component.text(
                    "nessuna regione: /agent region pos1, pos2, create <nome>", NamedTextColor.DARK_GRAY));
        }
        plugin.regions().all().forEach(r -> s.sendMessage(Component.text(r.describe(), NamedTextColor.WHITE)));
        return Command.SINGLE_SUCCESS;
    }

    private int regionRemove(CommandSender s, String name) {
        boolean ok = plugin.regions().remove(name);
        s.sendMessage(Component.text(
                ok ? "Regione " + name + " rimossa." : "Nessuna regione " + name,
                ok ? NamedTextColor.GREEN : NamedTextColor.RED));
        return ok ? Command.SINGLE_SUCCESS : 0;
    }

    private int undo(CommandSender s, String id) {
        List<String> ids = plugin.jobs().undoable(id);
        if (ids.isEmpty()) {
            s.sendMessage(Component.text("Niente da annullare per " + id + " (vedi /agent jobs)", NamedTextColor.RED));
            return 0;
        }
        int blocks = 0;
        for (String j : ids) {
            try {
                blocks += plugin.jobs().undo(j, Actor.of(s).name());
            } catch (java.io.IOException | IllegalArgumentException e) {
                s.sendMessage(Component.text("Non annullato " + j + ": " + e.getMessage(), NamedTextColor.RED));
            }
        }
        plugin.audit()
                .record(new dev.agentcraft.server.audit.AuditLog.Entry(
                        "-", Actor.of(s).name(), "world.undo", String.join(",", ids), "requested"));
        s.sendMessage(Component.text(
                "Annullo " + String.join(", ", ids) + ": ripristino " + blocks + " blocchi.", NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private int setStation(CommandSender s, String station) {
        if (!(s instanceof Player p)) {
            s.sendMessage(Component.text("Solo un giocatore può posizionare le stazioni.", NamedTextColor.RED));
            return 0;
        }
        if (!Stations.NAMES.contains(station)) {
            s.sendMessage(Component.text("Stazioni: " + String.join(", ", Stations.NAMES), NamedTextColor.RED));
            return 0;
        }
        plugin.stations().set(station, p.getLocation());
        s.sendMessage(Component.text("Stazione '" + station + "' impostata qui.", NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ helpers

    private static String str(CommandContext<CommandSourceStack> ctx, String name) {
        return StringArgumentType.getString(ctx, name);
    }

    private static JsonObject intent(String type, CommandSender s) {
        JsonObject m = new JsonObject();
        m.addProperty("v", 1);
        m.addProperty("type", type);
        m.add("actor", Actor.of(s).toJson());
        return m;
    }

    private static String resultField(JsonObject ack, String field, String before, String after) {
        if (ack.has("result") && ack.get("result").isJsonObject()) {
            JsonObject r = ack.getAsJsonObject("result");
            if (r.has(field) && r.get(field).isJsonPrimitive()) {
                return before + r.get(field).getAsString() + after;
            }
        }
        return "";
    }

    private void send(CommandSender s, JsonObject msg, Function<JsonObject, String> onOk) {
        plugin.request(msg)
                .whenComplete((ack, err) -> Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                    if (err != null) {
                        Throwable cause = err.getCause() != null ? err.getCause() : err;
                        String why = cause instanceof java.util.concurrent.TimeoutException
                                ? "il foreman non ha risposto"
                                : cause.getMessage();
                        s.sendMessage(Component.text("Non inviato: " + why, NamedTextColor.RED));
                    } else if (ack.has("ok") && ack.get("ok").getAsBoolean()) {
                        s.sendMessage(Component.text(onOk.apply(ack), NamedTextColor.GREEN));
                    } else {
                        String why = ack.has("error") ? ack.get("error").getAsString() : "errore";
                        s.sendMessage(Component.text("Rifiutato: " + why, NamedTextColor.RED));
                    }
                }));
    }

    private SuggestionProvider<CommandSourceStack> suggest(Function<TeamView, Stream<String>> values) {
        return (ctx, b) -> {
            String rem = b.getRemainingLowerCase();
            values.apply(view())
                    .filter(v -> v.toLowerCase(Locale.ROOT).startsWith(rem))
                    .forEach(b::suggest);
            return b.buildFuture();
        };
    }

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestOptions(
            CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        view().decision(str(ctx, "id")).ifPresent(d -> {
            List<String> opts = d.options() == null ? List.of() : d.options();
            for (int i = 0; i < opts.size(); i++) {
                b.suggest(String.valueOf(i + 1), new com.mojang.brigadier.LiteralMessage(opts.get(i)));
            }
            if (opts.isEmpty()) {
                b.suggest("-");
            }
        });
        return b.buildFuture();
    }
}
