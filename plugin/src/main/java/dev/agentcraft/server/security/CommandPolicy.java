package dev.agentcraft.server.security;

import dev.agentcraft.server.config.CommandText;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Decides whether an agent may run a server command. The configured whitelist is matched by
 * leading words; a fixed deny list is applied first and cannot be overridden by config.
 */
public final class CommandPolicy {

    /** Never runnable by an agent, whatever the whitelist says (namespaced forms included). */
    public static final Set<String> FIXED_DENY = Set.of(
            "op",
            "deop",
            "ban",
            "ban-ip",
            "banlist",
            "pardon",
            "pardon-ip",
            "whitelist",
            "kick",
            "lp",
            "luckperms",
            "perm",
            "perms",
            "permission",
            "permissions",
            "pex",
            "stop",
            "restart",
            "reload",
            "rl",
            "execute",
            "sudo",
            "plugman",
            "function",
            "datapack",
            "save-off",
            "transfer");

    public static final int MAX_LENGTH = 256;

    public sealed interface Verdict permits Allowed, Denied {}

    public record Allowed(String command) implements Verdict {}

    public record Denied(String reason) implements Verdict {}

    private final List<String[]> whitelist;

    public CommandPolicy(List<String> whitelist) {
        this.whitelist = whitelist.stream()
                .map(CommandText::normalize)
                .filter(s -> !s.isEmpty())
                .map(s -> s.split(" "))
                .toList();
    }

    public Verdict check(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Denied("empty command");
        }
        if (raw.length() > MAX_LENGTH) {
            return new Denied("command longer than " + MAX_LENGTH + " characters");
        }
        if (raw.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            return new Denied("control characters are not allowed");
        }
        String command = CommandText.normalize(raw);
        String root = CommandText.rootLabel(command);
        if (FIXED_DENY.contains(root)) {
            return new Denied("'" + root + "' is never allowed for agents");
        }
        String[] words = command.split(" ");
        words[0] = root;
        for (String[] entry : whitelist) {
            if (startsWith(words, entry)) {
                return new Allowed(command);
            }
        }
        return new Denied("'" + root + "' is not in commands.whitelist");
    }

    /**
     * For the test player: it has only the permissions given in qa.tester-permissions, like a normal
     * player, so the whitelist does not apply; the fixed deny list still does.
     */
    public Verdict checkAsPlayer(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Denied("empty command");
        }
        if (raw.length() > MAX_LENGTH) {
            return new Denied("command longer than " + MAX_LENGTH + " characters");
        }
        if (raw.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            return new Denied("control characters are not allowed");
        }
        String command = CommandText.normalize(raw);
        String root = CommandText.rootLabel(command);
        if (FIXED_DENY.contains(root)) {
            return new Denied("'" + root + "' is never allowed for agents");
        }
        return new Allowed(command);
    }

    private static boolean startsWith(String[] words, String[] entry) {
        if (entry.length > words.length) {
            return false;
        }
        String[] head = Arrays.copyOf(words, entry.length);
        head[0] = CommandText.rootLabel(head[0]);
        String[] e = entry.clone();
        e[0] = CommandText.rootLabel(e[0]);
        return Arrays.equals(head, e);
    }
}
