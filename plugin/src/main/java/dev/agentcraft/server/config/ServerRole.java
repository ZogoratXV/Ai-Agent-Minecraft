package dev.agentcraft.server.config;

import java.util.Locale;

/** Decided only by the plugin's config.yml; the foreman cannot change it. */
public enum ServerRole {
    PRODUCTION,
    STAGING;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ServerRole parse(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
            case "production" -> PRODUCTION;
            case "staging" -> STAGING;
            default -> throw new IllegalArgumentException("server.role must be 'production' or 'staging'");
        };
    }
}
