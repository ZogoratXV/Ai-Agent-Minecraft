package dev.tessera.api;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Punto unico in cui finiscono le chiamate a metodi dell'API Bukkit che Tessera non implementa
 * ancora. In modalità {@code warn} (predefinita) registra un avviso, una sola volta per metodo,
 * e il chiamante riceve un valore neutro; in modalità {@code strict} lancia un'eccezione.
 */
public final class Unsupported {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/API");
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    private static volatile boolean strict = Boolean.getBoolean("tessera.strictApi");

    public static void setStrict(boolean value) {
        strict = value;
    }

    public static void hit(String type, String method) {
        String key = type + "#" + method;
        if (strict) {
            throw new UnsupportedOperationException("Tessera non implementa ancora " + key);
        }
        if (SEEN.add(key)) {
            LOGGER.warn("API non implementata: {} (restituito un valore neutro). Stack del chiamante:", key,
                    new Throwable("chiamata a " + key));
        }
    }

    /** Elenco dei metodi mancanti usati finora: comodo per capire cosa serve a un plugin. */
    public static Set<String> seen() {
        return Set.copyOf(SEEN);
    }

    private Unsupported() {
    }
}
