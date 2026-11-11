package dev.agentcraft.server.protocol;

/** A bridge message that cannot be accepted. */
public final class ProtocolException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }
}
