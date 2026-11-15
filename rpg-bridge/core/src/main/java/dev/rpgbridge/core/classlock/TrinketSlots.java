/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core.classlock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SlotAccess;

/** Slot di Trinkets Updated (es. lo slot "spell book" di Spell Engine), via riflessione: Trinkets è facoltativo. */
public final class TrinketSlots {
	private static volatile Boolean available;
	private static Method getAttachment;
	private static Method allEquipped;

	private TrinketSlots() {
	}

	/** Slot di Trinkets occupati dell'entità (vuoto se Trinkets non c'è). Ogni slot si legge e scrive con get/set. */
	public static List<SlotAccess> equipped(LivingEntity entity) {
		if (!init()) return List.of();
		try {
			Object attachment = getAttachment.invoke(null, entity);
			if (attachment == null) return List.of();
			List<SlotAccess> slots = new ArrayList<>();
			for (Object access : (List<?>) allEquipped.invoke(attachment, false)) {
				if (access instanceof SlotAccess slot) slots.add(slot);
			}
			return slots;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return List.of();
		}
	}

	private static boolean init() {
		Boolean ok = available;
		if (ok != null) return ok;
		synchronized (TrinketSlots.class) {
			if (available != null) return available;
			try {
				if (!FabricLoader.getInstance().isModLoaded("trinkets_updated") && !FabricLoader.getInstance().isModLoaded("trinkets")) {
					available = false;
					return false;
				}
				Class<?> api = Class.forName("eu.pb4.trinkets.api.TrinketsApi");
				Class<?> attachment = Class.forName("eu.pb4.trinkets.api.TrinketAttachment");
				getAttachment = api.getMethod("getAttachment", LivingEntity.class);
				allEquipped = attachment.getMethod("allEquipped", boolean.class);
				available = true;
			} catch (ReflectiveOperationException | RuntimeException e) {
				available = false;
			}
			return available;
		}
	}
}
