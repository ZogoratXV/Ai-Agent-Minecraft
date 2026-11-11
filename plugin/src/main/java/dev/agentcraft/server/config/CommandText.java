package dev.agentcraft.server.config;

import java.util.Locale;

/** Normalizes command text for policy matching. */
public final class CommandText {
    private CommandText() {}

    /** Trims, drops leading slashes, collapses whitespace, lowercases the first word. */
    public static String normalize(String command) {
        String s = command.strip();
        while (s.startsWith("/")) {
            s = s.substring(1).stripLeading();
        }
        String[] words = s.split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            return "";
        }
        words[0] = words[0].toLowerCase(Locale.ROOT);
        return String.join(" ", words);
    }

    /** "minecraft:op" -> "op". */
    public static String rootLabel(String normalized) {
        String first = normalized.split(" ", 2)[0];
        int colon = first.indexOf(':');
        return colon >= 0 ? first.substring(colon + 1) : first;
    }
}
