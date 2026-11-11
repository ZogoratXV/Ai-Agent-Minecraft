package dev.agentcraft.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.agentcraft.server.security.CommandPolicy;
import dev.agentcraft.server.security.CommandPolicy.Allowed;
import dev.agentcraft.server.security.CommandPolicy.Denied;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommandPolicyTest {

    private final CommandPolicy policy = new CommandPolicy(List.of("list", "time query", "say", "op", "lp user"));

    @Test
    void allowsWhitelistedCommandsByLeadingWords() {
        assertEquals("time query day", ((Allowed) policy.check("/time query day")).command());
        assertInstanceOf(Allowed.class, policy.check("list"));
        assertInstanceOf(Allowed.class, policy.check("minecraft:say ciao"));
        assertInstanceOf(Allowed.class, policy.check("  //SAY   ciao   a tutti "));
    }

    @Test
    void refusesWhatIsNotWhitelisted() {
        assertInstanceOf(Denied.class, policy.check("time set day"));
        assertInstanceOf(Denied.class, policy.check("give @p diamond 64"));
        assertInstanceOf(Denied.class, policy.check("timequery"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "op Zogorat",
                "minecraft:op Zogorat",
                "/OP Zogorat",
                "lp user Zogorat permission set *",
                "luckperms:lp user x",
                "execute as @a run op @s",
                "ban someone",
                "pardon someone",
                "whitelist off",
                "stop",
                "reload confirm",
                "deop Zogorat"
            })
    void fixedDenyListWinsOverWhitelist(String command) {
        assertInstanceOf(Denied.class, policy.check(command));
    }

    @Test
    void refusesControlCharactersEmptyAndLongCommands() {
        assertInstanceOf(Denied.class, policy.check("say a\nop me"));
        assertInstanceOf(Denied.class, policy.check("   "));
        assertInstanceOf(Denied.class, policy.check(null));
        assertInstanceOf(Denied.class, policy.check("say " + "x".repeat(300)));
    }
}
