package dev.agentcraft.server.config;

import java.util.Locale;

/** Block id helpers: "TNT", "minecraft:tnt" and "tnt[foo=bar]" all normalize to "minecraft:tnt". */
public final class BlockIds {
    private BlockIds() {}

    public static String normalize(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        int state = s.indexOf('[');
        if (state >= 0) {
            s = s.substring(0, state);
        }
        return s.contains(":") ? s : "minecraft:" + s;
    }
}
