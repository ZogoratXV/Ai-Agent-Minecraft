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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import dev.rpgbridge.core.RpgBridgeMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Bonus delle mod RPG Series su oggetti di MMOItems, ItemsAdder o altri: attributi (es. Spell Power), stile d'attacco di
 * Better Combat e spell di Spell Engine, scelti uno per uno in {@code config/rpgbridge/armi.yml}. Il bridge li scrive
 * nell'oggetto (componenti) quando è nell'inventario di un giocatore e li riscrive se l'oggetto viene ricostruito
 * (MMOItems) o se la configurazione cambia. Le altre proprietà dell'oggetto restano invariate.
 */
public final class WeaponBonuses {
	private static final String MARKER = "rpgbridge_bonus";
	private static final String MODIFIER_NAMESPACE = "rpgbridge";
	private static final String DEFAULT_CONFIG = """
			# RPG Bridge - bonus delle mod RPG Series su oggetti di MMOItems, ItemsAdder o altri
			#
			# Per ogni regola scegli i singoli bonus da aggiungere agli oggetti indicati. L'oggetto mantiene tutto il
			# resto (modello, nome, statistiche MMOItems, classe richiesta). I bonus si applicano entro un secondo da
			# quando l'oggetto e' nell'inventario di un giocatore e si riapplicano se MMOItems ricostruisce l'oggetto o
			# se cambi questo file (/rpgbridge ricarica). Vale la PRIMA regola che corrisponde all'oggetto.
			#
			#   oggetti: mmoitems:TIPO:ID, itemsadder:namespace:id o id del registro (* = qualsiasi testo)
			#   attributi: "id attributo": valore, sommato quando l'oggetto e' nello slot indicato. Esempi:
			#       "spell_power:arcane", "spell_power:fire", "spell_power:frost", "spell_power:healing",
			#       "spell_power:lightning", "spell_power:soul", "spell_power:critical_chance" (5 = +5%: crit e haste sono in punti percentuali, base 100),
			#       "spell_power:critical_damage", "spell_power:haste", "minecraft:attack_damage", "minecraft:attack_speed"
			#   operazione: add_value (somma, predefinita), add_multiplied_base, add_multiplied_total
			#   slot: mainhand (predefinito), offhand, hand, head, chest, legs, feet, armor, any
			#   stile-attacco: preset di Better Combat (animazioni, combo, portata), es. bettercombat:claymore,
			#       bettercombat:sword, bettercombat:dagger, bettercombat:spear, bettercombat:staff, bettercombat:wand,
			#       bettercombat:battlestaff, bettercombat:katana, bettercombat:heavy_axe, bettercombat:hammer, ...
			#   spell: spell di Spell Engine lanciabili con l'oggetto in mano, es. [wizards:arcane_bolt, wizards:fireball]
			#   tipo-spell: MAGIC (predefinito) o ARCHERY
			#
			# Esempio (togli i # per attivarlo):
			armi: []
			#armi:
			#  - oggetti: ["mmoitems:STAFF:BASTONE_DEL_FUOCO"]
			#    attributi:
			#      "spell_power:fire": 4
			#      "spell_power:critical_chance": 5
			#    stile-attacco: bettercombat:staff
			#    spell: [wizards:fireball, wizards:fire_blast]
			#  - oggetti: ["itemsadder:castigo:spadone_drago"]
			#    attributi:
			#      "minecraft:attack_damage": 3
			#    stile-attacco: bettercombat:claymore
			""";

	private record Bonus(Identifier attribute, double amount) {
	}

	private record Rule(List<Pattern> items, List<Bonus> attributes, AttributeModifier.Operation operation,
			EquipmentSlotGroup slot, Identifier attackStyle, List<String> spells, String spellType, String signature) {
	}

	private static volatile boolean loaded;
	private static volatile List<Rule> rules = List.of();

	private WeaponBonuses() {
	}

	public static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("rpgbridge").resolve("armi.yml");
	}

	public static synchronized String reload() {
		Path file = configFile();
		try {
			if (!Files.exists(file)) {
				Files.createDirectories(file.getParent());
				Files.writeString(file, DEFAULT_CONFIG, StandardCharsets.UTF_8);
			}
			YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
			List<Rule> parsed = new ArrayList<>();
			List<String> problems = new ArrayList<>();
			int index = 0;
			for (Map<?, ?> map : yaml.getMapList("armi")) {
				index++;
				YamlConfiguration section = new YamlConfiguration();
				for (var e : map.entrySet()) section.set(String.valueOf(e.getKey()), e.getValue());
				List<Pattern> items = new ArrayList<>();
				for (String s : section.getStringList("oggetti")) items.add(ClassLock.glob(s));
				if (items.isEmpty()) {
					problems.add("regola " + index + ": nessun oggetto");
					continue;
				}
				List<Bonus> bonuses = new ArrayList<>();
				Object attrs = map.get("attributi");
				if (attrs instanceof Map<?, ?> attrMap) {
					for (var e : attrMap.entrySet()) {
						Identifier id = Identifier.tryParse(String.valueOf(e.getKey()).trim());
						if (id == null || BuiltInRegistries.ATTRIBUTE.get(id).isEmpty()) {
							problems.add("regola " + index + ": attributo sconosciuto " + e.getKey());
							continue;
						}
						try {
							bonuses.add(new Bonus(id, Double.parseDouble(String.valueOf(e.getValue()).trim())));
						} catch (NumberFormatException ex) {
							problems.add("regola " + index + ": valore non numerico per " + e.getKey());
						}
					}
				}
				AttributeModifier.Operation operation = switch (section.getString("operazione", "add_value").toLowerCase(Locale.ROOT)) {
					case "add_multiplied_base" -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
					case "add_multiplied_total" -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
					default -> AttributeModifier.Operation.ADD_VALUE;
				};
				EquipmentSlotGroup slot = slot(section.getString("slot", "mainhand"));
				Identifier style = null;
				String styleText = section.getString("stile-attacco", "");
				if (!styleText.isBlank()) {
					style = Identifier.tryParse(styleText.trim());
					if (style == null) problems.add("regola " + index + ": stile-attacco non valido " + styleText);
				}
				List<String> spells = new ArrayList<>();
				for (String sp : section.getStringList("spell")) if (!sp.isBlank()) spells.add(sp.trim());
				String spellType = section.getString("tipo-spell", "MAGIC").toUpperCase(Locale.ROOT);
				String signature = Integer.toHexString((map.toString() + "|v1").hashCode());
				parsed.add(new Rule(List.copyOf(items), List.copyOf(bonuses), operation, slot, style, List.copyOf(spells), spellType, signature));
			}
			rules = List.copyOf(parsed);
			loaded = true;
			String summary = "bonus armi: " + rules.size() + " regole" + (problems.isEmpty() ? "" : ", problemi: " + problems);
			RpgBridgeMod.LOGGER.info("[Armi] {} ({})", summary, file);
			return summary;
		} catch (IOException | RuntimeException e) {
			RpgBridgeMod.LOGGER.error("[Armi] Impossibile leggere {}: bonus disattivati", file, e);
			rules = List.of();
			loaded = true;
			return "errore nella configurazione delle armi: " + e.getMessage();
		}
	}

	private static EquipmentSlotGroup slot(String name) {
		return switch (name.toLowerCase(Locale.ROOT)) {
			case "offhand" -> EquipmentSlotGroup.OFFHAND;
			case "hand" -> EquipmentSlotGroup.HAND;
			case "head" -> EquipmentSlotGroup.HEAD;
			case "chest" -> EquipmentSlotGroup.CHEST;
			case "legs" -> EquipmentSlotGroup.LEGS;
			case "feet" -> EquipmentSlotGroup.FEET;
			case "armor" -> EquipmentSlotGroup.ARMOR;
			case "any" -> EquipmentSlotGroup.ANY;
			default -> EquipmentSlotGroup.MAINHAND;
		};
	}

	private static Rule ruleFor(ItemStack stack) {
		if (!loaded) reload();
		if (rules.isEmpty() || stack.isEmpty()) return null;
		List<String> keys = ClassLock.itemKeys(stack);
		for (Rule rule : rules) {
			for (Pattern p : rule.items()) for (String key : keys) if (p.matcher(key).matches()) return rule;
		}
		return null;
	}

	/**
	 * Applica (o riapplica) i bonus all'oggetto se una regola lo riguarda e l'oggetto non li ha già nella versione
	 * corrente della regola. Modifica lo stack sul posto; restituisce true se è cambiato.
	 */
	public static boolean apply(ItemStack stack) {
		Rule rule = ruleFor(stack);
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		String current = data == null ? "" : data.getUnsafe().getStringOr(MARKER, "");
		if (rule == null) return false;
		if (rule.signature().equals(current)) return false;

		// Attributi: tieni quelli dell'oggetto (anche di MMOItems), togli i nostri precedenti, aggiungi quelli della regola.
		ItemAttributeModifiers base = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		List<ItemAttributeModifiers.Entry> entries = new ArrayList<>();
		for (var entry : base.modifiers()) {
			if (!MODIFIER_NAMESPACE.equals(entry.modifier().id().getNamespace())) entries.add(entry);
		}
		ItemAttributeModifiers modifiers = new ItemAttributeModifiers(entries);
		int n = 0;
		for (Bonus bonus : rule.attributes()) {
			var holder = BuiltInRegistries.ATTRIBUTE.get(bonus.attribute());
			if (holder.isEmpty()) continue;
			var id = Identifier.fromNamespaceAndPath(MODIFIER_NAMESPACE, "bonus_" + (n++));
			modifiers = modifiers.withModifierAdded(holder.get(), new AttributeModifier(id, bonus.amount(), rule.operation()), rule.slot());
		}
		stack.set(DataComponents.ATTRIBUTE_MODIFIERS, modifiers);

		String previous = data == null ? "" : data.getUnsafe().getStringOr(MARKER + "_set", "");
		String nowSet = BetterCombatAndSpells.apply(stack, rule.attackStyle(), rule.spells(), rule.spellType(), previous);

		CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
		tag.putString(MARKER, rule.signature());
		tag.putString(MARKER + "_set", nowSet);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return true;
	}

	/** Descrizione dei bonus attuali di un oggetto (per il comando di controllo). */
	public static String describe(ItemStack stack) {
		Rule rule = ruleFor(stack);
		if (rule == null) return "";
		Map<String, Object> out = new LinkedHashMap<>();
		if (!rule.attributes().isEmpty()) out.put("attributi", rule.attributes().stream().map(b -> b.attribute() + "=" + b.amount()).toList());
		if (rule.attackStyle() != null) out.put("stile", rule.attackStyle());
		if (!rule.spells().isEmpty()) out.put("spell", rule.spells());
		return " bonus " + out;
	}
}
