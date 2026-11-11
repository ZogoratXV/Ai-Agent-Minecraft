package dev.agentcraft.server.command;

import com.google.gson.JsonObject;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Who issued a command, as sent to the foreman for its log. The console has the nil UUID. */
public record Actor(UUID uuid, String name) {
    public static final UUID CONSOLE = new UUID(0, 0);

    public static Actor of(CommandSender sender) {
        if (sender instanceof Player p) {
            return new Actor(p.getUniqueId(), p.getName());
        }
        return new Actor(CONSOLE, "Console");
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid.toString());
        o.addProperty("name", name.length() > 16 ? name.substring(0, 16) : name);
        return o;
    }
}
