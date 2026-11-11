package dev.agentcraft.server.body;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/** The in-world representation of one agent. Mannequin (vanilla) today, Citizens next. */
public interface AgentBody {
    Entity entity();

    default boolean isValid() {
        return entity().isValid();
    }

    default Location location() {
        return entity().getLocation();
    }

    /** Moves the body one small step; the caller interpolates the path. */
    void moveTo(Location to);

    /**
     * Walks to {@code to} with the body's own pathfinding, if it has one (Citizens). Returns false
     * when the caller must plan and interpolate the path itself (Mannequin).
     */
    default boolean navigate(Location to) {
        return false;
    }

    /** Name line and status line above the head. */
    void setLabels(Component name, Component status);

    void swingArm();

    void remove();
}
