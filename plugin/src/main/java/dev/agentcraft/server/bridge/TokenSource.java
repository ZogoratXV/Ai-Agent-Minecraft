package dev.agentcraft.server.bridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** The shared bridge token: AGENTCRAFT_TOKEN, or the file named in bridge.token-file. Never logged. */
public final class TokenSource {
    public static final int MIN_LENGTH = 16;

    private TokenSource() {}

    public static Optional<String> resolve(Map<String, String> env, Path dataDir, String tokenFile) throws IOException {
        String token = env.get("AGENTCRAFT_TOKEN");
        if ((token == null || token.isBlank()) && tokenFile != null && !tokenFile.isBlank()) {
            Path p = dataDir.resolve(tokenFile);
            token = Files.readString(p).strip();
        }
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        if (token.length() < MIN_LENGTH) {
            throw new IOException("bridge token must be at least " + MIN_LENGTH + " characters");
        }
        return Optional.of(token.strip());
    }
}
