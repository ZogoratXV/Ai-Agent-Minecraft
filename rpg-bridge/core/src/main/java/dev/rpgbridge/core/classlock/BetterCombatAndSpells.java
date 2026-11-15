/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core.classlock;

import java.util.List;

import net.bettercombat.api.component.BetterCombatDataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.spell_engine.api.spell.container.SpellContainer;
import net.spell_engine.api.spell.SpellDataComponents;

/** Componenti di Better Combat (preset dell'arma) e Spell Engine (contenitore di spell) per i bonus delle armi. */
final class BetterCombatAndSpells {
	private BetterCombatAndSpells() {
	}

	/**
	 * Scrive i componenti richiesti. Toglie solo quelli che il bridge aveva messo in precedenza ({@code previous}
	 * contiene "bc" e/o "spell"), mai quelli propri dell'oggetto (es. le spell di una bacchetta della mod).
	 * Restituisce i componenti ora messi dal bridge.
	 */
	static String apply(ItemStack stack, Identifier attackStyle, List<String> spells, String spellType, String previous) {
		StringBuilder set = new StringBuilder();
		if (attackStyle != null) {
			stack.set(BetterCombatDataComponents.WEAPON_PRESET_ID, attackStyle);
			set.append("bc,");
		} else if (previous.contains("bc")) {
			stack.remove(BetterCombatDataComponents.WEAPON_PRESET_ID);
		}
		if (!spells.isEmpty()) {
			SpellContainer.ContentType type;
			try {
				type = SpellContainer.ContentType.valueOf(spellType);
			} catch (IllegalArgumentException e) {
				type = SpellContainer.ContentType.MAGIC;
			}
			stack.set(SpellDataComponents.SPELL_CONTAINER, new SpellContainer(type, "", "", "", 0, List.copyOf(spells), 0));
			set.append("spell,");
		} else if (previous.contains("spell")) {
			stack.remove(SpellDataComponents.SPELL_CONTAINER);
		}
		return set.toString();
	}
}
