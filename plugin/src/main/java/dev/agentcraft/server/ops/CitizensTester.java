package dev.agentcraft.server.ops;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/** Citizens access, in its own class so Tester loads even when Citizens is missing. */
final class CitizensTester {

    private static NPCRegistry registry;

    private CitizensTester() {}

    static Player spawn(Tester owner, String name, Location at) {
        NPC npc = (NPC) owner.npc();
        if (npc == null) {
            if (registry == null) {
                registry = CitizensAPI.createInMemoryNPCRegistry("agentcraft-tester");
            }
            npc = registry.createNPC(EntityType.PLAYER, name);
            npc.setProtected(true);
            owner.npc(npc);
        }
        if (!npc.isSpawned()) {
            npc.spawn(at);
        }
        if (!(npc.getEntity() instanceof Player p)) {
            throw new dev.agentcraft.server.tools.ToolFailure("internal", "the test player could not spawn");
        }
        return p;
    }

    static void remove(Tester owner) {
        if (owner.npc() instanceof NPC npc) {
            npc.destroy();
            owner.npc(null);
        }
    }
}
