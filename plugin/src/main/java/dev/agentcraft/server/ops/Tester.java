package dev.agentcraft.server.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentcraft.server.config.PluginSettings;
import dev.agentcraft.server.security.CommandPolicy;
import dev.agentcraft.server.tools.ToolFailure;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

/**
 * player.simulate: a test player that runs commands, chats, moves, clicks blocks and inventory
 * slots, so the QA agent can try plugins on the staging server. It is a Citizens NPC (a real Player
 * entity), never op, with only the permissions in qa.tester-permissions. Needs Citizens.
 */
public final class Tester {

    private static final int MAX_TICKS = 20 * 120;

    private final Plugin plugin;
    private final CommandPolicy policy;
    private final PluginSettings.Qa settings;
    private final Supplier<Location> base;
    private Object npc; // net.citizensnpcs.api.npc.NPC, kept as Object so the class loads without Citizens
    private PermissionAttachment perms;
    private boolean busy;

    public Tester(Plugin plugin, CommandPolicy policy, PluginSettings.Qa settings, Supplier<Location> base) {
        this.plugin = plugin;
        this.policy = policy;
        this.settings = settings;
        this.base = base;
    }

    public CompletableFuture<JsonObject> run(JsonArray actions) {
        if (busy) {
            throw new ToolFailure("busy", "the test player is already running a simulation");
        }
        if (actions.isEmpty() || actions.size() > 200) {
            throw new ToolFailure("invalid_args", "1-200 actions");
        }
        Player p = player();
        busy = true;
        CompletableFuture<JsonObject> done = new CompletableFuture<>();
        JsonArray results = new JsonArray();
        step(p, actions, 0, 0, results, done);
        return done.whenComplete((r, e) -> busy = false);
    }

    private void step(
            Player p, JsonArray actions, int i, int waited, JsonArray results, CompletableFuture<JsonObject> done) {
        if (i >= actions.size() || !p.isValid()) {
            JsonObject out = new JsonObject();
            out.addProperty("tester", p.getName());
            out.add("results", results);
            out.add("openInventory", inventory(p));
            out.addProperty(
                    "note",
                    "messages sent to the test player are not captured: check effects with server_logs,"
                            + " the inventory and the world");
            done.complete(out);
            return;
        }
        JsonObject a = actions.get(i).isJsonObject() ? actions.get(i).getAsJsonObject() : new JsonObject();
        String kind = a.has("kind") ? a.get("kind").getAsString() : "";
        JsonObject r = new JsonObject();
        r.addProperty("kind", kind);
        int delay = 1;
        try {
            switch (kind) {
                case "command" -> {
                    String raw = ServerOps.str(a, "command");
                    CommandPolicy.Verdict v = policy.checkAsPlayer(raw.startsWith("/") ? raw.substring(1) : raw);
                    if (v instanceof CommandPolicy.Denied d) {
                        throw new ToolFailure("forbidden", d.reason());
                    }
                    String cmd = ((CommandPolicy.Allowed) v).command();
                    r.addProperty("command", cmd);
                    r.addProperty("ok", p.performCommand(cmd));
                }
                case "chat" -> {
                    String text = ServerOps.str(a, "text");
                    if (text.startsWith("/") || text.length() > 256) {
                        throw new ToolFailure("invalid_args", "chat text: plain text up to 256 characters");
                    }
                    p.chat(text);
                    r.addProperty("ok", true);
                }
                case "move" -> {
                    Location to = location(a.getAsJsonObject("to"));
                    r.addProperty("ok", p.teleport(to));
                }
                case "interact" -> {
                    Location at = location(a.getAsJsonObject("at"));
                    Block b = at.getBlock();
                    boolean right = !"left".equals(a.has("hand") ? a.get("hand").getAsString() : "right");
                    if (p.getLocation().distanceSquared(at) > 36) {
                        p.teleport(at.clone().add(0.5, 1, 0.5));
                    }
                    PlayerInteractEvent ev = new PlayerInteractEvent(
                            p,
                            right ? Action.RIGHT_CLICK_BLOCK : Action.LEFT_CLICK_BLOCK,
                            p.getInventory().getItemInMainHand(),
                            b,
                            BlockFace.UP,
                            EquipmentSlot.HAND);
                    Bukkit.getPluginManager().callEvent(ev);
                    r.addProperty("block", b.getType().getKey().toString());
                    r.addProperty("cancelled", ev.useInteractedBlock() == org.bukkit.event.Event.Result.DENY);
                    r.addProperty("ok", true);
                }
                case "click_slot" -> {
                    int slot = a.get("slot").getAsInt();
                    InventoryView view = p.getOpenInventory();
                    if (view.getTopInventory().getType() == InventoryType.CRAFTING) {
                        throw new ToolFailure("not_found", "no inventory is open");
                    }
                    if (slot < 0 || slot >= view.countSlots()) {
                        throw new ToolFailure("invalid_args", "slot out of range");
                    }
                    InventoryClickEvent ev = new InventoryClickEvent(
                            view, view.getSlotType(slot), slot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
                    Bukkit.getPluginManager().callEvent(ev);
                    r.addProperty("cancelled", ev.isCancelled());
                    r.addProperty("ok", true);
                }
                case "wait" -> {
                    int ticks = a.get("ticks").getAsInt();
                    if (ticks < 1 || waited + ticks > MAX_TICKS) {
                        throw new ToolFailure("invalid_args", "waits add up to at most " + MAX_TICKS + " ticks");
                    }
                    delay = ticks;
                    waited += ticks;
                    r.addProperty("ok", true);
                }
                default -> throw new ToolFailure("invalid_args", "unknown action '" + kind + "'");
            }
        } catch (ToolFailure f) {
            r.addProperty("ok", false);
            r.addProperty("error", f.code() + ": " + f.getMessage());
        } catch (RuntimeException e) {
            r.addProperty("ok", false);
            r.addProperty("error", "the server threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        r.addProperty("openInventory", title(p));
        results.add(r);
        int next = i + 1;
        int w = waited;
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> step(p, actions, next, w, results, done), delay);
    }

    private static Location location(JsonObject pos) {
        if (pos == null) {
            throw new ToolFailure("invalid_args", "missing position");
        }
        World w = Bukkit.getWorld(ServerOps.str(pos, "world"));
        if (w == null) {
            throw new ToolFailure("invalid_args", "unknown world");
        }
        double x = pos.get("x").getAsDouble();
        double y = pos.get("y").getAsDouble();
        double z = pos.get("z").getAsDouble();
        if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || y < w.getMinHeight() || y > w.getMaxHeight()) {
            throw new ToolFailure("invalid_args", "position out of the world");
        }
        return new Location(w, x, y, z);
    }

    private static String title(Player p) {
        InventoryView v = p.getOpenInventory();
        if (v.getTopInventory().getType() == InventoryType.CRAFTING) {
            return "";
        }
        return PlainTextComponentSerializer.plainText().serialize(v.title());
    }

    private static JsonElement inventory(Player p) {
        InventoryView v = p.getOpenInventory();
        if (v.getTopInventory().getType() == InventoryType.CRAFTING) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        JsonObject o = new JsonObject();
        o.addProperty("title", title(p));
        JsonArray items = new JsonArray();
        ItemStack[] contents = v.getTopInventory().getContents();
        for (int s = 0; s < contents.length && s < 90; s++) {
            ItemStack it = contents[s];
            if (it == null || it.getType().isAir()) {
                continue;
            }
            JsonObject e = new JsonObject();
            e.addProperty("slot", s);
            e.addProperty("item", it.getType().getKey().toString());
            e.addProperty("amount", it.getAmount());
            e.addProperty("name", PlainTextComponentSerializer.plainText().serialize(it.effectiveName()));
            items.add(e);
        }
        o.add("items", items);
        return o;
    }

    private Player player() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            throw new ToolFailure("not_found", "the test player needs Citizens on the staging server");
        }
        Player p = CitizensTester.spawn(this, settings.testerName(), base.get());
        if (perms == null || !p.getEffectivePermissions().contains(perms)) {
            p.setOp(false);
            perms = p.addAttachment(plugin);
            for (String perm : settings.testerPermissions()) {
                perms.setPermission(perm, true);
            }
        }
        return p;
    }

    Object npc() {
        return npc;
    }

    void npc(Object npc) {
        this.npc = npc;
    }

    public void remove() {
        if (npc != null) {
            CitizensTester.remove(this);
        }
    }
}
