package dev.tessera.service;

import dev.tessera.stub.StubRegistryAccess;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.stream.Stream;
import dev.tessera.registry.TesseraRegistries;
import org.bukkit.Registry;

/**
 * Registri Bukkit. BLOCK e ITEM sono reali (TesseraRegistries); gli altri per ora sono vuoti: le costanti statiche (Registry.ENCHANTMENT, Sound,
 * Particle...) vengono inizializzate senza errori ma le ricerche restituiscono null.
 */
public final class TesseraRegistryAccess extends StubRegistryAccess {

    private static final Registry<?> EMPTY = (Registry<?>) Proxy.newProxyInstance(
            Registry.class.getClassLoader(), new Class<?>[] {Registry.class}, emptyHandler());

    @Override
    protected String tesseraStubName() {
        return "RegistryAccess";
    }

    @Override
    public Registry getRegistry(Class type) {
        if (type == org.bukkit.block.BlockType.class) return TesseraRegistries.blockRegistry();
        if (type == org.bukkit.inventory.ItemType.class) return TesseraRegistries.itemRegistry();
        return EMPTY;
    }

    @Override
    public Registry getRegistry(io.papermc.paper.registry.RegistryKey key) {
        if (key == io.papermc.paper.registry.RegistryKey.BLOCK) return TesseraRegistries.blockRegistry();
        if (key == io.papermc.paper.registry.RegistryKey.ITEM) return TesseraRegistries.itemRegistry();
        return EMPTY;
    }

    private static InvocationHandler emptyHandler() {
        return (proxy, method, args) -> {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            return switch (method.getName()) {
                case "getOrThrow" -> throw new NoSuchElementException("Registro non disponibile in Tessera: " + (args == null ? "" : args[0]));
                case "stream", "keyStream" -> Stream.empty();
                case "iterator" -> Collections.emptyIterator();
                case "size" -> 0;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "TesseraEmptyRegistry";
                default -> {
                    Class<?> r = method.getReturnType();
                    if (r == boolean.class) yield false;
                    if (r == int.class) yield 0;
                    yield null;
                }
            };
        };
    }
}
