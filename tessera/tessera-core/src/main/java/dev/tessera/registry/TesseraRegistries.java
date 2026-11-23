package dev.tessera.registry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.BiFunction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.kyori.adventure.key.Key;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.BlockType;
import org.bukkit.inventory.ItemType;
import org.jspecify.annotations.Nullable;

/**
 * Registri Bukkit costruiti dai registri vanilla (blocchi e oggetti, comprese le aggiunte delle
 * mod Fabric). Gli altri registri sono vuoti: vedi TesseraRegistryAccess.
 */
public final class TesseraRegistries {

    private static volatile @Nullable Map<NamespacedKey, TesseraBlockType> blocks;
    private static volatile @Nullable Map<NamespacedKey, TesseraItemType> items;

    public static Map<NamespacedKey, TesseraBlockType> blocks() {
        if (blocks == null) blocks = build(BuiltInRegistries.BLOCK, TesseraBlockType::new);
        return blocks;
    }

    public static Map<NamespacedKey, TesseraItemType> items() {
        if (items == null) items = build(BuiltInRegistries.ITEM, TesseraItemType::new);
        return items;
    }

    public static TesseraBlockType block(Block block) {
        return blocks().get(key(BuiltInRegistries.BLOCK.getKey(block)));
    }

    public static TesseraItemType item(Item item) {
        return items().get(key(BuiltInRegistries.ITEM.getKey(item)));
    }

    static NamespacedKey key(Identifier id) {
        return new NamespacedKey(id.getNamespace(), id.getPath());
    }

    private static <V, T> Map<NamespacedKey, T> build(net.minecraft.core.Registry<V> registry, BiFunction<V, NamespacedKey, T> factory) {
        Map<NamespacedKey, T> map = new LinkedHashMap<>();
        for (V value : registry) {
            NamespacedKey key = key(registry.getKey(value));
            map.put(key, factory.apply(value, key));
        }
        return Map.copyOf(map);
    }

    public static Registry<BlockType> blockRegistry() {
        return proxy(TesseraRegistries::blocks);
    }

    public static Registry<ItemType> itemRegistry() {
        return proxy(TesseraRegistries::items);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Keyed> Registry<T> proxy(java.util.function.Supplier<? extends Map<NamespacedKey, ? extends T>> source) {
        InvocationHandler handler = (proxy, method, args) -> {
            Map<NamespacedKey, ? extends T> map = source.get();
            return switch (method.getName()) {
                case "get" -> args[0] instanceof Key k ? map.get(NamespacedKey.fromString(k.asString())) : null;
                case "getOrThrow" -> {
                    Object v = args[0] instanceof Key k ? map.get(NamespacedKey.fromString(k.asString())) : null;
                    if (v == null) throw new NoSuchElementException("Nessun elemento " + args[0]);
                    yield v;
                }
                case "match" -> map.get(NamespacedKey.fromString(((String) args[0]).toLowerCase(java.util.Locale.ROOT)));
                case "stream" -> map.values().stream();
                case "keyStream" -> map.keySet().stream();
                case "iterator" -> map.values().iterator();
                case "size" -> map.size();
                case "getKey", "getKeyOrThrow" -> ((Keyed) args[0]).getKey();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "TesseraRegistry(" + map.size() + ")";
                default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : null;
            };
        };
        return (Registry<T>) Proxy.newProxyInstance(Registry.class.getClassLoader(), new Class<?>[] {Registry.class}, handler);
    }

    private TesseraRegistries() {
    }
}
