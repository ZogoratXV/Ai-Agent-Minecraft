package dev.agentcraft.server.gui;

import dev.agentcraft.server.state.TeamView;
import dev.agentcraft.server.state.Views;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * {@code /agent gui}: the team at a glance. Agents (click: pause/resume), open decisions (click:
 * choose an option), tasks and recent activity. Every click runs the matching /agent command, so
 * permissions and checks are exactly those of the commands.
 */
public final class AgentGui implements Listener {

    private final TeamView view;

    public AgentGui(TeamView view) {
        this.view = view;
    }

    /** Marks our inventories and remembers what each slot does. */
    private static final class Menu implements InventoryHolder {
        private final Map<Integer, String> commands = new HashMap<>();
        private final Map<Integer, String> submenus = new HashMap<>();
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public void openMain(Player p) {
        Menu m = new Menu();
        Inventory inv = Bukkit.createInventory(m, 54, Component.text("AgentCraft: la squadra"));
        m.inventory = inv;
        int slot = 0;
        for (Views.Agent a : view.agents()) {
            if (slot > 8) {
                break;
            }
            List<Component> lore = new ArrayList<>();
            lore.add(line(a.title() + " (" + a.role() + ")", NamedTextColor.GRAY));
            lore.add(line(
                    a.active() ? (a.paused() ? "in pausa" : a.state() + ": " + nz(a.activity())) : "fuori turno",
                    NamedTextColor.WHITE));
            if (a.taskId() != null) {
                lore.add(line("task " + a.taskId(), NamedTextColor.AQUA));
            }
            if (a.model() != null) {
                lore.add(line(
                        a.model() + (a.costUsd() == null ? "" : String.format(" · %.2f $", a.costUsd())),
                        NamedTextColor.DARK_GRAY));
            }
            lore.add(line(a.paused() ? "Clic: riprendi" : "Clic: metti in pausa", NamedTextColor.YELLOW));
            inv.setItem(slot, item(head(a), name(a.name(), a.color()), lore));
            m.commands.put(slot, "agent " + (a.paused() ? "resume " : "pause ") + a.id());
            slot++;
        }
        slot = 9;
        List<Views.Decision> open = view.openDecisions();
        for (Views.Decision d : open) {
            if (slot > 26) {
                break;
            }
            List<Component> lore = new ArrayList<>();
            wrap(d.question(), lore, NamedTextColor.WHITE);
            if (d.context() != null) {
                for (String c : d.context().split("\n")) {
                    if (lore.size() > 12) {
                        break;
                    }
                    wrap(c, lore, NamedTextColor.GRAY);
                }
            }
            lore.add(line("Clic: scegli la risposta", NamedTextColor.YELLOW));
            Material mat = switch (d.kind()) {
                case "merge" -> Material.ANVIL;
                case "deploy" -> Material.CHEST_MINECART;
                case "build" -> Material.BRICKS;
                case "permission" -> Material.TRIPWIRE_HOOK;
                default -> Material.WRITABLE_BOOK;
            };
            inv.setItem(slot, item(mat, Component.text(d.id() + " · " + d.kind(), NamedTextColor.GOLD), lore));
            m.submenus.put(slot, d.id());
            slot++;
        }
        if (open.isEmpty()) {
            inv.setItem(
                    13,
                    item(
                            Material.LIME_STAINED_GLASS_PANE,
                            Component.text("Nessuna decisione in attesa", NamedTextColor.GREEN),
                            List.of()));
        }
        slot = 27;
        for (Views.Task t : view.tasks()) {
            if ("done".equals(t.status()) || "cancelled".equals(t.status())) {
                continue;
            }
            if (slot > 44) {
                break;
            }
            List<Component> lore = new ArrayList<>();
            lore.add(line(
                    t.kind() + " · " + t.status() + (t.assignee() == null ? "" : " · @" + t.assignee()),
                    NamedTextColor.GRAY));
            if (t.blockedReason() != null && "blocked".equals(t.status())) {
                wrap(t.blockedReason(), lore, NamedTextColor.RED);
            }
            Material mat = switch (t.status()) {
                case "doing" -> Material.YELLOW_CONCRETE;
                case "review" -> Material.LIGHT_BLUE_CONCRETE;
                case "blocked" -> Material.RED_CONCRETE;
                default -> Material.WHITE_CONCRETE;
            };
            inv.setItem(slot, item(mat, Component.text(t.id() + " " + t.title(), NamedTextColor.WHITE), lore));
            slot++;
        }
        List<Component> feed = new ArrayList<>();
        List<Views.FeedItem> items = view.feed();
        for (int i = Math.max(0, items.size() - 10); i < items.size(); i++) {
            Views.FeedItem f = items.get(i);
            feed.add(line((f.agentId() == null ? "" : f.agentId() + ": ") + cut(f.text(), 60), NamedTextColor.GRAY));
        }
        inv.setItem(49, item(Material.BOOK, Component.text("Ultime attività", NamedTextColor.AQUA), feed));
        String status = view.status()
                .map(s -> s.backend() + " · " + s.auth() + (s.paused() ? " · in pausa" : ""))
                .orElse("foreman non connesso");
        inv.setItem(45, item(Material.COMPASS, Component.text("Stato: " + status, NamedTextColor.WHITE), List.of()));
        inv.setItem(53, item(Material.BARRIER, Component.text("Chiudi", NamedTextColor.RED), List.of()));
        m.commands.put(53, "");
        p.openInventory(inv);
    }

    private void openDecision(Player p, String id) {
        Views.Decision d = view.decision(id).orElse(null);
        if (d == null || !d.isOpen()) {
            openMain(p);
            return;
        }
        Menu m = new Menu();
        Inventory inv = Bukkit.createInventory(m, 27, Component.text("Decisione " + d.id()));
        m.inventory = inv;
        List<Component> lore = new ArrayList<>();
        wrap(d.question(), lore, NamedTextColor.WHITE);
        if (d.context() != null) {
            for (String c : d.context().split("\n")) {
                wrap(c, lore, NamedTextColor.GRAY);
            }
        }
        inv.setItem(
                4,
                item(
                        Material.PAPER,
                        Component.text(d.kind(), NamedTextColor.GOLD),
                        lore.subList(0, Math.min(lore.size(), 20))));
        List<String> options = d.options() == null ? List.of() : d.options();
        int[] slots = {10, 12, 14, 16, 11, 13, 15};
        for (int i = 0; i < options.size() && i < slots.length; i++) {
            String o = options.get(i);
            Material mat =
                    i == 0 ? Material.LIME_WOOL : i == options.size() - 1 ? Material.RED_WOOL : Material.YELLOW_WOOL;
            inv.setItem(
                    slots[i],
                    item(
                            mat,
                            Component.text((i + 1) + ". " + o, NamedTextColor.WHITE),
                            List.of(line(
                                    "Per aggiungere un testo usa /agent answer " + d.id() + " " + (i + 1) + " <testo>",
                                    NamedTextColor.DARK_GRAY))));
            m.commands.put(slots[i], "agent answer " + d.id() + " " + (i + 1));
        }
        inv.setItem(22, item(Material.ARROW, Component.text("Indietro", NamedTextColor.GRAY), List.of()));
        m.submenus.put(22, "");
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Menu m)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)
                || e.getClickedInventory() != e.getView().getTopInventory()) {
            return;
        }
        int slot = e.getRawSlot();
        if (m.submenus.containsKey(slot)) {
            String id = m.submenus.get(slot);
            Bukkit.getGlobalRegionScheduler().execute(dev(), () -> {
                if (id.isEmpty()) {
                    openMain(p);
                } else {
                    openDecision(p, id);
                }
            });
        } else if (m.commands.containsKey(slot)) {
            String cmd = m.commands.get(slot);
            p.closeInventory();
            if (!cmd.isEmpty()) {
                p.performCommand(cmd);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder(false) instanceof Menu) {
            e.setCancelled(true);
        }
    }

    private static org.bukkit.plugin.Plugin dev() {
        return Bukkit.getPluginManager().getPlugin("AgentCraftServer");
    }

    private static Material head(Views.Agent a) {
        return a.active() ? Material.PLAYER_HEAD : Material.SKELETON_SKULL;
    }

    private static Component name(String text, String hex) {
        TextColor c =
                hex != null && hex.matches("#[0-9a-fA-F]{6}") ? TextColor.fromHexString(hex) : NamedTextColor.WHITE;
        return Component.text(text, c);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static void wrap(String text, List<Component> out, NamedTextColor color) {
        String rest = text == null ? "" : text.strip();
        while (!rest.isEmpty() && out.size() < 24) {
            int cut = rest.length() <= 48 ? rest.length() : Math.max(rest.lastIndexOf(' ', 48), 20);
            out.add(line(rest.substring(0, cut).strip(), color));
            rest = rest.substring(cut).strip();
        }
    }

    private static String cut(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static ItemStack item(Material m, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }
}
