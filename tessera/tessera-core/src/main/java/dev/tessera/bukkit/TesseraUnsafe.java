package dev.tessera.bukkit;

import dev.tessera.stub.StubUnsafeValues;
import net.minecraft.SharedConstants;
import org.bukkit.plugin.PluginDescriptionFile;

/** Parti di UnsafeValues che servono al caricamento dei plugin e ai controlli di versione. */
public final class TesseraUnsafe extends StubUnsafeValues {

    @Override
    protected String tesseraStubName() {
        return "UnsafeValues";
    }

    @Override
    public int getDataVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    @Override
    public int getProtocolVersion() {
        return SharedConstants.getProtocolVersion();
    }

    @Override
    public void checkSupported(PluginDescriptionFile pdf) {
        // Tutte le api-version sono accettate: il controllo reale lo fa l'uso dell'API.
    }

    @Override
    public boolean isSupportedApiVersion(String apiVersion) {
        return true;
    }

    @Override
    public byte[] processClass(PluginDescriptionFile pdf, String path, byte[] clazz) {
        return clazz;
    }
}
