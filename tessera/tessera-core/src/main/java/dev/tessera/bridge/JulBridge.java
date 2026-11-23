package dev.tessera.bridge;

import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * I plugin Bukkit scrivono su java.util.logging; il server usa log4j. Questo handler inoltra
 * ogni record JUL a SLF4J (quindi alla console e a logs/latest.log del server).
 */
public final class JulBridge extends Handler {

    private final Formatter formatter = new SimpleFormatter();

    public static void install() {
        java.util.logging.Logger root = LogManager.getLogManager().getLogger("");
        for (Handler h : root.getHandlers()) {
            root.removeHandler(h);
        }
        root.addHandler(new JulBridge());
        root.setLevel(Level.INFO);
    }

    @Override
    public void publish(LogRecord record) {
        if (!isLoggable(record)) return;
        String name = record.getLoggerName() == null || record.getLoggerName().isEmpty() ? "Minecraft" : record.getLoggerName();
        Logger logger = LoggerFactory.getLogger(name);
        String msg = formatter.formatMessage(record);
        if (!"Minecraft".equals(name) && !name.contains(".")) {
            msg = "[" + name + "] " + msg;
        }
        int level = record.getLevel().intValue();
        Throwable t = record.getThrown();
        if (level >= Level.SEVERE.intValue()) logger.error(msg, t);
        else if (level >= Level.WARNING.intValue()) logger.warn(msg, t);
        else if (level >= Level.INFO.intValue()) logger.info(msg, t);
        else logger.debug(msg, t);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
