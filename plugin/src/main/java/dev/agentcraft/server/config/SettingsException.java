package dev.agentcraft.server.config;

import java.util.List;

/** config.yml has one or more invalid values. */
public final class SettingsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final List<String> problems;

    public SettingsException(List<String> problems) {
        super("invalid config.yml: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
