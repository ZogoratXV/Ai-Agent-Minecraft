package dev.tessera.testplugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Plugin Bukkit "normale", compilato solo contro paper-api: nessun riferimento a Tessera. */
public final class TesseraTestPlugin extends JavaPlugin implements Listener {

    private final AtomicInteger ticks = new AtomicInteger();

    @Override
    public void onLoad() {
        getLogger().info("onLoad: server=" + Bukkit.getName() + " " + Bukkit.getVersion());
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (ticks.addAndGet(20) % 1200 == 0) {
                    getLogger().info("Scheduler attivo: " + ticks.get() + " tick, giocatori online: " + Bukkit.getOnlinePlayers().size());
                }
            }
        }.runTaskTimer(this, 20L, 20L);
        getLogger().info("onEnable: mondi = " + Bukkit.getWorlds().stream().map(World::getName).toList());
    }

    @Override
    public void onDisable() {
        getLogger().info("onDisable: arrivederci");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("hello")) {
            String who = args.length > 0 ? args[0] : sender.getName();
            sender.sendMessage(ChatColor.GREEN + "Ciao " + who + "! " + ChatColor.GRAY + "(" + Bukkit.getName() + " " + Bukkit.getBukkitVersion() + ")");
            return true;
        }
        if (command.getName().equals("tesseratest")) {
            runChecks(sender);
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equals("hello") && args.length == 1) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            names.add("mondo");
            return names;
        }
        return List.of();
    }

    private void runChecks(CommandSender sender) {
        List<String> report = new ArrayList<>();
        World world = Bukkit.getWorlds().getFirst();
        report.add("mondo principale: " + world.getName() + " (" + world.getEnvironment() + "), seed " + world.getSeed());
        report.add("altezza: " + world.getMinHeight() + ".." + world.getMaxHeight() + ", ora " + world.getTime());

        Block block = world.getBlockAt(0, world.getMaxHeight() - 2, 0);
        Material before = block.getType();
        block.setType(Material.GOLD_BLOCK);
        Material after = block.getType();
        block.setType(before);
        report.add("blocco (0," + block.getY() + ",0): " + before + " -> " + after + " -> " + block.getType());

        report.add("Material: STONE.isBlock=" + Material.STONE.isBlock() + " isSolid=" + Material.STONE.isSolid()
                + ", DIAMOND_SWORD.isItem=" + Material.DIAMOND_SWORD.isItem() + " maxStack=" + Material.DIAMOND_SWORD.getMaxStackSize());
        org.bukkit.inventory.ItemStack stack = org.bukkit.inventory.ItemStack.of(Material.DIAMOND, 3);
        report.add("ItemStack: " + stack.getType() + " x" + stack.getAmount() + " (max " + stack.getMaxStackSize() + ")");

        report.add("config greeting: " + getConfig().getString("greeting"));
        report.add("permesso tesseratest.admin registrato: " + (getServer().getPluginManager().getPermission("tesseratest.admin") != null));
        report.add("plugin attivi: " + getServer().getPluginManager().getPlugins().length);

        Bukkit.getScheduler().runTaskLater(this, () -> getLogger().info("runTaskLater eseguito dopo 5 tick"), 5L);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> getLogger().info("task asincrono su " + Thread.currentThread().getName()));

        report.forEach(line -> sender.sendMessage(ChatColor.AQUA + "[check] " + ChatColor.WHITE + line));
        getLogger().info("TESSERA-CHECK-OK " + report.size() + " controlli");
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        getLogger().info("ServerLoadEvent: " + event.getType());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        String greeting = ChatColor.translateAlternateColorCodes('&', getConfig().getString("greeting", ""))
                .replace("%player%", event.getPlayer().getName());
        event.setJoinMessage(greeting);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (getConfig().getBoolean("protect-bedrock") && event.getBlock().getType() == Material.BEDROCK) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "La bedrock è protetta da TesseraTest.");
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        event.setFormat(ChatColor.GOLD + "[Tessera] " + ChatColor.RESET + "<%1$s> %2$s");
    }
}
