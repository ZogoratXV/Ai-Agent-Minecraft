/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core.classlock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import dev.rpgbridge.core.RpgBridgeMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Tomi legati al giocatore: uno Spell Book (o altro oggetto configurato) si lega a chi lo equipaggia per primo
 * (slot di Trinkets o mano secondaria). Da allora solo il proprietario può usarlo, equipaggiarlo e lanciarne le spell.
 * Il proprietario è salvato nell'oggetto ({@code custom_data}: rpgbridge_owner, rpgbridge_owner_name).
 */
public final class BoundTomes {
	public static final String BYPASS_PERMISSION = "rpgbridge.tomi.bypass";
	private static final String OWNER = "rpgbridge_owner";
	private static final String OWNER_NAME = "rpgbridge_owner_name";
	private static final String DEFAULT_CONFIG = """
			# RPG Bridge - tomi legati al giocatore
			#
			# Un tomo si lega a chi lo equipaggia per primo (slot "spell book" di Trinkets o mano secondaria).
			# Da allora solo il proprietario puo' usarlo, equipaggiarlo e lanciarne le spell; gli altri possono solo
			# tenerlo nell'inventario o nei bauli (se lo equipaggiano torna nell'inventario entro mezzo secondo).
			# Permesso per ignorare il legame: rpgbridge.tomi.bypass (default false anche per gli OP).
			# Comandi: /rpgbridge tomo slega <giocatore> (scioglie il tomo che il giocatore ha in mano),
			#          /rpgbridge classi controlla <giocatore> (mostra anche i proprietari). Ricarica: /rpgbridge ricarica

			attivo: true

			# Oggetti che sono tomi (id del registro, * = qualsiasi testo; anche mmoitems:TIPO:ID, itemsadder:ns:id)
			oggetti: ["spell_engine:spell_book"]

			# Messaggio a chi prova a usare il tomo di un altro. {proprietario} = nome del proprietario.
			messaggio: "&cQuesto tomo e' legato a &f{proprietario}"

			# Riga aggiunta alla descrizione del tomo quando si lega (vuota = nessuna riga).
			descrizione: "&7Legato a {proprietario}"

			# Messaggio al giocatore quando un tomo si lega a lui (vuoto = nessun messaggio).
			messaggio-legato: "&aIl tomo ora e' legato a te"
			""";

	private static volatile boolean loaded;
	private static volatile boolean enabled;
	private static volatile List<Pattern> items = List.of();
	private static volatile String message = "";
	private static volatile String loreFormat = "";
	private static volatile String boundMessage = "";

	private BoundTomes() {
	}

	public static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("rpgbridge").resolve("tomi.yml");
	}

	public static synchronized String reload() {
		Path file = configFile();
		try {
			if (!Files.exists(file)) {
				Files.createDirectories(file.getParent());
				Files.writeString(file, DEFAULT_CONFIG, StandardCharsets.UTF_8);
			}
			YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
			List<Pattern> parsed = new ArrayList<>();
			for (String s : yaml.getStringList("oggetti")) parsed.add(ClassLock.glob(s));
			items = List.copyOf(parsed);
			enabled = yaml.getBoolean("attivo", true);
			message = color(yaml.getString("messaggio", "&cQuesto tomo e' legato a &f{proprietario}"));
			loreFormat = color(yaml.getString("descrizione", "&7Legato a {proprietario}"));
			boundMessage = color(yaml.getString("messaggio-legato", "&aIl tomo ora e' legato a te"));
			loaded = true;
			String summary = "tomi legati " + (enabled ? "attivi" : "disattivati") + ", " + items.size() + " tipi di oggetto";
			RpgBridgeMod.LOGGER.info("[Tomi] {} ({})", summary, file);
			return summary;
		} catch (IOException | RuntimeException e) {
			RpgBridgeMod.LOGGER.error("[Tomi] Impossibile leggere {}: tomi legati disattivati", file, e);
			enabled = false;
			loaded = true;
			return "errore nella configurazione dei tomi, disattivati: " + e.getMessage();
		}
	}

	private static String color(String s) {
		return s == null ? "" : s.replace('&', '§');
	}

	public static boolean isTome(ItemStack stack) {
		if (!loaded) reload();
		if (!enabled || stack == null || stack.isEmpty()) return false;
		for (String key : ClassLock.itemKeys(stack)) {
			for (Pattern p : items) if (p.matcher(key).matches()) return true;
		}
		return false;
	}

	/** UUID del proprietario salvato nell'oggetto, oppure null. */
	public static UUID owner(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return null;
		String id = data.getUnsafe().getStringOr(OWNER, "");
		try {
			return id.isEmpty() ? null : UUID.fromString(id);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public static String ownerName(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? "" : data.getUnsafe().getStringOr(OWNER_NAME, "");
	}

	/** true se il giocatore può usare l'oggetto per quanto riguarda il legame (oggetto non legato, suo, o bypass). */
	public static boolean allowed(ServerPlayer player, ItemStack stack) {
		if (!loaded) reload();
		if (!enabled || stack == null || stack.isEmpty()) return true;
		UUID owner = owner(stack);
		if (owner == null || owner.equals(player.getUUID())) return true;
		return ClassLock.hasPermission(player, BYPASS_PERMISSION, "Usa i tomi legati ad altri giocatori (RPG Bridge)");
	}

	public static String deniedMessage(ItemStack stack) {
		String name = ownerName(stack);
		return message.replace("{proprietario}", name.isEmpty() ? "un altro giocatore" : name);
	}

	/** Lega il tomo al giocatore se è un tomo non ancora legato. Restituisce la copia legata, oppure null. */
	public static ItemStack bindIfUnbound(ServerPlayer player, ItemStack stack) {
		if (!isTome(stack) || owner(stack) != null) return null;
		ItemStack bound = stack.copy();
		String name = player.getGameProfile().name();
		CompoundTag tag = bound.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
		tag.putString(OWNER, player.getUUID().toString());
		tag.putString(OWNER_NAME, name);
		bound.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		if (!loreFormat.isEmpty()) {
			List<Component> lines = new ArrayList<>(bound.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines());
			lines.add(Component.literal(loreFormat.replace("{proprietario}", name)));
			bound.set(DataComponents.LORE, new ItemLore(lines));
		}
		if (!boundMessage.isEmpty()) player.sendOverlayMessage(Component.literal(boundMessage));
		return bound;
	}

	/** Scioglie il legame (comando staff). Restituisce true se il tomo era legato. */
	public static boolean unbind(ItemStack stack) {
		if (owner(stack) == null) return false;
		String name = ownerName(stack);
		CompoundTag tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
		tag.remove(OWNER);
		tag.remove(OWNER_NAME);
		if (tag.isEmpty()) stack.remove(DataComponents.CUSTOM_DATA);
		else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		if (!loreFormat.isEmpty()) {
			String line = loreFormat.replace("{proprietario}", name);
			List<Component> lines = new ArrayList<>(stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines());
			lines.removeIf(c -> c.getString().equals(Component.literal(line).getString()));
			if (lines.isEmpty()) stack.remove(DataComponents.LORE);
			else stack.set(DataComponents.LORE, new ItemLore(lines));
		}
		return true;
	}
}
