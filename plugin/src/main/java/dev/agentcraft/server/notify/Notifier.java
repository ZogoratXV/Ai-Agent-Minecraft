package dev.agentcraft.server.notify;

import dev.agentcraft.server.state.TeamView;
import dev.agentcraft.server.state.Views;
import java.util.List;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Tells admins when an agent needs them: clickable chat options, a sound, and a reminder on join. */
public final class Notifier implements TeamView.Listener, Listener {
    public static final String PERM_APPROVE = "agentcraft.approve";
    public static final String PERM_USE = "agentcraft.use";

    private static final Component PREFIX = Component.text("[AgentCraft] ", NamedTextColor.GOLD);

    private final TeamView view;

    public Notifier(TeamView view) {
        this.view = view;
    }

    @Override
    public void onDecision(Views.Decision before, Views.Decision now) {
        if (now.isOpen() && (before == null || !before.isOpen())) {
            Component msg = decisionMessage(now, view.agent(now.agentId()).orElse(null));
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission(PERM_APPROVE)) {
                    p.sendMessage(msg);
                    p.playSound(Sound.sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, Sound.Source.MASTER, 0.8f, 1.2f));
                }
            }
            Bukkit.getConsoleSender().sendMessage(msg);
        }
    }

    /** {@code notify} from the foreman. Decisions are already announced by {@link #onDecision}. */
    public void onNotify(String level, String text, String decisionId) {
        if (decisionId != null
                && view.decision(decisionId).map(Views.Decision::isOpen).orElse(false)) {
            return;
        }
        NamedTextColor color = "warn".equals(level) ? NamedTextColor.YELLOW : NamedTextColor.GRAY;
        Component msg = PREFIX.append(Component.text(text, color));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(PERM_USE)) {
                p.sendMessage(msg);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        int open = view.openDecisions().size();
        if (open > 0 && p.hasPermission(PERM_APPROVE)) {
            p.sendMessage(PREFIX.append(Component.text(
                            open == 1 ? "1 decisione ti aspetta. " : open + " decisioni ti aspettano. ",
                            NamedTextColor.YELLOW))
                    .append(Component.text("[vedi]", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.runCommand("/agent decisions"))));
        }
    }

    /** "❓ Briciola (d3): question" plus one clickable button per option. */
    public static Component decisionMessage(Views.Decision d, Views.Agent agent) {
        String who = agent == null ? d.agentId() : agent.name();
        TextComponent.Builder b = Component.text()
                .append(PREFIX)
                .append(Component.text("❓ " + who, NamedTextColor.YELLOW))
                .append(Component.text(" (" + d.id() + ", " + d.kind() + "): ", NamedTextColor.GRAY))
                .append(Component.text(d.question(), NamedTextColor.WHITE));
        if (d.context() != null && !d.context().isBlank()) {
            b.append(Component.newline()).append(Component.text("   " + d.context(), NamedTextColor.DARK_GRAY));
        }
        b.append(Component.newline()).append(Component.text("   "));
        List<String> options = d.options() == null ? List.of() : d.options();
        for (int i = 0; i < options.size(); i++) {
            String label = options.get(i);
            String cmd = "/agent answer " + d.id() + " " + (i + 1);
            b.append(Component.text("[" + (i + 1) + " " + label + "]", optionColor(label))
                            .clickEvent(ClickEvent.runCommand(cmd))
                            .hoverEvent(HoverEvent.showText(Component.text("Clic: " + label))))
                    .append(Component.text(" "));
        }
        b.append(Component.text("[✎ testo]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.suggestCommand(
                        "/agent answer " + d.id() + " " + (options.isEmpty() ? "-" : "1") + " "))
                .hoverEvent(HoverEvent.showText(Component.text("Rispondi aggiungendo un testo"))));
        return b.build();
    }

    private static NamedTextColor optionColor(String label) {
        String l = label.toLowerCase();
        if (l.equals("merge") || l.startsWith("approv") || l.startsWith("allow")) {
            return NamedTextColor.GREEN;
        }
        if (l.equals("reject") || l.equals("deny") || l.startsWith("rifiut")) {
            return NamedTextColor.RED;
        }
        return NamedTextColor.AQUA;
    }
}
