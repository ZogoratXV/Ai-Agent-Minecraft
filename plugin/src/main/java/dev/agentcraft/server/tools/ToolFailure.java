package dev.agentcraft.server.tools;

/** A tool call that cannot be done; becomes {@code tool.result} with this error code. */
public final class ToolFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;

    public ToolFailure(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
