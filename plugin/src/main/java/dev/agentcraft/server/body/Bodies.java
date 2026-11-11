package dev.agentcraft.server.body;

import dev.agentcraft.server.config.PluginSettings.NpcBackend;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;

/**
 * Chooses the body implementation. Citizens classes are only touched when Citizens is enabled,
 * and any Citizens failure falls back to Mannequin for the rest of the session.
 */
public final class Bodies {
    private final NpcBackend backend;
    private final Logger log;
    private boolean citizensBroken;

    public Bodies(NpcBackend backend, Logger log) {
        this.backend = backend;
        this.log = log;
    }

    public boolean usesCitizens() {
        return !citizensBroken
                && backend != NpcBackend.MANNEQUIN
                && Bukkit.getPluginManager().isPluginEnabled("Citizens");
    }

    public AgentBody spawn(Location at, String agentId, String skinName, NamespacedKey tag) {
        if (usesCitizens()) {
            try {
                return CitizensBody.spawn(at, agentId, skinName, tag);
            } catch (Throwable t) { // LinkageError from an incompatible Citizens build included
                citizensBroken = true;
                log.warning("Citizens NPC failed (" + t + "); using Mannequin bodies instead");
            }
        } else if (backend == NpcBackend.CITIZENS && !citizensBroken) {
            log.warning("npc.backend is 'citizens' but Citizens is not enabled; using Mannequin bodies");
            citizensBroken = true;
        }
        return MannequinBody.spawn(at, agentId, skinName, tag);
    }
}
