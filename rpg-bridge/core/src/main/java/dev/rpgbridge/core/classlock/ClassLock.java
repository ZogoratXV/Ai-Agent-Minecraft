/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core.classlock;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import dev.rpgbridge.core.RpgBridgeMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;

/**
 * Blocco degli oggetti per classe MMOCore: un oggetto soggetto a una regola (o con la "Required Class" di MMOItems)
 * non si può usare, con cui attaccare, con cui lanciare spell né indossare se la classe MMOCore del giocatore non è
 * tra quelle ammesse. Regole in {@code config/rpgbridge/classi.yml}. Le verifiche sono tutte lato server.
 */
public final class ClassLock {
	public static final String BYPASS_PERMISSION = "rpgbridge.classi.bypass";
	private static final String DEFAULT_CONFIG = """
			# RPG Bridge - blocco degli oggetti per classe MMOCore
			#
			# Un oggetto bloccato non si puo' usare (tasto destro, archi, scudi, bacchette, uso su blocchi ed entita'),
			# con lui non si attacca (attacco normale e Better Combat), non lancia spell (Spell Engine) e non si puo'
			# indossare o tenere nella mano secondaria (viene rimesso nell'inventario, o lasciato a terra se pieno).
			# Senza MMOCore il blocco e' inattivo. Permesso per ignorarlo: rpgbridge.classi.bypass (default false,
			# anche per gli OP: va dato esplicitamente, es. con LuckPerms).
			# Dopo una modifica: /rpgbridge classi ricarica

			attivo: true

			# Messaggio nella barra delle azioni. {classi} = classi ammesse. Codici colore con &.
			messaggio: "&cQuesto oggetto e' riservato a: &f{classi}"

			# Usa anche la "Required Class" degli oggetti MMOItems come blocco vero (MMOItems da solo toglie solo le
			# statistiche). Le regole qui sotto hanno la precedenza.
			mmoitems-classe-richiesta: true

			# Regole: per ogni oggetto vale la PRIMA regola che corrisponde (metti prima le piu' specifiche).
			#   oggetti: identificativi, con * = qualsiasi testo
			#     - oggetti delle mod o vanilla: id del registro, es. wizards:wand_arcane, wizards:* (tutta la mod)
			#     - MMOItems: mmoitems:TIPO:ID, es. mmoitems:SWORD:SPADONE_DRAGO, mmoitems:STAFF:*
			#     - ItemsAdder: itemsadder:namespace:id, es. itemsadder:castigo:spada_drago
			#   classi: id o nome delle classi MMOCore (maiuscole/minuscole indifferenti), es. MAGE, ARCANE_MAGE
			# Esempio (togli i # per attivarlo):
			regole: []
			#regole:
			#  - oggetti: ["wizards:*", "mmoitems:STAFF:*"]
			#    classi: [MAGE, ARCANE_MAGE]
			#  - oggetti: ["paladins:*"]
			#    classi: [PALADIN]
			#  - oggetti: ["rogues:*"]
			#    classi: [ROGUE, WARRIOR]
			#  - oggetti: ["archers:*"]
			#    classi: [MARKSMAN]
			""";

	private record Rule(List<Pattern> items, List<String> classes) {
	}

	private static volatile boolean loaded;
	private static volatile boolean enabled;
	private static volatile boolean mmoItemsRequiredClass;
	private static volatile String message = "";
	private static volatile List<Rule> rules = List.of();
	private static volatile boolean permissionRegistered;
	private static final Map<UUID, Long> LAST_MESSAGE = new ConcurrentHashMap<>();
	/** Giocatori esclusi dal blocco: il bot del self-test durante la prova di base. */
	private static final java.util.Set<UUID> EXEMPT = ConcurrentHashMap.newKeySet();

	private ClassLock() {
	}

	public static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("rpgbridge").resolve("classi.yml");
	}

	/** (Ri)legge la configurazione; crea il file con i commenti alla prima esecuzione. Restituisce un riepilogo. */
	public static synchronized String reload() {
		Path file = configFile();
		try {
			if (!Files.exists(file)) {
				Files.createDirectories(file.getParent());
				Files.writeString(file, DEFAULT_CONFIG, StandardCharsets.UTF_8);
			}
			YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
			List<Rule> parsed = new ArrayList<>();
			for (Map<?, ?> map : yaml.getMapList("regole")) {
				List<Pattern> items = new ArrayList<>();
				for (Object o : asList(map.get("oggetti"))) items.add(glob(String.valueOf(o)));
				List<String> classes = new ArrayList<>();
				for (Object o : asList(map.get("classi"))) classes.add(String.valueOf(o).trim());
				if (!items.isEmpty() && !classes.isEmpty()) parsed.add(new Rule(List.copyOf(items), List.copyOf(classes)));
			}
			rules = List.copyOf(parsed);
			enabled = yaml.getBoolean("attivo", true);
			mmoItemsRequiredClass = yaml.getBoolean("mmoitems-classe-richiesta", true);
			message = yaml.getString("messaggio", "&cQuesto oggetto e' riservato a: &f{classi}").replace('&', '§');
			loaded = true;
			String summary = "blocco per classe " + (enabled ? "attivo" : "disattivato") + ", " + rules.size() + " regole, MMOItems "
					+ (mmoItemsRequiredClass ? "si" : "no") + ", MMOCore " + (mmoCore() != null ? "presente" : "assente (blocco inattivo)");
			RpgBridgeMod.LOGGER.info("[ClassLock] {} ({})", summary, file);
			return summary;
		} catch (IOException | RuntimeException e) {
			RpgBridgeMod.LOGGER.error("[ClassLock] Impossibile leggere {}: blocco disattivato", file, e);
			enabled = false;
			loaded = true;
			return "errore nella configurazione, blocco disattivato: " + e.getMessage();
		}
	}

	private static List<?> asList(Object o) {
		if (o instanceof List<?> l) return l;
		return o == null ? List.of() : List.of(o);
	}

	private static Pattern glob(String text) {
		StringBuilder regex = new StringBuilder();
		for (String part : text.trim().split("\\*", -1)) {
			if (!regex.isEmpty()) regex.append(".*");
			regex.append(Pattern.quote(part));
		}
		return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
	}

	// ------------------------------------------------------------------ identificazione dell'oggetto

	/** Identificativi con cui le regole riconoscono l'oggetto: id del registro, mmoitems:TIPO:ID, itemsadder:ns:id. */
	public static List<String> itemKeys(ItemStack stack) {
		List<String> keys = new ArrayList<>(3);
		keys.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		CompoundTag tag = customData(stack);
		if (tag != null) {
			String type = tag.getStringOr("MMOITEMS_ITEM_TYPE", "");
			String id = tag.getStringOr("MMOITEMS_ITEM_ID", "");
			if (!type.isEmpty() && !id.isEmpty()) keys.add("mmoitems:" + type + ":" + id);
			CompoundTag ia = tag.getCompoundOrEmpty("itemsadder");
			String ns = ia.getStringOr("namespace", "");
			String iaId = ia.getStringOr("id", "");
			if (!ns.isEmpty() && !iaId.isEmpty()) keys.add("itemsadder:" + ns + ":" + iaId);
		}
		return keys;
	}

	private static CompoundTag customData(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? null : data.getUnsafe();
	}

	/** Classi ammesse per l'oggetto, oppure null se l'oggetto non è soggetto al blocco. */
	public static List<String> requiredClasses(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return null;
		if (!loaded) reload();
		List<String> keys = itemKeys(stack);
		for (Rule rule : rules) {
			for (Pattern pattern : rule.items()) {
				for (String key : keys) {
					if (pattern.matcher(key).matches()) return rule.classes();
				}
			}
		}
		if (mmoItemsRequiredClass) {
			CompoundTag tag = customData(stack);
			String required = tag == null ? "" : tag.getStringOr("MMOITEMS_REQUIRED_CLASS", "");
			if (!required.isBlank()) {
				List<String> classes = new ArrayList<>();
				for (String c : required.split("[,;\\n]")) if (!c.isBlank()) classes.add(c.trim());
				if (!classes.isEmpty()) return classes;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ controllo

	/** true se il giocatore può usare l'oggetto (nessuna regola, classe ammessa, permesso di bypass o blocco inattivo). */
	public static boolean allowed(ServerPlayer player, ItemStack stack) {
		if (!loaded) reload();
		if (!enabled || stack == null || stack.isEmpty() || EXEMPT.contains(player.getUUID())) return true;
		List<String> required = requiredClasses(stack);
		if (required == null) return true;
		String[] playerClass = playerClass(player.getUUID());
		if (playerClass == null) return true; // MMOCore assente: blocco inattivo (segnalato in ricarica)
		for (String c : required) {
			for (String own : playerClass) {
				if (own != null && normalize(own).equals(normalize(c))) return true;
			}
		}
		return hasBypass(player);
	}

	/** Come {@link #allowed}, ma avvisa il giocatore nella barra delle azioni (al massimo una volta al secondo). */
	public static boolean check(ServerPlayer player, ItemStack stack) {
		if (allowed(player, stack)) return true;
		notify(player, stack);
		return false;
	}

	/** Esclude (o reintegra) un giocatore dal blocco: usato dal bot del self-test. */
	public static void setExempt(UUID uuid, boolean exempt) {
		if (exempt) EXEMPT.add(uuid);
		else EXEMPT.remove(uuid);
	}

	public static void notify(ServerPlayer player, ItemStack stack) {
		long now = System.currentTimeMillis();
		if (Boolean.getBoolean("rpgbridge.debugClassLock")) RpgBridgeMod.LOGGER.info("[ClassLock] blocco {} per {}", itemKeys(stack), player.getName().getString());
		Long last = LAST_MESSAGE.get(player.getUUID());
		if (last != null && now - last < 1000) return;
		LAST_MESSAGE.put(player.getUUID(), now);
		List<String> required = requiredClasses(stack);
		String text = message.replace("{classi}", required == null ? "" : String.join(", ", required));
		var server = player.level().getServer();
		Runnable send = () -> player.sendOverlayMessage(Component.literal(text));
		if (server.isSameThread()) send.run();
		else server.execute(send);
	}

	private static String normalize(String s) {
		return s.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]", "");
	}

	private static boolean hasBypass(ServerPlayer player) {
		if (!permissionRegistered) {
			synchronized (ClassLock.class) {
				if (!permissionRegistered) {
					try {
						if (Bukkit.getPluginManager().getPermission(BYPASS_PERMISSION) == null) {
							Bukkit.getPluginManager().addPermission(new Permission(BYPASS_PERMISSION,
									"Ignora il blocco degli oggetti per classe di RPG Bridge", PermissionDefault.FALSE));
						}
					} catch (RuntimeException ignored) {
					}
					permissionRegistered = true;
				}
			}
		}
		return player.getBukkitEntity().hasPermission(BYPASS_PERMISSION);
	}

	// ------------------------------------------------------------------ MMOCore (riflessione: nessuna dipendenza di compilazione)

	private static volatile Method playerDataGet;
	private static volatile Method getProfess;
	private static volatile Method classGetId;
	private static volatile Method classGetName;
	private static volatile Plugin mmoCorePlugin;

	private static Plugin mmoCore() {
		try {
			Plugin plugin = Bukkit.getPluginManager().getPlugin("MMOCore");
			return plugin != null && plugin.isEnabled() ? plugin : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** {id, nome} della classe MMOCore del giocatore, oppure null se MMOCore non è disponibile. */
	public static String[] playerClass(UUID uuid) {
		Plugin plugin = mmoCore();
		if (plugin == null) return null;
		try {
			if (plugin != mmoCorePlugin) {
				ClassLoader loader = plugin.getClass().getClassLoader();
				Class<?> playerData = Class.forName("net.Indyuce.mmocore.api.player.PlayerData", true, loader);
				Class<?> playerClass = Class.forName("net.Indyuce.mmocore.api.player.profess.PlayerClass", true, loader);
				playerDataGet = playerData.getMethod("get", UUID.class);
				getProfess = playerData.getMethod("getProfess");
				classGetId = playerClass.getMethod("getId");
				classGetName = playerClass.getMethod("getName");
				mmoCorePlugin = plugin;
			}
			Object data = playerDataGet.invoke(null, uuid);
			Object profess = data == null ? null : getProfess.invoke(data);
			if (profess == null) return new String[] {"", ""};
			return new String[] {String.valueOf(classGetId.invoke(profess)), String.valueOf(classGetName.invoke(profess))};
		} catch (ReflectiveOperationException | RuntimeException e) {
			// Dati del giocatore non ancora caricati da MMOCore (o API cambiata): nessuna classe.
			return new String[] {"", ""};
		}
	}
}
