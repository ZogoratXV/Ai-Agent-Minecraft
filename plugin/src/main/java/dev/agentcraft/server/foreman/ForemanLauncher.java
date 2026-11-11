package dev.agentcraft.server.foreman;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts and supervises the foreman bundled in the jar ({@code foreman/foreman.mjs}) with the
 * local Node, so admins only install the plugin. Everything runs on a daemon thread; the server
 * thread never waits for the process. If a foreman is already listening on the bridge port (for
 * example started by hand), nothing is launched and that one is used.
 */
public final class ForemanLauncher {
    public static final int MIN_NODE_MAJOR = 22;
    private static final long LOG_ROTATE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_FAST_FAILURES = 5;

    /** Version of the Claude Agent SDK installed next to the bundle for the claude backend. */
    public static final String AGENT_SDK_VERSION = "0.3.322";

    public record Options(
            String node,
            String npm,
            String backend,
            URI bridge,
            List<String> extraArgs,
            Path workDir,
            String token,
            Path apiKeyFile) {}

    private final Options opts;
    private final Supplier<InputStream> bundle;
    private final Logger log;
    private volatile boolean stopped;
    private volatile Process process;
    private Thread supervisor;

    public ForemanLauncher(Options opts, Supplier<InputStream> bundle, Logger log) {
        // absolute: the process runs inside workDir, a relative script path would resolve twice
        this.opts = new Options(
                opts.node(),
                opts.npm(),
                opts.backend(),
                opts.bridge(),
                opts.extraArgs(),
                opts.workDir().toAbsolutePath(),
                opts.token(),
                opts.apiKeyFile());
        this.bundle = bundle;
        this.log = log;
    }

    // ------------------------------------------------------------------ pure helpers (tested)

    /** "v22.23.4" -> 22; empty if the output is not a Node version. */
    public static Optional<Integer> nodeMajor(String versionOutput) {
        Matcher m = Pattern.compile("^v?(\\d+)\\.\\d+").matcher(versionOutput.strip());
        return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
    }

    /** Command line of the foreman process. The token goes in the environment, never here. */
    public static List<String> command(Options o, Path script) {
        return command(o.node(), o, script);
    }

    static List<String> command(String nodeExecutable, Options o, Path script) {
        List<String> cmd = new ArrayList<>(List.of(
                nodeExecutable,
                script.toString(),
                "--backend",
                o.backend(),
                "--host",
                o.bridge().getHost().replace("[", "").replace("]", ""),
                "--port",
                String.valueOf(o.bridge().getPort() > 0 ? o.bridge().getPort() : 7878),
                "--home",
                o.workDir().resolve("home").toString()));
        cmd.addAll(o.extraArgs());
        return cmd;
    }

    /** A random token of 48 hex characters, written with owner-only permissions. */
    public static String createTokenFile(Path file) throws IOException {
        byte[] bytes = new byte[24];
        new java.security.SecureRandom().nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, token + "\n");
        try {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX file system (Windows): the plugin folder's own permissions apply
        }
        return token;
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        supervisor = new Thread(this::supervise, "AgentCraft-Foreman");
        supervisor.setDaemon(true);
        supervisor.start();
    }

    public void stop() {
        stopped = true;
        Process p = process;
        if (p != null && p.isAlive()) {
            p.destroy(); // SIGTERM: the foreman saves and closes
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        if (supervisor != null) {
            supervisor.interrupt();
        }
    }

    /** Node, npm and the PATH additions actually used (resolved at start-up, possibly downloaded). */
    private String node;

    private String npm;
    private final List<Path> extraPath = new ArrayList<>();

    /**
     * Finds Node (and Maven for the claude backend): the configured ones, the machine's own, or
     * pinned downloads into the plugin folder when the machine has none (Pelican/Pterodactyl Java
     * containers). Returns false when the foreman cannot run.
     */
    private boolean resolveTools() throws IOException, InterruptedException {
        Runtimes runtimes = new Runtimes(opts.workDir().getParent().resolve("runtime"), log);
        boolean auto = "auto".equals(opts.node());
        node = auto ? "node" : opts.node();
        npm = "auto".equals(opts.npm()) ? "npm" : opts.npm();
        if (!nodeOk(node, !auto)) {
            if (!auto) {
                return false;
            }
            Path bin = runtimes.ensureNode();
            node = bin.resolve(Runtimes.nodeExecutable()).toString();
            if ("auto".equals(opts.npm())) {
                npm = bin.resolve(Runtimes.npmExecutable()).toString();
            }
            extraPath.add(bin);
            if (!nodeOk(node, true)) {
                return false;
            }
        }
        if ("claude".equals(opts.backend()) && !works("mvn", "-v")) {
            extraPath.add(runtimes.ensureMaven());
        }
        return true;
    }

    private Map<String, String> withPath(Map<String, String> env) {
        if (!extraPath.isEmpty()) {
            String path = String.join(
                    java.io.File.pathSeparator,
                    extraPath.stream().map(Path::toString).toList());
            env.put("PATH", path + java.io.File.pathSeparator + env.getOrDefault("PATH", ""));
        }
        return env;
    }

    private static boolean works(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void supervise() {
        Path script;
        try {
            if (!resolveTools()) {
                return;
            }
            script = extract();
            if ("claude".equals(opts.backend()) && !ensureAgentSdk()) {
                return;
            }
        } catch (IOException e) {
            log.warning("cannot prepare the foreman: " + e.getMessage());
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        int fastFailures = 0;
        long backoffMs = 2_000;
        while (!stopped) {
            if (isListening()) {
                log.info("a foreman is already running at " + opts.bridge() + ": using it");
                sleep(30_000);
                continue;
            }
            long started = System.currentTimeMillis();
            try {
                rotateLog();
                ProcessBuilder pb = new ProcessBuilder(command(node, opts, script))
                        .directory(opts.workDir().toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(
                                ProcessBuilder.Redirect.appendTo(logFile().toFile()));
                Map<String, String> env = withPath(pb.environment());
                env.put("AGENTCRAFT_TOKEN", opts.token());
                apiKey().ifPresent(k -> env.put("ANTHROPIC_API_KEY", k));
                process = pb.start();
                log.info("foreman started (pid " + process.pid() + ", backend " + opts.backend() + "), log: "
                        + logFile());
                int code = process.waitFor();
                if (stopped) {
                    return;
                }
                log.warning("foreman exited with code " + code + ", see " + logFile());
            } catch (IOException e) {
                log.warning("cannot start the foreman: " + e.getMessage());
            } catch (InterruptedException e) {
                return;
            }
            boolean fast = System.currentTimeMillis() - started < 30_000;
            fastFailures = fast ? fastFailures + 1 : 0;
            if (fastFailures >= MAX_FAST_FAILURES) {
                log.severe("the foreman keeps failing at start-up; giving up until the next server start. See "
                        + logFile());
                return;
            }
            backoffMs = fast ? Math.min(60_000, backoffMs * 2) : 2_000;
            sleep(backoffMs);
        }
    }

    /** Whether {@code executable} is Node 22+. With {@code report} a failure is logged. */
    private boolean nodeOk(String executable, boolean report) {
        try {
            Process p = new ProcessBuilder(executable, "--version")
                    .redirectErrorStream(true)
                    .start();
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readNBytes(200));
            }
            p.waitFor(10, TimeUnit.SECONDS);
            Optional<Integer> major = nodeMajor(out);
            if (major.isEmpty() || major.get() < MIN_NODE_MAJOR) {
                if (report) {
                    log.warning("the foreman needs Node " + MIN_NODE_MAJOR + "+, found '" + out.strip()
                            + "'. Set foreman.node: auto to download it, or install it.");
                }
                return false;
            }
            return true;
        } catch (IOException e) {
            if (report) {
                log.warning("Node not found ('" + executable + "'). Set foreman.node: auto to download it.");
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The key from foreman.api-key-file, if set. Only the foreman process gets it. */
    private Optional<String> apiKey() {
        if (opts.apiKeyFile() == null) {
            return Optional.empty();
        }
        try {
            String k = Files.readString(opts.apiKeyFile()).strip();
            return k.isEmpty() ? Optional.empty() : Optional.of(k);
        } catch (IOException e) {
            log.warning("cannot read foreman.api-key-file: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The claude backend needs the Claude Agent SDK (it ships a native binary per platform, so it
     * cannot live in the jar). Installed once with npm next to the bundle.
     */
    private boolean ensureAgentSdk() throws IOException, InterruptedException {
        Path pkg = opts.workDir().resolve("node_modules/@anthropic-ai/claude-agent-sdk/package.json");
        if (Files.exists(pkg) && Files.readString(pkg).contains("\"version\": \"" + AGENT_SDK_VERSION + "\"")) {
            return true;
        }
        Files.writeString(
                opts.workDir().resolve("package.json"),
                "{\n  \"private\": true,\n  \"type\": \"module\",\n  \"dependencies\": {\n"
                        + "    \"@anthropic-ai/claude-agent-sdk\": \"" + AGENT_SDK_VERSION + "\"\n  }\n}\n");
        log.info("installing the Claude Agent SDK " + AGENT_SDK_VERSION + " with npm (first start only)...");
        ProcessBuilder npmInstall = new ProcessBuilder(npm, "install", "--omit=dev", "--no-audit", "--no-fund")
                .directory(opts.workDir().toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile().toFile()));
        withPath(npmInstall.environment());
        Process p = npmInstall.start();
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            log.severe("npm install timed out; see " + logFile());
            return false;
        }
        if (p.exitValue() != 0) {
            log.severe("npm install failed (exit " + p.exitValue() + "); see " + logFile()
                    + ". Check that npm can reach registry.npmjs.org.");
            return false;
        }
        log.info("Claude Agent SDK installed");
        return true;
    }

    /** Copies the bundled script out of the jar when it changed. */
    private Path extract() throws IOException {
        Files.createDirectories(opts.workDir());
        Path script = opts.workDir().resolve("foreman.mjs");
        byte[] bytes;
        try (InputStream in = bundle.get()) {
            if (in == null) {
                throw new IOException("this jar was built without the foreman bundle");
            }
            bytes = in.readAllBytes();
        }
        if (Files.exists(script) && sha256(Files.readAllBytes(script)).equals(sha256(bytes))) {
            return script;
        }
        Path tmp = opts.workDir().resolve("foreman.mjs.tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, script, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return script;
    }

    private boolean isListening() {
        String host = opts.bridge().getHost().replace("[", "").replace("]", "");
        int port = opts.bridge().getPort() > 0 ? opts.bridge().getPort() : 7878;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private Path logFile() {
        return opts.workDir().resolve("foreman.log");
    }

    private void rotateLog() throws IOException {
        Path f = logFile();
        if (Files.exists(f) && Files.size(f) > LOG_ROTATE_BYTES) {
            Files.move(f, opts.workDir().resolve("foreman.log.1"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
