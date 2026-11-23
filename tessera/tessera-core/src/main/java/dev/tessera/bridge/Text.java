package dev.tessera.bridge;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.tessera.Tessera;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.network.chat.ComponentSerialization;
import org.jspecify.annotations.Nullable;

/** Conversioni tra componenti Adventure (API Paper) e componenti vanilla, passando dal JSON. */
public final class Text {

    public static net.minecraft.network.chat.Component toVanilla(Component component) {
        JsonElement json = GsonComponentSerializer.gson().serializeToTree(component);
        var ops = Tessera.minecraft().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        return ComponentSerialization.CODEC.parse(ops, json).getOrThrow();
    }

    public static Component fromVanilla(net.minecraft.network.chat.Component component) {
        var ops = Tessera.minecraft().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        JsonElement json = ComponentSerialization.CODEC.encodeStart(ops, component).getOrThrow();
        return GsonComponentSerializer.gson().deserializeFromTree(json);
    }

    /** Testo "legacy" con i codici colore § (come lo usano i plugin Bukkit classici). */
    public static Component legacy(@Nullable String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text == null ? "" : text);
    }

    public static String toLegacy(@Nullable Component component) {
        return component == null ? "" : LegacyComponentSerializer.legacySection().serialize(component);
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private Text() {
    }
}
