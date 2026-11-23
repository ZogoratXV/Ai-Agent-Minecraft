package dev.tessera;

import dev.tessera.bukkit.TesseraServer;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/** Accesso statico allo stato globale: server vanilla e implementazione Bukkit. */
public final class Tessera {

    public static final String NAME = "Tessera";
    public static final String MINECRAFT_VERSION = "26.2";
    public static final String VERSION = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("tessera")
            .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");

    private static @Nullable MinecraftServer minecraft;
    private static @Nullable TesseraServer bukkit;

    static void attach(MinecraftServer server, TesseraServer bukkitServer) {
        minecraft = server;
        bukkit = bukkitServer;
    }

    static void detach() {
        minecraft = null;
    }

    public static MinecraftServer minecraft() {
        return Objects.requireNonNull(minecraft, "server Minecraft non ancora avviato");
    }

    public static TesseraServer bukkit() {
        return Objects.requireNonNull(bukkit, "server Bukkit non ancora inizializzato");
    }

    public static boolean isRunning() {
        return minecraft != null;
    }

    private Tessera() {
    }
}
