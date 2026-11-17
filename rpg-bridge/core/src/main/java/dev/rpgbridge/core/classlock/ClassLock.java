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
 * Blocco degli oggetti per classe: un oggetto soggetto a una regola (o con le classi di CastigoEquipaggiamento, o con la
 * "Required Class" di MMOItems) non si può usare, con cui attaccare, con cui lanciare spell né indossare se la classe del
 * giocatore (CastigoBellica tramite CastigoCore, oppure MMOCore) non è
 * tra quelle ammesse. Regole in {@code config/rpgbridge/classi.yml}. Le verifiche sono tutte lato server.
 */
public final class ClassLock {
	public static final String BYPASS_PERMISSION = "rpgbridge.classi.bypass";
	private static final String DEFAULT_CONFIG = """
			# RPG Bridge - blocco degli oggetti per classe (CastigoBellica o MMOCore)
			#
			# Un oggetto bloccato non si puo' usare (tasto destro, archi, scudi, bacchette, uso su blocchi ed entita'),
			# con lui non si attacca (attacco normale e Better Combat), non lancia spell (Spell Engine) e non si puo'
			# indossare o tenere nella mano secondaria (viene rimesso nell'inventario, o lasciato a terra se pieno).
			# La classe arriva da CastigoBellica (servizio classi di CastigoCore 2.1.0-beta.63+) o, in mancanza, da MMOCore;
			# senza nessuno dei due il blocco e' inattivo. Gli oggetti di CastigoEquipaggiamento portano le loro classi. Permesso per ignorarlo: rpgbridge.classi.bypass (default false,
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
			#   classi: id o nome delle classi (maiuscole/minuscole indifferenti): CastigoBellica MAGO_BIANCO, MAGO_NERO,
			#     DIFENSORE, GUERRIERO, ASSASSINO, ARCIERE; MMOCore es. MAGE, ARCANE_MAGE
			# Esempio (togli i # per attivarlo):
			regole: []

			# Livello minimo per tier degli oggetti di CastigoEquipaggiamento (tier scritto nell'oggetto), con CastigoBellica:
			# blocco = sigillo raggiunto (1 Novizio, 2 Esperto dal 25, 3 Maestro dal 50, 4 Leggenda dal 75), livello = livello minimo.
			# {tier} e {requisito} nel messaggio. Il permesso rpgbridge.classi.bypass ignora anche questo controllo.
			tier:
			  attivo: true
			  messaggio: "&cPer un oggetto {tier} serve {requisito}"
			  requisiti:
			    T1: {blocco: 1}
			    T2: {blocco: 2}
			    T3: {blocco: 3}
			    T4: {blocco: 4}
			    T5: {blocco: 4, livello: 100}

			# Spell da apprendere per blocco (CastigoBellica): id della spell -> blocco minimo (2 Esperto, 3 Maestro, 4 Leggenda).
			# Le spell non elencate si lanciano sempre. Esempio del kit Mago Nero:
			spell:
			  messaggio: "&cPer questa spell serve {requisito}"
			  blocchi: {}
			#  blocchi:
			#    castamir:globo_ombra: 2
			#    castamir:nube_tenebra: 2
			#    castamir:raggio_abisso: 3
			#    castamir:marchio_rovina: 3
			# Regole per classe (la prima che corrisponde vale; * = qualsiasi testo): spell, classi ammesse (vuoto = tutte),
			# blocco minimo, vietata: true per chiuderla a tutti. Hanno la precedenza su "blocchi".
			#  regole:
			#    - {spell: ["paladins:holy_beam", "paladins:levitate"], classi: [MAGO_BIANCO], blocco: 1}
			#    - {spell: ["wizards:arcane_missile"], vietata: true}
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

	/** Requisito di un tier di CastigoEquipaggiamento: blocco di CastigoBellica (sigillo) e livello minimi. */
	record TierRequirement(int block, int level) {
	}

	/** Variante B decisa il 16 novembre 2026: un tier per blocco, il T5 solo a livello 100. */
	static final Map<String, TierRequirement> DEFAULT_TIERS = Map.of(
			"T1", new TierRequirement(1, 1), "T2", new TierRequirement(2, 1), "T3", new TierRequirement(3, 1),
			"T4", new TierRequirement(4, 1), "T5", new TierRequirement(4, 100));
	private static volatile boolean tiersEnabled = true;
	private static volatile Map<String, TierRequirement> tiers = DEFAULT_TIERS;
	private static volatile String tierMessage = "§cPer un oggetto {tier} serve {requisito}";
	/** Spell da apprendere per blocco (id spell → blocco minimo di CastigoBellica, sezione "spell" di classi.yml). */
	private static volatile Map<String, Integer> spellBlocks = Map.of();
	private static volatile String spellMessage = "§cPer questa spell serve {requisito}";
	/** Regola di una spell: id (con *), classi ammesse (vuoto = tutte), blocco minimo, oppure vietata a tutti. */
	record SpellRule(List<Pattern> spells, List<String> classes, int block, boolean forbidden) {
	}
	private static volatile List<SpellRule> spellRules = List.of();
	private static volatile String spellClassMessage = "§cQuesta spell è riservata a: {classi}";
	private static volatile String spellForbiddenMessage = "§cQuesta spell non si usa su questo server";

	private static volatile boolean loaded;
	private static volatile boolean enabled;
	private static volatile boolean mmoItemsRequiredClass;
	private static volatile String message = "";
	private static volatile List<Rule> rules = List.of();
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
			var spellSection = yaml.getConfigurationSection("spell");
			var parsedSpells = new java.util.HashMap<String, Integer>();
			spellMessage = (spellSection == null ? "&cPer questa spell serve {requisito}" : spellSection.getString("messaggio", "&cPer questa spell serve {requisito}")).replace('&', '§');
			var blocchi = spellSection == null ? null : spellSection.getConfigurationSection("blocchi");
			if (blocchi != null) for (String id : blocchi.getKeys(false)) parsedSpells.put(id.toLowerCase(Locale.ROOT), Math.max(1, Math.min(4, blocchi.getInt(id, 1))));
			spellBlocks = Map.copyOf(parsedSpells);
			var parsedRules = new ArrayList<SpellRule>();
			if (spellSection != null) {
				for (Map<?, ?> map : spellSection.getMapList("regole")) {
					List<Pattern> ids = new ArrayList<>();
					for (Object o : asList(map.get("spell"))) ids.add(glob(String.valueOf(o)));
					List<String> classes = new ArrayList<>();
					for (Object o : asList(map.get("classi"))) classes.add(String.valueOf(o).trim());
					int block = map.get("blocco") instanceof Number n ? Math.max(1, Math.min(4, n.intValue())) : 1;
					boolean forbidden = Boolean.TRUE.equals(map.get("vietata"));
					if (!ids.isEmpty()) parsedRules.add(new SpellRule(List.copyOf(ids), List.copyOf(classes), block, forbidden));
				}
				spellClassMessage = spellSection.getString("messaggio-classe", "&cQuesta spell è riservata a: {classi}").replace('&', '§');
				spellForbiddenMessage = spellSection.getString("messaggio-vietata", "&cQuesta spell non si usa su questo server").replace('&', '§');
			}
			spellRules = List.copyOf(parsedRules);
			var tierSection = yaml.getConfigurationSection("tier");
			tiersEnabled = tierSection == null || tierSection.getBoolean("attivo", true);
			tierMessage = (tierSection == null ? "&cPer un oggetto {tier} serve {requisito}"
					: tierSection.getString("messaggio", "&cPer un oggetto {tier} serve {requisito}")).replace('&', '§');
			var reqSection = tierSection == null ? null : tierSection.getConfigurationSection("requisiti");
			if (reqSection == null) tiers = DEFAULT_TIERS;
			else {
				var parsedTiers = new java.util.HashMap<String, TierRequirement>();
				for (String t : reqSection.getKeys(false)) {
					int block = Math.max(1, Math.min(4, reqSection.getInt(t + ".blocco", 1)));
					int level = Math.max(1, Math.min(1000, reqSection.getInt(t + ".livello", 1)));
					parsedTiers.put(t.toUpperCase(Locale.ROOT), new TierRequirement(block, level));
				}
				tiers = Map.copyOf(parsedTiers);
			}
			enabled = yaml.getBoolean("attivo", true);
			mmoItemsRequiredClass = yaml.getBoolean("mmoitems-classe-richiesta", true);
			message = yaml.getString("messaggio", "&cQuesto oggetto e' riservato a: &f{classi}").replace('&', '§');
			loaded = true;
			String summary = "blocco per classe " + (enabled ? "attivo" : "disattivato") + ", " + rules.size() + " regole, MMOItems "
					+ (mmoItemsRequiredClass ? "si" : "no") + ", tier " + (tiersEnabled ? tiers.size() + " requisiti" : "spento") + ", spell per blocco " + spellBlocks.size() + ", regole spell " + spellRules.size() + ", classi da " + (classProvider().isEmpty() ? "nessuno (blocco inattivo finché CastigoBellica o MMOCore non sono attivi)" : classProvider());
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

	static Pattern glob(String text) {
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
		CompoundTag tag = customData(stack);
		// oggetti dei datapack (es. kit Mago Nero su oggetti Wizards): custom_data rpgbridge_id "ns:id" prende il
		// posto dell'id di registro, così il bastone del Mago Nero non ricade nelle regole "wizards:*"
		String own = tag == null ? "" : tag.getStringOr("rpgbridge_id", "");
		keys.add(own.isEmpty() ? BuiltInRegistries.ITEM.getKey(stack.getItem()).toString() : own);
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
		// CastigoEquipaggiamento: classi scritte nell'oggetto (PersistentDataContainer → custom_data.PublicBukkitValues)
		CompoundTag dati = customData(stack);
		if (dati != null) {
			String equip = dati.getCompoundOrEmpty("PublicBukkitValues").getStringOr("castigoequipaggiamento:classi", "");
			if (!equip.isBlank()) {
				List<String> classes = new ArrayList<>();
				for (String c : equip.split(",")) if (!c.isBlank()) classes.add(c.trim());
				if (!classes.isEmpty()) return classes;
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
		if (stack == null || stack.isEmpty() || EXEMPT.contains(player.getUUID())) return true;
		return BoundTomes.allowed(player, stack) && classAllowed(player, stack) && tierAllowed(player, stack);
	}

	/** Tier scritto da CastigoEquipaggiamento nell'oggetto (es. "T3"), oppure "". */
	public static String itemTier(ItemStack stack) {
		CompoundTag dati = customData(stack);
		if (dati == null) return "";
		return dati.getCompoundOrEmpty("PublicBukkitValues").getStringOr("castigoequipaggiamento:tier", "").trim().toUpperCase(Locale.ROOT);
	}

	/** Requisito non soddisfatto per il tier dell'oggetto, oppure null se il giocatore lo può usare. */
	static TierRequirement missingTier(Map<String, TierRequirement> table, String tier, int block, int level) {
		TierRequirement req = table.get(tier);
		if (req == null) return null;
		return block >= req.block() && level >= req.level() ? null : req;
	}

	/**
	 * Livello minimo per tier (dalla classe di CastigoBellica): blocco raggiunto (sigillo consegnato) e livello. Senza
	 * CastigoBellica il controllo è spento; chi non ha ancora una classe vale come blocco 1, livello 1.
	 */
	public static boolean tierAllowed(ServerPlayer player, ItemStack stack) {
		if (!loaded) reload();
		if (!enabled || !tiersEnabled || stack == null || stack.isEmpty()) return true;
		String tier = itemTier(stack);
		if (tier.isEmpty()) return true;
		int[] progress = castigoProgress(player.getUUID());
		if (progress == null) return true;
		if (missingTier(tiers, tier, progress[0], progress[1]) == null) return true;
		return hasPermission(player, BYPASS_PERMISSION, "Ignora il blocco degli oggetti per classe di RPG Bridge");
	}

	/** Prima regola della sezione "spell.regole" che corrisponde alla spell, oppure null. */
	static SpellRule spellRule(String spellId) {
		if (!loaded) reload();
		if (spellId == null) return null;
		for (SpellRule rule : spellRules) for (Pattern p : rule.spells()) if (p.matcher(spellId).matches()) return rule;
		return null;
	}

	/** Blocco minimo per la spell: dalla regola, altrimenti da "spell.blocchi"; 1 se non elencata. */
	public static int spellBlock(String spellId) {
		if (!loaded) reload();
		SpellRule rule = spellRule(spellId);
		if (rule != null) return rule.block();
		return spellId == null ? 1 : spellBlocks.getOrDefault(spellId.toLowerCase(Locale.ROOT), 1);
	}

	/**
	 * Motivo per cui il giocatore non può lanciare la spell (testo per la barra delle azioni), oppure null se può:
	 * spell vietata, classe non ammessa, blocco (sigillo) non raggiunto. Senza CastigoBellica restano solo i divieti.
	 */
	static String spellDenial(ServerPlayer player, String spellId) {
		if (!loaded) reload();
		if (!enabled || EXEMPT.contains(player.getUUID())) return null;
		SpellRule rule = spellRule(spellId);
		if (rule != null && rule.forbidden()) return spellForbiddenMessage;
		if (rule != null && !rule.classes().isEmpty()) {
			String[] own = playerClass(player.getUUID());
			if (own != null) {
				boolean ok = false;
				for (String c : rule.classes()) for (String o : own) if (o != null && normalize(o).equals(normalize(c))) ok = true;
				if (!ok) return spellClassMessage.replace("{classi}", String.join(", ", rule.classes()));
			}
		}
		int need = spellBlock(spellId);
		if (need > 1) {
			int[] progress = castigoProgress(player.getUUID());
			if (progress != null && progress[0] < need) return spellMessage.replace("{requisito}", requirementText(new TierRequirement(need, 1)));
		}
		return null;
	}

	/** Spell consentita: nessun divieto, classe ammessa e blocco raggiunto (o permesso di bypass). */
	public static boolean spellAllowed(ServerPlayer player, String spellId) {
		if (spellDenial(player, spellId) == null) return true;
		return hasPermission(player, BYPASS_PERMISSION, "Ignora il blocco degli oggetti per classe di RPG Bridge");
	}

	/** Avviso nella barra delle azioni per una spell non ancora consentita. */
	public static void notifySpell(ServerPlayer player, String spellId) {
		long now = System.currentTimeMillis();
		String reason = spellDenial(player, spellId);
		if (Boolean.getBoolean("rpgbridge.debugClassLock")) RpgBridgeMod.LOGGER.info("[ClassLock] spell {} bloccata per {}: {}", spellId, player.getName().getString(), reason);
		Long last = LAST_MESSAGE.get(player.getUUID());
		if (last != null && now - last < 1000) return;
		LAST_MESSAGE.put(player.getUUID(), now);
		String text = reason == null ? spellMessage.replace("{requisito}", requirementText(new TierRequirement(spellBlock(spellId), 1))) : reason;
		var server = player.level().getServer();
		Runnable send = () -> player.sendOverlayMessage(Component.literal(text));
		if (server.isSameThread()) send.run();
		else server.execute(send);
	}

	static String blockName(int block) {
		return switch (block) {
			case 1 -> "Novizio";
			case 2 -> "Esperto";
			case 3 -> "Maestro";
			default -> "Leggenda";
		};
	}

	static String requirementText(TierRequirement req) {
		String seal = req.block() <= 1 ? "" : "il sigillo di " + blockName(req.block());
		String lvl = req.level() <= 1 ? "" : "il livello " + req.level();
		if (seal.isEmpty()) return lvl;
		return lvl.isEmpty() ? seal : seal + " e " + lvl;
	}

	/** Solo la parte "classe" del controllo (senza il legame dei tomi). */
	public static boolean classAllowed(ServerPlayer player, ItemStack stack) {
		if (!loaded) reload();
		if (!enabled || stack == null || stack.isEmpty()) return true;
		List<String> required = requiredClasses(stack);
		if (required == null) return true;
		String[] playerClass = playerClass(player.getUUID());
		if (playerClass == null) return true; // né CastigoBellica né MMOCore: blocco inattivo (segnalato in ricarica)
		for (String c : required) {
			for (String own : playerClass) {
				if (own != null && normalize(own).equals(normalize(c))) return true;
			}
		}
		return hasPermission(player, BYPASS_PERMISSION, "Ignora il blocco degli oggetti per classe di RPG Bridge");
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
		String text;
		if (!BoundTomes.allowed(player, stack)) {
			text = BoundTomes.deniedMessage(stack);
		} else {
			List<String> required = requiredClasses(stack);
			if (classAllowed(player, stack) && !tierAllowed(player, stack)) {
				String tier = itemTier(stack);
				int[] progress = castigoProgress(player.getUUID());
				TierRequirement req = progress == null ? null : missingTier(tiers, tier, progress[0], progress[1]);
				text = tierMessage.replace("{tier}", tier).replace("{requisito}", req == null ? "" : requirementText(req));
			} else text = message.replace("{classi}", required == null ? "" : String.join(", ", required));
		}
		var server = player.level().getServer();
		Runnable send = () -> player.sendOverlayMessage(Component.literal(text));
		if (server.isSameThread()) send.run();
		else server.execute(send);
	}

	private static String normalize(String s) {
		return s.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]", "");
	}

	private static final java.util.Set<String> REGISTERED = ConcurrentHashMap.newKeySet();

	/**
	 * Permesso Bukkit registrato con default FALSE, quindi negato anche agli OP finché non viene concesso
	 * esplicitamente (es. LuckPerms).
	 */
	public static boolean hasPermission(ServerPlayer player, String permission, String description) {
		if (REGISTERED.add(permission)) {
			try {
				if (Bukkit.getPluginManager().getPermission(permission) == null) {
					Bukkit.getPluginManager().addPermission(new Permission(permission, description, PermissionDefault.FALSE));
				}
			} catch (RuntimeException ignored) {
			}
		}
		return player.getBukkitEntity().hasPermission(permission);
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

	// ------------------------------------------------------------------ CastigoBellica (servizio classi() di CastigoCore, per riflessione)

	/**
	 * {id, nome} della classe del giocatore: dal servizio {@code classi()} di CastigoCore (fornito da CastigoBellica) se
	 * attivo, altrimenti da MMOCore; null se nessuno dei due è disponibile (blocco inattivo).
	 */
	public static String[] playerClass(UUID uuid) {
		String[] castigo = castigoClass(uuid);
		return castigo != null ? castigo : mmoCoreClass(uuid);
	}

	/** Nome del fornitore delle classi, per i messaggi. */
	public static String classProvider() {
		if (castigoService() != null) return "CastigoBellica";
		return mmoCore() != null ? "MMOCore" : "";
	}

	/** Il servizio Classi attivo di CastigoCore, oppure null. */
	private static Object castigoService() {
		try {
			Plugin core = Bukkit.getPluginManager().getPlugin("CastigoCore");
			if (core == null || !core.isEnabled()) return null;
			Object fornitore = core.getClass().getMethod("classi").invoke(core);
			Object attivo = fornitore.getClass().getMethod("attivo").invoke(fornitore);
			return attivo instanceof java.util.Optional<?> o ? o.orElse(null) : null;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null; // Core senza il servizio classi (prima della 2.1.0-beta.63)
		}
	}

	private static String[] castigoClass(UUID uuid) {
		Object service = castigoService();
		if (service == null) return null;
		try {
			Object profilo = publicMethod(service, "profilo", UUID.class).invoke(service, uuid);
			Object p = profilo instanceof java.util.Optional<?> o ? o.orElse(null) : null;
			if (p == null) return new String[] {"", ""};
			return new String[] {String.valueOf(publicMethod(p, "classe").invoke(p)), String.valueOf(publicMethod(p, "nome").invoke(p))};
		} catch (ReflectiveOperationException | RuntimeException e) {
			return new String[] {"", ""};
		}
	}

	/** {blocco, livello} del personaggio da CastigoBellica; {1, 1} senza classe; null senza il servizio classi. */
	static int[] castigoProgress(UUID uuid) {
		Object service = castigoService();
		if (service == null) return null;
		try {
			Object profilo = publicMethod(service, "profilo", UUID.class).invoke(service, uuid);
			Object p = profilo instanceof java.util.Optional<?> o ? o.orElse(null) : null;
			if (p == null) return new int[] {1, 1};
			return new int[] {((Number) publicMethod(p, "blocco").invoke(p)).intValue(), ((Number) publicMethod(p, "livello").invoke(p)).intValue()};
		} catch (ReflectiveOperationException | RuntimeException e) {
			return new int[] {1, 1};
		}
	}

	/**
	 * Metodo invocabile anche quando la classe concreta non è pubblica (il fornitore registra una classe privata che
	 * implementa l'interfaccia pubblica del Core): lo cerca sulle interfacce e sulle superclassi pubbliche.
	 */
	private static Method publicMethod(Object target, String name, Class<?>... args) throws NoSuchMethodException {
		for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
			if (java.lang.reflect.Modifier.isPublic(c.getModifiers())) {
				try {
					return c.getMethod(name, args);
				} catch (NoSuchMethodException ignored) {
				}
			}
			for (Class<?> i : c.getInterfaces()) {
				try {
					return i.getMethod(name, args);
				} catch (NoSuchMethodException ignored) {
				}
			}
		}
		Method m = target.getClass().getMethod(name, args);
		m.setAccessible(true);
		return m;
	}

	/** {id, nome} della classe MMOCore del giocatore, oppure null se MMOCore non è disponibile. */
	private static String[] mmoCoreClass(UUID uuid) {
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
