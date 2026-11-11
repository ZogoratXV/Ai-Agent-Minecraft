package dev.agentcraft.server;

import org.bukkit.plugin.PluginManager;

/** Optional plugins found on this server. Sent to the foreman in {@code hello.capabilities}. */
public record Integrations(boolean citizens, boolean worldGuard, boolean worldEdit, boolean luckPerms, boolean spark) {

    public static Integrations detect(PluginManager pm) {
        return new Integrations(
                pm.isPluginEnabled("Citizens"),
                pm.isPluginEnabled("WorldGuard"),
                pm.isPluginEnabled("WorldEdit") || pm.isPluginEnabled("FastAsyncWorldEdit"),
                pm.isPluginEnabled("LuckPerms"),
                pm.isPluginEnabled("spark"));
    }
}
