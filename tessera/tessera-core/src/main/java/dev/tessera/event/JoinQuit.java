package dev.tessera.event;

import dev.tessera.Tessera;
import dev.tessera.TesseraMod;
import dev.tessera.bridge.Text;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Messaggi di entrata/uscita controllabili dai plugin. Le mixin trattengono il messaggio vanilla;
 * qui viene lanciato l'evento Bukkit e trasmesso il messaggio scelto dai plugin (o nessuno).
 */
public final class JoinQuit {

    private static final Map<UUID, net.minecraft.network.chat.Component> PENDING_JOIN = new ConcurrentHashMap<>();

    /** Chiamato dalla mixin su PlayerList: true = trattieni il messaggio vanilla. */
    public static boolean captureJoinMessage(ServerPlayer player, net.minecraft.network.chat.Component message) {
        if (!Tessera.isRunning() || !TesseraMod.config().bridgeJoinQuitMessages()) return false;
        PENDING_JOIN.put(player.getUUID(), message);
        return true;
    }

    static void onJoin(ServerPlayer player) {
        if (!Tessera.isRunning()) return;
        var vanilla = PENDING_JOIN.remove(player.getUUID());
        PlayerJoinEvent event = new PlayerJoinEvent(EventBridge.bukkit(player), vanilla == null ? null : Text.fromVanilla(vanilla));
        EventBridge.call(event);
        Component message = event.joinMessage();
        if (vanilla != null && message != null) {
            Tessera.minecraft().getPlayerList().broadcastSystemMessage(Text.toVanilla(message), false);
        }
    }

    /** Chiamato dalla mixin al momento dell'uscita: restituisce il messaggio da trasmettere (o null). */
    public static net.minecraft.network.chat.Component onQuit(ServerPlayer player, net.minecraft.network.chat.Component vanilla) {
        if (!Tessera.isRunning()) return vanilla;
        PlayerQuitEvent event = new PlayerQuitEvent(EventBridge.bukkit(player), Text.fromVanilla(vanilla),
                PlayerQuitEvent.QuitReason.DISCONNECTED);
        EventBridge.call(event);
        Tessera.bukkit().forget(player);
        if (!TesseraMod.config().bridgeJoinQuitMessages()) return vanilla;
        return event.quitMessage() == null ? null : Text.toVanilla(event.quitMessage());
    }

    private JoinQuit() {
    }
}
