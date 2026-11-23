package dev.tessera.service;

import dev.tessera.stub.StubInternalAPIBridge;
import dev.tessera.stub.StubLifecycleEventManager;
import java.util.function.BooleanSupplier;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Servizio interno di Paper (caricato via ServiceLoader dall'API). */
public final class TesseraInternalBridge extends StubInternalAPIBridge {

    @Override
    protected String tesseraStubName() {
        return "InternalAPIBridge";
    }

    @Override
    public io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager createPluginLifecycleEventManager(
            JavaPlugin plugin, BooleanSupplier registrationCheck) {
        // Gli eventi di ciclo di vita Paper (es. registrazione comandi Brigadier) non sono ancora supportati.
        return new StubLifecycleEventManager();
    }

    @Override
    public ItemStack createEmptyStack() {
        return new dev.tessera.bukkit.TesseraItemStack(net.minecraft.world.item.ItemStack.EMPTY.copy());
    }
}
