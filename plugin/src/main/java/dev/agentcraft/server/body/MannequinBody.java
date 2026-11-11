package dev.agentcraft.server.body;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.persistence.PersistentDataType;

/**
 * Vanilla Mannequin entity, no dependencies. It has no AI of its own: the plugin moves it along
 * its own paths. Not persistent, invulnerable, silent and not pushable.
 */
public final class MannequinBody implements AgentBody {
    private final Mannequin entity;

    private MannequinBody(Mannequin entity) {
        this.entity = entity;
    }

    public static MannequinBody spawn(Location at, String agentId, String skinName, NamespacedKey tag) {
        Mannequin m = at.getWorld().spawn(at, Mannequin.class, e -> {
            e.setPersistent(false);
            e.setInvulnerable(true);
            e.setSilent(true);
            e.setCollidable(false);
            e.setImmovable(true);
            e.setGravity(false);
            e.setAI(false);
            e.setRemoveWhenFarAway(false);
            e.setCustomNameVisible(true);
            e.getPersistentDataContainer().set(tag, PersistentDataType.STRING, agentId);
            if (skinName != null && !skinName.isBlank()) {
                e.setProfile(
                        ResolvableProfile.resolvableProfile().name(skinName).build());
            }
        });
        return new MannequinBody(m);
    }

    @Override
    public Entity entity() {
        return entity;
    }

    @Override
    public void moveTo(Location to) {
        entity.teleport(to);
    }

    @Override
    public void setLabels(Component name, Component status) {
        entity.customName(name);
        entity.setDescription(status);
    }

    @Override
    public void swingArm() {
        entity.swingMainHand();
    }

    @Override
    public void remove() {
        entity.remove();
    }
}
