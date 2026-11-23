package dev.tessera.service;

import dev.tessera.stub.StubPluginLoader;
import java.util.regex.Pattern;

/** Servizio richiesto da JavaPlugin (campo "loader"); il caricamento vero lo fa TesseraPluginManager. */
public final class TesseraLegacyPluginLoader extends StubPluginLoader {

    @Override
    protected String tesseraStubName() {
        return "PluginLoader";
    }

    @Override
    public Pattern[] getPluginFileFilters() {
        return new Pattern[] {Pattern.compile("\\.jar$")};
    }
}
