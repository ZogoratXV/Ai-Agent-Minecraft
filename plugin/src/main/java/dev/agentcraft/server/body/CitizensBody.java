package dev.agentcraft.server.body;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.HologramTrait;
import net.citizensnpcs.trait.SkinTrait;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;

/**
 * Player-like NPC from Citizens, with Citizens' own pathfinding and skins. NPCs live in an
 * in-memory registry, so they are never written to Citizens' saves. Only loaded when Citizens is
 * installed (see {@link Bodies}).
 */
final class CitizensBody implements AgentBody {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static NPCRegistry registry;

    private final NPC npc;

    private CitizensBody(NPC npc) {
        this.npc = npc;
    }

    private static synchronized NPCRegistry registry() {
        if (registry == null) {
            registry = CitizensAPI.createInMemoryNPCRegistry("agentcraft");
        }
        return registry;
    }

    static AgentBody spawn(Location at, String agentId, String skinName, NamespacedKey tag) {
        NPC npc = registry().createNPC(EntityType.PLAYER, agentId);
        npc.setProtected(true);
        if (skinName != null && !skinName.isBlank()) {
            npc.getOrAddTrait(SkinTrait.class).setSkinName(skinName, true);
        }
        npc.getOrAddTrait(HologramTrait.class).addLine("");
        npc.spawn(at, e -> e.getPersistentDataContainer().set(tag, PersistentDataType.STRING, agentId));
        return new CitizensBody(npc);
    }

    @Override
    public Entity entity() {
        return npc.getEntity();
    }

    @Override
    public boolean isValid() {
        return npc.isSpawned() && npc.getEntity() != null && npc.getEntity().isValid();
    }

    @Override
    public Location location() {
        Entity e = npc.getEntity();
        return e != null ? e.getLocation() : npc.getStoredLocation();
    }

    @Override
    public void moveTo(Location to) {
        npc.getNavigator().cancelNavigation();
        npc.teleport(to, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    @Override
    public boolean navigate(Location to) {
        npc.getNavigator().setTarget(to);
        return true;
    }

    @Override
    public void setLabels(Component name, Component status) {
        String legacyName = LEGACY.serialize(name);
        if (!legacyName.equals(npc.getFullName())) {
            npc.setName(legacyName);
        }
        npc.getOrAddTrait(HologramTrait.class).setLine(0, LEGACY.serialize(status));
    }

    @Override
    public void swingArm() {
        if (npc.getEntity() instanceof LivingEntity le) {
            le.swingMainHand();
        }
    }

    @Override
    public void remove() {
        npc.destroy();
    }
}
