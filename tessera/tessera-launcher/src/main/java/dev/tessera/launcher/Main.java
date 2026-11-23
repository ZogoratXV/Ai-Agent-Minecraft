package dev.tessera.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Launcher di Tessera: installa/aggiorna server vanilla, Fabric Loader, Fabric API e la mod
 * Tessera, poi avvia il server in un processo figlio e lo riavvia in caso di crash.
 *
 * <pre>
 * java -jar tessera-launcher.jar [--dir percorso] [--install-only] [--accept-eula] [--no-restart]
 * </pre>
 */
public final class Main {

    private static final int MIN_JAVA = 25;
    private static final String KNOT_SERVER = "net.fabricmc.loader.impl.launch.knot.KnotServer";

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(".").toAbsolutePath().normalize();
        boolean installOnly = false;
        boolean acceptEula = false;
        boolean noRestart = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dir" -> dir = Path.of(args[++i]).toAbsolutePath().normalize();
                case "--install-only" -> installOnly = true;
                case "--accept-eula" -> acceptEula = true;
                case "--no-restart" -> noRestart = true;
                case "--version" -> {
                    System.out.println("Tessera launcher " + version());
                    return;
                }
                case "-h", "--help" -> {
                    printHelp();
                    return;
                }
                default -> {
                    System.err.println("Opzione sconosciuta: " + args[i]);
                    printHelp();
                    System.exit(2);
                }
            }
        }

        int javaVersion = Runtime.version().feature();
        if (javaVersion < MIN_JAVA) {
            System.err.println("[Tessera] Minecraft 26.2 richiede Java " + MIN_JAVA + " o superiore (in uso: " + javaVersion + ").");
            System.err.println("[Tessera] Scarica Java 25 da https://adoptium.net/");
            System.exit(1);
        }

        Files.createDirectories(dir);
        System.out.println("[Tessera] Launcher " + version() + " - cartella server: " + dir);
        LauncherConfig config = LauncherConfig.load(dir);

        Installer.Installation installation = new Installer(dir, config).install();
        System.out.println("[Tessera] Installazione pronta: Minecraft " + config.get("minecraft.version")
                + ", Fabric Loader " + config.get("fabric.loader.version")
                + ", Fabric API " + config.get("fabric.api.version"));
        if (installOnly) return;

        if (!ensureEula(dir, config, acceptEula)) {
            System.exit(1);
        }
        run(dir, config, installation, noRestart || !config.flag("auto-restart"));
    }

    private static void run(Path dir, LauncherConfig config, Installer.Installation inst, boolean noRestart) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString());
        cmd.add("-Xms" + config.get("memory.min"));
        cmd.add("-Xmx" + config.get("memory.max"));
        cmd.addAll(config.list("jvm.args"));
        cmd.add("-Dfabric.gameJarPath=" + inst.serverJar());
        cmd.add("-Dlog4j2.formatMsgNoLookups=true");
        cmd.add("-cp");
        cmd.add(inst.classpath().stream().map(Path::toString).collect(Collectors.joining(java.io.File.pathSeparator)));
        cmd.add(KNOT_SERVER);
        cmd.addAll(config.list("server.args"));

        Deque<Long> crashes = new ArrayDeque<>();
        int maxPerHour = config.integer("restart.max-per-hour", 5);
        int delay = config.integer("restart.delay-seconds", 10);
        while (true) {
            System.out.println("[Tessera] Avvio del server...");
            Process process = new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO().start();
            Thread hook = new Thread(() -> {
                if (process.isAlive()) {
                    process.destroy(); // SIGTERM: il server salva i mondi e si ferma
                    try {
                        process.waitFor();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "tessera-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            int code = process.waitFor();
            Runtime.getRuntime().removeShutdownHook(hook);

            if (code == 0) {
                System.out.println("[Tessera] Server fermato correttamente.");
                return;
            }
            System.err.println("[Tessera] Il server è terminato con codice " + code + ".");
            if (noRestart) System.exit(code);

            long now = System.currentTimeMillis();
            crashes.addLast(now);
            while (!crashes.isEmpty() && now - crashes.peekFirst() > 3_600_000L) crashes.removeFirst();
            if (crashes.size() > maxPerHour) {
                System.err.println("[Tessera] Troppi crash nell'ultima ora (" + crashes.size() + "): riavvio automatico sospeso.");
                System.exit(code);
            }
            System.out.println("[Tessera] Riavvio tra " + delay + " secondi (Ctrl+C per annullare)...");
            Thread.sleep(delay * 1000L);
        }
    }

    private static boolean ensureEula(Path dir, LauncherConfig config, boolean acceptFlag) throws IOException {
        Path eula = dir.resolve("eula.txt");
        boolean accepted = Files.exists(eula) && Files.readString(eula).contains("eula=true");
        if (!accepted && (acceptFlag || config.flag("eula"))) accepted = true;
        if (!accepted && System.console() != null) {
            System.out.println("[Tessera] Per avviare il server devi accettare la EULA di Minecraft: https://aka.ms/MinecraftEULA");
            System.out.print("[Tessera] Accetti? (si/no): ");
            String answer = new BufferedReader(new InputStreamReader(System.in)).readLine();
            accepted = answer != null && List.of("si", "sì", "s", "yes", "y", "true").contains(answer.strip().toLowerCase(Locale.ROOT));
        }
        if (!accepted) {
            System.err.println("[Tessera] EULA non accettata. Usa --accept-eula, eula=true in " + LauncherConfig.FILE
                    + " o la variabile TESSERA_EULA=true.");
            return false;
        }
        Files.writeString(eula, "# Accettata tramite il launcher Tessera (https://aka.ms/MinecraftEULA)\neula=true\n");
        return true;
    }

    static String version() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void printHelp() {
        System.out.println("""
                Uso: java -jar tessera-launcher.jar [opzioni]
                  --dir <percorso>   cartella del server (predefinita: cartella corrente)
                  --install-only     scarica/aggiorna i file e termina senza avviare
                  --accept-eula      accetta la EULA di Minecraft (https://aka.ms/MinecraftEULA)
                  --no-restart       non riavviare il server dopo un crash
                  --version          mostra la versione del launcher
                Impostazioni in tessera-launcher.properties (o variabili TESSERA_*).""");
    }

    private Main() {
    }
}
